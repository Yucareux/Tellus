/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers.block;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
#if MC_VER <= MC_1_7_10
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.common.util.ForgeDirection;
import com.seibel.distanthorizons.common.backports.IBlockState;
import com.seibel.distanthorizons.common.backports.FakeBlockState;
import com.seibel.distanthorizons.common.backports.FakeWorld;
#elif MC_VER <= MC_1_12_2
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.*;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.util.math.BlockPos;
#else
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
#endif
import com.seibel.distanthorizons.core.logging.DhLogger;
#if MC_VER > MC_1_7_10
import org.jetbrains.annotations.Nullable;
#endif

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

#if MC_VER <= MC_1_7_10
#elif MC_VER < MC_1_21_5
import net.minecraft.client.renderer.block.model.BakedQuad;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
#else
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.client.color.block.BlockTintSource;
#endif

/**
 * This stores and calculates the colors
 * the given BlockState should have based
 * on the given {@link IClientLevelWrapper}.
 *
 * @see ColorUtil
 */
public class ClientBlockStateColorCache
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MC = Minecraft.getMinecraft();
	#else
	private static final Minecraft MC = Minecraft.getInstance();
	#endif
	
	
	#if MC_VER <= MC_1_12_2
	#else
	private static final HashSet<BlockState> BLOCK_STATES_THAT_NEED_LEVEL = new HashSet<>();
	#endif

	#if MC_VER <= MC_1_12_2
	private static final HashSet<IBlockState> BROKEN_BLOCK_STATES = new HashSet<>();
	#else
	private static final HashSet<BlockState> BROKEN_BLOCK_STATES = new HashSet<>();
	#endif
	
	/** 
	 * Methods using MC's "RandomSource" object aren't thread safe <br>
	 * so we need to put locks around that logic. <br>
	 * specifically:
	 * <code>
	 * getBlockModel(this.blockState).getQuads(this.blockState, direction, RANDOM)
	 * </code>
	 */
	private static final ReentrantLock RESOLVE_LOCK = new ReentrantLock();
	
	public static final int INVALID_COLOR = -1;
	
	
	/** This is the order each direction on a block is processed when attempting to get the texture/color */
	#if MC_VER <= MC_1_7_10
	// 1.7.10 doesn't use quads/directions; texture is fetched via IIcon directly
	#else
	private static final @Nullable EDhDirection[] COLOR_RESOLUTION_DIRECTION_ORDER =
		{
			EDhDirection.UP,
			null, // null represents "unculled" faces, IE the top of farmland
			EDhDirection.NORTH,
			EDhDirection.EAST,
			EDhDirection.WEST,
			EDhDirection.SOUTH,
			EDhDirection.DOWN
		};
	#endif
	
	private static final int FLOWER_COLOR_SCALE = 5;
	
	
	
	#if MC_VER <= MC_1_7_10
	private static final ThreadLocal<FakeWorld> FAKE_WORLD_REF = ThreadLocal.withInitial(FakeWorld::new);
	#endif

	private final IClientLevelWrapper clientLevelWrapper;
	#if MC_VER <= MC_1_12_2
	private final IBlockState blockState;
	#else
	private final BlockState blockState;
	#endif
	private final BlockStateWrapper blockStateWrapper;
	
	private boolean isColorResolved = false;
	private int baseColor = 0;
	private boolean needPostTinting = false;
	private int tintIndex = 0;
	
	
	
	//===========//
	// constants //
	//===========//
	//region
	
	private static final int MIN_SRGB_BITS = 0x39000000; // 2^(-13)
	private static final int MAX_SRGB_BITS = 0x3f7fffff; // 1.0 - f32::EPSILON
	private static final float MIN_SRGB_BOUND = Float.intBitsToFloat(MIN_SRGB_BITS);
	private static final float MAX_SRGB_BOUND = Float.intBitsToFloat(MAX_SRGB_BITS);
	
	private static final int[] linearToSrgbTable = new int[] 
		{
			//region
			0x0073000d, 0x007a000d, 0x0080000d, 0x0087000d, 0x008d000d, 0x0094000d, 0x009a000d, 0x00a1000d,
			0x00a7001a, 0x00b4001a, 0x00c1001a, 0x00ce001a, 0x00da001a, 0x00e7001a, 0x00f4001a, 0x0101001a,
			0x010e0033, 0x01280033, 0x01410033, 0x015b0033, 0x01750033, 0x018f0033, 0x01a80033, 0x01c20033,
			0x01dc0067, 0x020f0067, 0x02430067, 0x02760067, 0x02aa0067, 0x02dd0067, 0x03110067, 0x03440067,
			0x037800ce, 0x03df00ce, 0x044600ce, 0x04ad00ce, 0x051400ce, 0x057b00c5, 0x05dd00bc, 0x063b00b5,
			0x06970158, 0x07420142, 0x07e30130, 0x087b0120, 0x090b0112, 0x09940106, 0x0a1700fc, 0x0a9500f2,
			0x0b0f01cb, 0x0bf401ae, 0x0ccb0195, 0x0d950180, 0x0e56016e, 0x0f0d015e, 0x0fbc0150, 0x10630143,
			0x11070264, 0x1238023e, 0x1357021d, 0x14660201, 0x156601e9, 0x165a01d3, 0x174401c0, 0x182401af,
			0x18fe0331, 0x1a9602fe, 0x1c1502d2, 0x1d7e02ad, 0x1ed4028d, 0x201a0270, 0x21520256, 0x227d0240,
			0x239f0443, 0x25c003fe, 0x27bf03c4, 0x29a10392, 0x2b6a0367, 0x2d1d0341, 0x2ebe031f, 0x304d0300,
			0x31d105b0, 0x34a80555, 0x37520507, 0x39d504c5, 0x3c37048b, 0x3e7c0458, 0x40a8042a, 0x42bd0401,
			0x44c20798, 0x488e071e, 0x4c1c06b6, 0x4f76065d, 0x52a50610, 0x55ac05cc, 0x5892058f, 0x5b590559,
			0x5e0c0a23, 0x631c0980, 0x67db08f6, 0x6c55087f, 0x70940818, 0x74a007bd, 0x787d076c, 0x7c330723,
			//endregion
		};
	
	private static final float[] srgbToLinearTable = new float[] 
		{
			//region
			0.0f, 0.000303527f, 0.000607054f, 0.00091058103f, 0.001214108f, 0.001517635f, 0.0018211621f, 0.002124689f,
			0.002428216f, 0.002731743f, 0.00303527f, 0.0033465356f, 0.003676507f, 0.004024717f, 0.004391442f,
			0.0047769533f, 0.005181517f, 0.0056053917f, 0.0060488326f, 0.006512091f, 0.00699541f, 0.0074990317f,
			0.008023192f, 0.008568125f, 0.009134057f, 0.009721218f, 0.010329823f, 0.010960094f, 0.011612245f,
			0.012286487f, 0.012983031f, 0.013702081f, 0.014443844f, 0.015208514f, 0.015996292f, 0.016807375f,
			0.017641952f, 0.018500218f, 0.019382361f, 0.020288562f, 0.02121901f, 0.022173883f, 0.023153365f,
			0.02415763f, 0.025186857f, 0.026241222f, 0.027320892f, 0.028426038f, 0.029556843f, 0.03071345f, 0.03189604f,
			0.033104774f, 0.03433981f, 0.035601325f, 0.036889452f, 0.038204376f, 0.039546248f, 0.04091521f, 0.042311423f,
			0.043735042f, 0.045186214f, 0.046665095f, 0.048171833f, 0.049706575f, 0.051269468f, 0.052860655f, 0.05448028f,
			0.056128494f, 0.057805434f, 0.05951124f, 0.06124607f, 0.06301003f, 0.06480328f, 0.06662595f, 0.06847818f,
			0.07036011f, 0.07227186f, 0.07421358f, 0.07618539f, 0.07818743f, 0.08021983f, 0.082282715f, 0.084376216f,
			0.086500466f, 0.088655606f, 0.09084173f, 0.09305898f, 0.095307484f, 0.09758736f, 0.09989874f, 0.10224175f,
			0.10461649f, 0.10702311f, 0.10946172f, 0.111932434f, 0.11443538f, 0.116970696f, 0.11953845f, 0.12213881f,
			0.12477186f, 0.12743773f, 0.13013652f, 0.13286836f, 0.13563336f, 0.13843165f, 0.14126332f, 0.1441285f,
			0.1470273f, 0.14995982f, 0.15292618f, 0.1559265f, 0.15896086f, 0.16202943f, 0.16513224f, 0.16826946f,
			0.17144115f, 0.17464745f, 0.17788847f, 0.1811643f, 0.18447503f, 0.1878208f, 0.19120172f, 0.19461787f,
			0.19806935f, 0.2015563f, 0.20507877f, 0.2086369f, 0.21223079f, 0.21586053f, 0.21952623f, 0.22322798f,
			0.22696589f, 0.23074007f, 0.23455065f, 0.23839766f, 0.2422812f, 0.2462014f, 0.25015837f, 0.25415218f,
			0.2581829f, 0.26225072f, 0.26635566f, 0.27049786f, 0.27467737f, 0.27889434f, 0.2831488f, 0.2874409f,
			0.2917707f, 0.29613832f, 0.30054384f, 0.30498737f, 0.30946895f, 0.31398875f, 0.31854683f, 0.32314324f,
			0.32777813f, 0.33245158f, 0.33716366f, 0.34191445f, 0.3467041f, 0.3515327f, 0.35640025f, 0.36130688f,
			0.3662527f, 0.37123778f, 0.37626222f, 0.3813261f, 0.38642952f, 0.39157256f, 0.3967553f, 0.40197787f,
			0.4072403f, 0.4125427f, 0.41788515f, 0.42326775f, 0.42869055f, 0.4341537f, 0.43965724f, 0.44520125f,
			0.45078585f, 0.45641106f, 0.46207705f, 0.46778384f, 0.47353154f, 0.47932023f, 0.48514998f, 0.4910209f,
			0.49693304f, 0.5028866f, 0.50888145f, 0.5149178f, 0.5209957f, 0.52711535f, 0.5332766f, 0.5394797f,
			0.5457247f, 0.5520116f, 0.5583406f, 0.5647117f, 0.57112503f, 0.57758063f, 0.5840786f, 0.590619f, 0.597202f,
			0.60382754f, 0.61049575f, 0.61720675f, 0.62396055f, 0.63075733f, 0.637597f, 0.6444799f, 0.6514058f,
			0.65837497f, 0.66538745f, 0.67244333f, 0.6795426f, 0.68668544f, 0.69387203f, 0.70110214f, 0.70837605f,
			0.7156938f, 0.72305536f, 0.730461f, 0.7379107f, 0.7454045f, 0.75294244f, 0.76052475f, 0.7681514f, 0.77582246f,
			0.78353804f, 0.79129815f, 0.79910296f, 0.8069525f, 0.8148468f, 0.822786f, 0.8307701f, 0.83879924f, 0.84687346f,
			0.8549928f, 0.8631574f, 0.87136734f, 0.8796226f, 0.8879232f, 0.89626956f, 0.90466136f, 0.913099f, 0.92158204f,
			0.93011117f, 0.9386859f, 0.9473069f, 0.9559735f, 0.9646866f, 0.9734455f, 0.98225087f, 0.9911022f, 1.0f
			//endregion
		};
	
	// these are threadlocals since AbstractDhTintGetter use local variables to handle color queries
	#if MC_VER > MC_1_12_2
	private static final ThreadLocal<TintWithoutLevelOverrider> TINT_WITHOUT_LEVEL_OVERRIDE_THREAD_LOCAL = ThreadLocal.withInitial(TintWithoutLevelOverrider::new);
	private static final ThreadLocal<TintGetterOverride> TINT_OVERRIDE_THREAD_LOCAL = ThreadLocal.withInitial(TintGetterOverride::new);
	#endif
	
	private static final ThreadLocal<DhApiBlockColorOverrideEvent.EventParam> COLOR_OVERRIDE_EVENT_PARAM_THREAD_LOCAL = ThreadLocal.withInitial(DhApiBlockColorOverrideEvent.EventParam::new);
	
	//endregion
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	#if MC_VER <= MC_1_12_2
	public ClientBlockStateColorCache(IBlockState blockState, IClientLevelWrapper clientLevelWrapper)
	#else
	public ClientBlockStateColorCache(BlockState blockState, IClientLevelWrapper clientLevelWrapper)
	#endif
	{
		this.blockState = blockState;
		this.blockStateWrapper = BlockStateWrapper.fromBlockState(blockState, clientLevelWrapper);
		this.clientLevelWrapper = clientLevelWrapper;

		this.resolveColors();
	}
	
	//endregion
	
	
	
	//===================//
	// color calculation //
	//===================//
	//region
	
	private void resolveColors()
	{
		if (this.isColorResolved)
		{
			return;
		}

		#if MC_VER <= MC_1_7_10
		try
		{
			RESOLVE_LOCK.lock();
			FakeBlockState blockState = (FakeBlockState)this.blockState; 

			TextureAtlasSprite sprite = TextureAtlasSpriteWrapper.resolveFaceSprite(
				blockState, ForgeDirection.UP.ordinal());
			if (sprite != null)
			{
				this.baseColor = calculateColorFromTexture(
					sprite,
					EColorMode.getColorMode(blockState.block));
			}
			else
			{
				LOGGER.warn("Can't get a usable icon for block type " + blockState.block.getClass());
				// bit-wise to set max alpha 255
				this.baseColor = 0xFF000000 | blockState.block.getBlockColor();
			}

			// Backup tinting heuristics
			// (aka guess that the block should be tinted if it's entirely white)
			this.needPostTinting = blockState.block.getBlockColor() != 0xFFFFFF; // white
			
			if (blockState.block instanceof BlockGrass
				|| blockState.block instanceof BlockLeavesBase
				|| blockState.block instanceof BlockBush)
			{
				this.needPostTinting = true;
			}
			
			if (blockState.block == Blocks.water 
				|| blockState.block == Blocks.flowing_water)
			{
				this.needPostTinting = true;
			}
			
			// Necessary for the Lord of the Rings (LOTR) mod
			if (blockState.block instanceof IShearable)
			{
				this.needPostTinting = true;
			}
			
			this.tintIndex = 0;

			this.isColorResolved = true;
		}
		finally
		{
			RESOLVE_LOCK.unlock();
		}
		
		#else
		
		try
		{
			// getQuads() isn't thread safe so we need to put this logic in a lock
			RESOLVE_LOCK.lock();

			#if MC_VER <= MC_1_12_2
			if (this.blockState.getRenderType() == EnumBlockRenderType.ENTITYBLOCK_ANIMATED)
			{
				this.needPostTinting = false;
				this.tintIndex = 0;
				this.baseColor = ColorUtil.argbToInt(255,
					this.blockStateWrapper.getMapColor().getRed(),
					this.blockStateWrapper.getMapColor().getGreen(),
					this.blockStateWrapper.getMapColor().getBlue());
				this.isColorResolved = true;
				return;
			}
			#endif
			
			if (!this.blockStateWrapper.isLiquid())
			{
				// look for the first non-empty direction
				List<BakedQuad> quads = null;
				
				EDhDirection direction;
				
				for (int i = 0; i < COLOR_RESOLUTION_DIRECTION_ORDER.length; i++)
				{
					direction = COLOR_RESOLUTION_DIRECTION_ORDER[i];
					try
					{
						quads = this.getQuadsForDirection(direction);
					}
					catch (Exception ignore)
					{
						// failing to get quads can happen in the block is invalid
						// (i.e. AIR is somehow passed in)
					}
					
					// return for the first valid direction we find
					if (quads != null 
						&& !quads.isEmpty()
						// for rotated blocks (ie logs) we want the side instead of the top,
						// so logs use their bark side instead of their cut/inner side
						&& !(
							#if MC_VER <= MC_1_12_2
							this.blockState.getBlock() instanceof BlockRotatedPillar
							#else
							this.blockState.getBlock() instanceof RotatedPillarBlock
							#endif
							&& direction == EDhDirection.UP
							)
						)
					{
						break;
					}
				}
				
				if (quads == null || quads.isEmpty())
				{
					try
					{
						quads = this.getUnculledQuads();
					}
					catch (Exception ignore)
					{
						// failing to get quads can happen in the block is invalid
						// (i.e. AIR is somehow passed in)
					}
				}
				
				if (quads != null 
					&& !quads.isEmpty() 
					&& quads.get(0) != null)
				{
					try
					{
						BakedQuad firstQuad = quads.get(0);
						
						#if MC_VER <= MC_1_12_2
						this.needPostTinting = firstQuad.hasTintIndex();						
						#elif MC_VER <= MC_1_21_11
						this.needPostTinting = firstQuad.isTinted();
						#else
						this.needPostTinting = firstQuad.materialInfo().isTinted();
						#endif
					
						#if MC_VER <= MC_1_21_4
						this.tintIndex = firstQuad.getTintIndex();
						#elif MC_VER <= MC_1_21_11
						this.tintIndex = firstQuad.tintIndex();
						#else
						this.tintIndex = firstQuad.materialInfo().tintIndex();
						#endif
						
						#if MC_VER < MC_1_17_1 && MC_VER > MC_1_12_2
						this.baseColor = calculateColorFromTexture(
	                        firstQuad.sprite,
							EColorMode.getColorMode(this.blockState.getBlock()));
						#elif MC_VER < MC_1_21_5
						this.baseColor = calculateColorFromTexture(
	                        firstQuad.getSprite(),
							EColorMode.getColorMode(this.blockState.getBlock()));
						#elif MC_VER <= MC_1_21_11
						this.baseColor = calculateColorFromTexture(
							firstQuad.sprite(),
							EColorMode.getColorMode(this.blockState.getBlock()));
						#else
						this.baseColor = calculateColorFromTexture(
							firstQuad.materialInfo().sprite(),
							EColorMode.getColorMode(this.blockState.getBlock()));
						#endif
					}
					catch (Exception e)
					{
						// Shouldn't normally happen, but there was at least 
						// one report of MC's texture being un-loaded
						// which prevented us from getting the texture.
						// So we should have some basic backup logic.
						
						LOGGER.warn("Failed to get texture color for block ["+this.blockStateWrapper.getSerialString()+"] due to: ["+e.getMessage()+"], falling back to particle color.");
						
						this.needPostTinting = false;
						this.tintIndex = 0;
						this.baseColor = this.getParticleIconColor();
					}
				}
				else
				{
					// Backup method.
					this.needPostTinting = false;
					this.tintIndex = 0;
					this.baseColor = this.getParticleIconColor();
				}
			}
			else
			{
				// Liquid Block
				this.needPostTinting = true;
				this.tintIndex = 0;
				this.baseColor = this.getParticleIconColor();
			}
			
			
			this.isColorResolved = true;
		}
		catch (Exception resolveError)
		{
			LOGGER.warn("Failed to get color for block ["+this.blockStateWrapper.getSerialString()+"], error: ["+resolveError.getMessage()+"]. Attempting to use particle icon color...", resolveError);
			
			this.needPostTinting = true;
			this.tintIndex = 0;
			
			try
			{
				this.baseColor = this.getParticleIconColor();
			}
			catch (Exception getParticleIconError)
			{
				LOGGER.warn("Failed to get particle icon color for block ["+this.blockStateWrapper.getSerialString()+"], error: ["+getParticleIconError.getMessage()+"], block will render as hot pink.", getParticleIconError);
				this.baseColor = ColorUtil.HOT_PINK;
			}
		}
		finally
		{
			RESOLVE_LOCK.unlock();
		}
		#endif
	}

	#if MC_VER > MC_1_7_10
	@Nullable
	private List<BakedQuad> getUnculledQuads() throws Exception { return this.getQuadsForDirection(null); }
	@Nullable
	private List<BakedQuad> getQuadsForDirection(@Nullable EDhDirection direction) throws Exception
	{
		//=========================//
		// specific state handling //
		//=========================//
		//region
		
		#if MC_VER <= MC_1_12_2
		IBlockState effectiveBlockState = this.blockState;
		#else
		BlockState effectiveBlockState = this.blockState;
		#endif
		
		// if this block is a slab, use it's double variant so we can get the top face,
		// otherwise the color will use the side, which isn't as accurate
		#if MC_VER <= MC_1_12_2
		if (this.blockState.getBlock() instanceof BlockSlab && !((BlockSlab) this.blockState.getBlock()).isDouble())
		{
			effectiveBlockState = this.blockState.withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP);
		}
		#else
		if (this.blockState.getBlock() instanceof SlabBlock)
		{
			effectiveBlockState = this.blockState.setValue( SlabBlock.TYPE, SlabType.DOUBLE );
		}
		#endif
		
		// huge mushroom block sides will show the inner color,
		// which isn't what you want to see at a distance,
		// you want to see the primary color (ie red for red mushrooms)
		// which is shown on all sides for the default state
		#if MC_VER <= MC_1_12_2
		if (this.blockState.getBlock() instanceof BlockHugeMushroom)
		{
			effectiveBlockState = this.blockState.getBlock().getDefaultState(); 
		}
		#else
		if (this.blockState.getBlock() instanceof HugeMushroomBlock)
		{
			effectiveBlockState = this.blockState.getBlock().defaultBlockState(); 
		}
		#endif
		
		//endregion
		
		
		
		List<BakedQuad> quads = QuadWrapper.getQuadsForDirection(effectiveBlockState, direction);
		return quads;
	}
	#endif

	/** if multiple frames are present, just the first one will be used */
	public static int calculateColorFromTexture(TextureAtlasSprite texture, EColorMode colorMode)
	{
		int count = 0;
		int alpha = 0;
		double red = 0;
		double green = 0;
		double blue = 0;
		int tempColor;
		
		// don't render Chiseled blocks.
		// Since EColorMode is set per block, you only need to check this once.
		if (colorMode != EColorMode.Chisel)
		{
			int textureHeight = TextureAtlasSpriteWrapper.getHeight(texture);
			int textureWidth = TextureAtlasSpriteWrapper.getWidth(texture);
			for (int v = 0; v < textureHeight; v++)
			{
				for (int u = 0; u < textureWidth; u++)
				{
					tempColor = TextureAtlasSpriteWrapper.getPixelARGB(texture, 0, u, v);
					
					int r = ColorUtil.getRed(tempColor);
					int g = ColorUtil.getGreen(tempColor);
					int b = ColorUtil.getBlue(tempColor);
					int a = ColorUtil.getAlpha(tempColor);
					
					int scale = 1;
					if (colorMode == EColorMode.Leaves)
					{
						if (a == 0)
						{
							continue; //same long grass
						}
						else
						{
							a = 255; //just in case there are semi transparent pixels
						}
					}
					else if (a == 0 && colorMode != EColorMode.Glass)
					{
						continue;
					}
					else if (colorMode == EColorMode.Flower && (g + 25 < b || g + 25 < r))
					{
						scale = FLOWER_COLOR_SCALE;
					}
					count += scale;
					//apparently alpha is linear
					alpha += a * scale;
					//gamma correction is complicated
					red += srgbToLinearTable[r] * a * scale;
					green += srgbToLinearTable[g] * a * scale;
					blue += srgbToLinearTable[b] * a * scale;
				}
			}
		}
		
		if (count == 0)
		{
			// this block is entirely transparent
			tempColor = ColorUtil.argbToInt(0, 255, 255, 255);
		}
		else
		{
			// determine the average color
			tempColor = ColorUtil.argbToInt(
					alpha / count,
					linearToSrgb((float) (red / (double) alpha)),
					linearToSrgb((float) (green / (double) alpha)),
					linearToSrgb((float) (blue / (double) alpha)));
		}
		
		//check if not missing texture
		if (tempColor == ColorUtil.argbToInt(255, 182, 0, 182))
		{
			//make it not render at all
			tempColor = ColorUtil.argbToInt(0, 255, 255, 255);
		}
		return tempColor;
	}
	/**
	 * This method was suggested by IMS from the Iris/Sodium team. 
	 * That's where the numbers and code are based.
	 */
	private static int linearToSrgb(float color)
	{
		if (!(color > MIN_SRGB_BOUND)) 
		{
			color = MIN_SRGB_BOUND;
		}
		
		if (color > MAX_SRGB_BOUND) 
		{
			color = MAX_SRGB_BOUND;
		}
		int inputBits = Float.floatToRawIntBits(color);
		int entry = linearToSrgbTable[((inputBits - MIN_SRGB_BITS) >> 20)];
		
		int bias = (entry >>> 16) << 9;
		int scale = entry & 0xffff;
		int t = (inputBits >>> 12) & 0xff;
		
		return (bias + (scale * t)) >>> 16;
	}
	
	#if MC_VER > MC_1_7_10
	private int getParticleIconColor()
	{
		// Air can be null which will cause issues below,
		// just use a static color, it shouldn't be rendered anyway.
		// This is just to capture a rare bug state where we attempt
		// to get air's color.
		if (BlockStateWrapper.isAir(this.blockState))
		{
			return ColorUtil.INVISIBLE;
		}

		return calculateColorFromTexture(
			#if MC_VER <= MC_1_12_2
			Minecraft.getMinecraft().getBlockRendererDispatcher().getBlockModelShapes().getTexture(this.blockState),
			#elif MC_VER <= MC_1_21_11
			Minecraft.getInstance().getModelManager().getBlockModelShaper().getParticleIcon(this.blockState),
			#else
			Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(this.blockState).particleMaterial().sprite(),
			#endif
				EColorMode.getColorMode(this.blockState.getBlock()));
	}
	#endif
	
	//endregion
	
	
	
	//===============//
	// public getter //
	//===============//
	//region
	
	public int getColor(
		BiomeWrapper biomeWrapper, FullDataSourceV2 fullDataSource, DhBlockPos blockPos,
		boolean allowApiOverride)
	{
		// only get the tint if the block needs to be tinted
		int tintColor = ClientBlockStateColorCache.INVALID_COLOR;
		
		if (this.needPostTinting)
		{
			// don't try tinting blocks that don't support our method of tint getting
			if (BROKEN_BLOCK_STATES.contains(this.blockState))
			{
				return this.baseColor;
			}
			
			
			// attempt to get the tint
			try
			{
				#if MC_VER <= MC_1_7_10
				// 1.7.10: route through FakeWorld + Block.colorMultiplier so block-tint logic
				// (grass/foliage/water/etc.) gets a usable IBlockAccess + biome lookup
				IBlockAccess realLevel = (IBlockAccess) this.clientLevelWrapper.getWrappedMcObject();
				FakeBlockState fakeBlockState = (FakeBlockState)this.blockState;
				
				FakeWorld fakeWorld = FAKE_WORLD_REF.get();
				fakeWorld.update(realLevel, biomeWrapper.biome, blockPos.getX(), blockPos.getY(), blockPos.getZ(), fakeBlockState);
				
				tintColor = fakeBlockState.block.colorMultiplier(fakeWorld, blockPos.getX(), blockPos.getY(), blockPos.getZ());
				#elif MC_VER <= MC_1_12_2
				// 1.12.2 doesn't have BlockAndTintGetter -> get tintColor from biome
				WorldClient world = (WorldClient) this.clientLevelWrapper.getWrappedMcObject();
				BlockPos mcPos = new BlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());

				Block block = this.blockState.getBlock();
				if (block instanceof BlockGrass
					|| block instanceof BlockBush)
				{
					tintColor = biomeWrapper.biome.getGrassColorAtPos(mcPos);
				}
				else if (block instanceof BlockLeaves)
				{
					tintColor = biomeWrapper.biome.getFoliageColorAtPos(mcPos);
				}
				else if (block instanceof BlockLiquid) // We don't want lava to fall into the else block
				{
					if(block == Blocks.WATER 
						|| block == Blocks.FLOWING_WATER)
					{
						tintColor = biomeWrapper.biome.getWaterColor();
					}
				}
				else
				{
					BlockColors blockColors = Minecraft.getMinecraft().getBlockColors();
					tintColor = blockColors.colorMultiplier(blockState, world, mcPos, this.tintIndex);

					if (tintColor == ClientBlockStateColorCache.INVALID_COLOR)
					{
						tintColor = blockColors.getColor(blockState, world, mcPos);
					}
				}
				#else
				// try to use the fast tint getter logic first
				if (!BLOCK_STATES_THAT_NEED_LEVEL.contains(this.blockState))
				{
					try
					{					
						TintWithoutLevelOverrider tintOverride = TINT_WITHOUT_LEVEL_OVERRIDE_THREAD_LOCAL.get();
						tintOverride.update(biomeWrapper, this.blockStateWrapper, fullDataSource, this.clientLevelWrapper);
						
						// try using DH's cached tint values first if possible
						tintColor = tintOverride.tryGetBlockTint(new DhBlockPosMutable(blockPos));
						if (tintColor == ClientBlockStateColorCache.INVALID_COLOR)
						{
							// one or more tint values weren't calculated,
							// we need MC's color resolver
							#if MC_VER <= MC_1_21_11
							tintColor = Minecraft.getInstance()
								.getBlockColors()
								.getColor(this.blockState,
										tintOverride, // tintOverride will save the result of this query to speed up future queries
										McObjectConverter.convert(blockPos),
										this.tintIndex);
							#else
							BlockTintSource tintSource = Minecraft.getInstance()
								.getBlockColors()
								.getTintSource(this.blockState, this.tintIndex);
							// a tint source may be null for blocks that don't actually need tinting
							// in that case the base color should be sufficient
							// Example: cherry blossom leaves
							if (tintSource != null)
							{
								BlockPos mcPos = McObjectConverter.convert(blockPos);
								tintColor = tintSource.colorInWorld(this.blockState, tintOverride, mcPos);
								if (tintColor == ClientBlockStateColorCache.INVALID_COLOR)
								{
									tintColor = tintSource.colorAsTerrainParticle(this.blockState, tintOverride, mcPos);
								}
							}
							
							// save this color to speed up future queries
							TintWithoutLevelOverrider.setStaticColor(this.blockStateWrapper, biomeWrapper, tintColor);
							// try to get the blended color with this new information
							tintColor = tintOverride.tryGetBlockTint(new DhBlockPosMutable(blockPos));
						#endif
						}
					}
					catch (Exception e)
					{
						#if MC_VER <= MC_1_21_11
						// this exception generally occurs if the tint requires other blocks besides itself
						LOGGER.debug("Unable to use ["+ TintWithoutLevelOverrider.class.getSimpleName()+"] to get the block tint for block: [" + this.blockState + "] and biome: [" + biomeWrapper + "] at pos: " + blockPos + ". Error: [" + e.getMessage() + "]. Attempting to use backup method...", e);
						BLOCK_STATES_THAT_NEED_LEVEL.add(this.blockState);
						#else
						// only display the error once per block/biome type to reduce log spam
						if (!BROKEN_BLOCK_STATES.contains(this.blockState))
						{
							LOGGER.warn("Failed to get block color for block: [" + this.blockState + "] and biome: [" + biomeWrapper + "] at pos: " + blockPos + ". Error: [" + e.getMessage() + "]. Note: future errors for this block/biome will be ignored.", e);
							BROKEN_BLOCK_STATES.add(this.blockState);
						}
						#endif
					}
				}
				#endif
				
				
				// level-specific logic is only needed for MC 1.21.11 and older
				#if MC_VER <= MC_1_21_11 && MC_VER > MC_1_12_2
				// use the level logic only if requested
				if (BLOCK_STATES_THAT_NEED_LEVEL.contains(this.blockState))
				{
					// the level shouldn't be used all the time due to it breaking some blocks tinting
					// specifically oceans don't render correctly
					
					TintGetterOverride tintOverride = TINT_OVERRIDE_THREAD_LOCAL.get();
					tintOverride.update(biomeWrapper, this.blockStateWrapper, fullDataSource, this.clientLevelWrapper);
					
					tintColor = tintOverride.tryGetBlockTint(new DhBlockPosMutable(blockPos));
					if (tintColor == ClientBlockStateColorCache.INVALID_COLOR)
					{
						tintColor = Minecraft.getInstance()
								.getBlockColors()
								.getColor(this.blockState,
										tintOverride,
										McObjectConverter.convert(blockPos),
										this.tintIndex);
					}
				}
				#endif
				
			}
			catch (Exception e)
			{
				// only display the error once per block/biome type to reduce log spam
				if (!BROKEN_BLOCK_STATES.contains(this.blockState))
				{
					LOGGER.warn("Failed to get block color for block: [" + this.blockState + "] and biome: [" + biomeWrapper + "] at pos: " + blockPos + ". Error: [" + e.getMessage() + "]. Note: future errors for this block/biome will be ignored.", e);
					BROKEN_BLOCK_STATES.add(this.blockState);
				}
			}
		}
		
		
		int returnColor;
		if (tintColor != ClientBlockStateColorCache.INVALID_COLOR)
		{
			returnColor = ColorUtil.multiplyARGBwithRGB(this.baseColor, tintColor);
		}
		else
		{
			// unable to get the tinted color, use the base color instead
			returnColor = this.baseColor;
		}
		
		
		// only fire the API event if allowed
		// (done to prevent infinite loops if called during by another color resolution event)
		if (allowApiOverride
			// if the API event is requested
			// (this is done to reduce GC pressure and speed up color getting)
			&& this.blockStateWrapper.allowApiColorOverride())
		{
			DhApiBlockColorOverrideEvent.EventParam eventParam = COLOR_OVERRIDE_EVENT_PARAM_THREAD_LOCAL.get();
			eventParam.update(
				this.clientLevelWrapper, fullDataSource,
				this.blockStateWrapper, biomeWrapper, returnColor, tintColor, this.baseColor,
				blockPos.getX(), blockPos.getY(), blockPos.getZ()
			);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBlockColorOverrideEvent.class, eventParam);
			
			// let the API user override this color
			returnColor = eventParam.getColorAsInt();
		}
		
		return returnColor;
	}
	
	//endregion
	
	
	
	//=========//
	// cleanup //
	//=========//
	//region
	
	public static void clearCachedTints() 
	{
		#if MC_VER <= MC_1_12_2
		#else
		AbstractDhTintGetter.clear(); 
		#endif
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	public enum EColorMode
	{
		Default,
		Flower,
		Leaves,
		Chisel,
		Glass;
		
		static EColorMode getColorMode(Block block)
		{
			
			
			
			//========//
			// leaves //
			//========//
			//region
			
			boolean isLeavesBlock;
			#if MC_VER <= MC_1_7_10
			isLeavesBlock = block instanceof BlockLeavesBase;
			#elif MC_VER <= MC_1_12_2
			isLeavesBlock = block instanceof BlockLeaves;
	        #else
			isLeavesBlock = block instanceof LeavesBlock;
	        #endif
			if (isLeavesBlock)
			{
				return Leaves;
			}
			
			//endregion



			//========//
			// flower //
			//========//
			//region
			
			boolean isFlowerBlock;
			#if MC_VER <= MC_1_12_2
			isFlowerBlock = block instanceof BlockFlower;
			#else
			isFlowerBlock = block instanceof FlowerBlock;
			#endif
			if (isFlowerBlock)
			{
				return Flower;
			}

			//endregion
			
			
			
			//=============//
			// misc/simple //
			//=============//
			//region
			
			if (block.toString().toLowerCase().contains("glass"))
			{
				return Glass;
			}
			if (block.toString().equals("Block{chiselsandbits:chiseled}"))
			{
				return Chisel;
			}
			
			//endregion
			
			
			
			return Default;
		}
	}
	
	//endregion
	
	
	
}
