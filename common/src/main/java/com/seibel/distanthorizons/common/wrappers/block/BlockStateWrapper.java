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

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.common.wrappers.WrapperFactory;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
#if MC_VER <= MC_1_7_10
import net.minecraft.block.*;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemDye;
import cpw.mods.fml.common.registry.GameData;
import com.seibel.distanthorizons.common.backports.IBlockState;
import net.minecraftforge.fluids.IFluidBlock;
import com.seibel.distanthorizons.common.backports.FakeBlockState;
#elif MC_VER <= MC_1_12_2
import net.minecraft.block.*;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.properties.IProperty;
import net.minecraftforge.fluids.IFluidBlock;
#else
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
#endif
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

#if MC_VER == MC_1_16_5 || MC_VER == MC_1_17_1
import net.minecraft.core.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
#elif MC_VER == MC_1_18_2 || MC_VER == MC_1_19_2
import net.minecraft.tags.TagKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.EmptyBlockGetter;
#elif MC_VER > MC_1_12_2
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.Holder;
#endif

#if MC_VER <= MC_1_7_10
#elif MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation; 
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif


public class BlockStateWrapper implements IBlockStateWrapper
{
	/** example "minecraft:water" */
	public static final String RESOURCE_LOCATION_SEPARATOR = ":";
	/** example "minecraft:water_STATE_{level:0}" */
	public static final String STATE_STRING_SEPARATOR = "_STATE_";
	
	
	// must be defined before AIR, otherwise a null pointer will be thrown
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_7_10
	public static final ConcurrentHashMap<Integer, BlockStateWrapper> WRAPPER_BY_BLOCK_ID_AND_META = new ConcurrentHashMap<>();
	#elif MC_VER <= MC_1_12_2
	public static final ConcurrentHashMap<IBlockState, BlockStateWrapper> WRAPPER_BY_BLOCK_STATE = new ConcurrentHashMap<>();
	#else
    public static final ConcurrentHashMap<BlockState, BlockStateWrapper> WRAPPER_BY_BLOCK_STATE = new ConcurrentHashMap<>();
	#endif
    public static final ConcurrentHashMap<String, BlockStateWrapper> WRAPPER_BY_RESOURCE_LOCATION = new ConcurrentHashMap<>();
	
	
	/** keep track of broken blocks so we don't log every time */
	#if MC_VER <= MC_1_7_10
	private static final HashSet<String> BROKEN_RESOURCE_LOCATIONS = new HashSet<>();
	#elif MC_VER <= MC_1_21_10
	private static final HashSet<ResourceLocation> BROKEN_RESOURCE_LOCATIONS = new HashSet<>();
	#else
	private static final HashSet<Identifier> BROKEN_RESOURCE_LOCATIONS = new HashSet<>();
	#endif
	
	
	// static blocks //
	//region
	
	public static final String AIR_STRING = "AIR";
	public static final BlockStateWrapper AIR = new BlockStateWrapper(null, null, null);
	
	private static final String WATER_RESOURCE_LOCATION_STRING = "minecraft:water";
	private static BlockStateWrapper waterBlock = null;
	
	private static final String DIRT_RESOURCE_LOCATION_STRING = "minecraft:dirt";
	private static BlockStateWrapper dirtBlock = null;
	
	private static final String ICE_RESOURCE_LOCATION_STRING = "minecraft:ice";
	private static BlockStateWrapper iceBlock = null;
	
	private static final String SNOW_RESOURCE_LOCATION_STRING = "minecraft:snow";
	private static BlockStateWrapper snowBlock = null;
	
	//endregion
	
	
	private static ObjectOpenHashSet<IBlockStateWrapper> rendererIgnoredBlocks = null;
	private static ObjectOpenHashSet<IBlockStateWrapper> rendererIgnoredCaveBlocks = null;
	private static ObjectOpenHashSet<IBlockStateWrapper> waterSubsurfaceReplacementBlocks = null;
	private static ObjectOpenHashSet<IBlockStateWrapper> waterSurfaceReplacementBlocks = null;
	
	
	
	// properties //
	
	#if MC_VER <= MC_1_7_10
	// TODO why aren't we just storing the block/meta?
	/** cache this to prevent re-allocating the array each time the wrapped objects are needed */
	private final Object[] wrappedMcObject;
	#endif
	
	@Nullable
	#if MC_VER <= MC_1_12_2
	public final IBlockState blockState;
	#else
	public final BlockState blockState;
	#endif
	/** technically final, but since it requires a method call to generate it can't be marked as such */
	private String serialString;
	private final int hashCode;
	/** Should be between {@link LodUtil#BLOCK_FULLY_OPAQUE} and {@link LodUtil#BLOCK_FULLY_OPAQUE} */
	private final int opacity;
	/** used by the Iris shader mod to determine how each LOD should be rendered */
	private byte blockMaterialId = 0;
	
	private final boolean isBeaconBlock; 
	private final boolean isBeaconBaseBlock;
	private final boolean isIceBlock;
	private final boolean allowsBeaconBeamPassage;
	private final boolean renderTexture;
	private final boolean useBottomTextureForSides;
	private final boolean alwaysRasterizeTexture;
	private final boolean isSolid;
	private final boolean isLiquid;
	private final boolean allowApiColorOverride;
	private final boolean allowApiTextureOverride;
	/** null if this block can't tint beacons */
	private final Color beaconTintColor; 
	private final Color mapColor;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	
	#if MC_VER <= MC_1_7_10
	public static BlockStateWrapper fromBlockState(IBlockState blockState, ILevelWrapper levelWrapper)
	{ return fromBlockState(blockState.getBlock(), blockState.getMeta(), levelWrapper); }
	#endif
	
	/**
	 * Can be faster than BlockStateWrapper#fromBlockState(BlockState, ILevelWrapper)
	 * in cases where the same block state is expected to be referenced multiple times.
	 */
	#if MC_VER <= MC_1_7_10
	// For 1.7.10 more accurate name is fromBlockAndMeta
	public static BlockStateWrapper fromBlockState(Block block, int meta, ILevelWrapper levelWrapper, IBlockStateWrapper guess)
	#elif MC_VER <= MC_1_12_2
	public static BlockStateWrapper fromBlockState(IBlockState blockState, ILevelWrapper levelWrapper, IBlockStateWrapper guess)
	#else
	public static BlockStateWrapper fromBlockState(BlockState blockState, ILevelWrapper levelWrapper, IBlockStateWrapper guess)
	#endif
	{
		if (guess == null)
		{
			#if MC_VER <= MC_1_7_10
			return fromBlockState(block, meta, levelWrapper);
			#else
			return fromBlockState(blockState, levelWrapper);
			#endif
		}
		
		
		// guess block state
		BlockStateWrapper wrapperGuess = (BlockStateWrapper) guess;
		#if MC_VER <= MC_1_12_2
		IBlockState guessBlockState;
		#else
		BlockState guessBlockState;
		#endif
		if(isAir(wrapperGuess.blockState))
		{
			guessBlockState = null;
		}
		else
		{
			// TODO can we just grab the block/meta directly instead of casting?
			#if MC_VER <= MC_1_7_10
			Object[] wrappedObject = (Object[]) guess.getWrappedMcObject();
			guessBlockState = new FakeBlockState(
				(Block) wrappedObject[0],
				(Integer) wrappedObject[1]
			);
			#elif MC_VER <= MC_1_12_2
			guessBlockState = (IBlockState) guess.getWrappedMcObject();
			#else 
			guessBlockState = (BlockState) guess.getWrappedMcObject();
			#endif
		}
		
		// input block state
		#if MC_VER <= MC_1_12_2
		IBlockState inputBlockState;
		#else
		BlockState inputBlockState;
		#endif
		
		#if MC_VER <= MC_1_7_10
		FakeBlockState blockState = new FakeBlockState(block, meta);
		#endif

		if (isAir(blockState))
		{
			inputBlockState = null;
		}
		else
		{
			inputBlockState = blockState;
		}
		
		
		if (Objects.equals(guessBlockState, inputBlockState))
		{
			return (BlockStateWrapper) guess;
		}
		
		#if MC_VER <= MC_1_7_10
		return fromBlockState(block, meta, levelWrapper);
	    #else 
		return fromBlockState(blockState, levelWrapper);
		#endif
	}

	#if MC_VER <= MC_1_7_10
	// For 1.7.10 more accurate name is fromBlockAndMeta
	public static BlockStateWrapper fromBlockState(Block block, int meta, ILevelWrapper levelWrapper)
	#elif MC_VER <= MC_1_12_2
	public static BlockStateWrapper fromBlockState(@Nullable IBlockState blockState, ILevelWrapper levelWrapper)
	#else
	public static BlockStateWrapper fromBlockState(@Nullable BlockState blockState, ILevelWrapper levelWrapper)
	#endif
	{
		// air is a special case
		#if MC_VER <= MC_1_7_10
		if (block == null
			|| block == Blocks.air)
		#else
		if (isAir(blockState))
		#endif
		{
			return AIR;
		}
		
		#if MC_VER <= MC_1_7_10
		FakeBlockState blockState = new FakeBlockState(block, meta);
		#endif
		
		// pooling wrappers significantly improves chunk->LOD processing speed
		// and also reduces GC pressure
		#if MC_VER <= MC_1_7_10
		final int blockId = Block.getIdFromBlock(block);
		final Integer packedIdMeta = FakeBlockState.packIdAndMeta(blockId, meta);
		BlockStateWrapper existingWrapper = WRAPPER_BY_BLOCK_ID_AND_META.get(packedIdMeta);
		#else
		BlockStateWrapper existingWrapper = WRAPPER_BY_BLOCK_STATE.get(blockState);
		#endif
		if (existingWrapper != null)
		{
			return existingWrapper;
		}
		
		
		
		// synchronized so the API event only fires once per block
		#if MC_VER <= MC_1_7_10
		synchronized (WRAPPER_BY_BLOCK_ID_AND_META)
		#else
		synchronized (WRAPPER_BY_BLOCK_STATE)
		#endif
		{
			// if another thread already finished this block, use that wrapper
			#if MC_VER <= MC_1_7_10
			existingWrapper = WRAPPER_BY_BLOCK_ID_AND_META.get(packedIdMeta);
			#else
			existingWrapper = WRAPPER_BY_BLOCK_STATE.get(blockState);
			#endif
			if (existingWrapper != null)
			{
				return existingWrapper;
			}
			
			
			// create a wrapper specifically for the API event to use
			BlockStateWrapper apiWrapper = new BlockStateWrapper(blockState, levelWrapper, null);
			DhApiBlockStateWrapperCreatedEvent.EventParam eventParam = new DhApiBlockStateWrapperCreatedEvent.EventParam(apiWrapper);
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBlockStateWrapperCreatedEvent.class, eventParam);
			
			if (!eventParam.getOverridesSet())
			{
				// no API changes needed, use the existing object
				#if MC_VER <= MC_1_7_10
				WRAPPER_BY_BLOCK_ID_AND_META.putIfAbsent(packedIdMeta, apiWrapper);
				#else
				WRAPPER_BY_BLOCK_STATE.putIfAbsent(blockState, apiWrapper);
				#endif
				return apiWrapper;
			}
			else
			{
				// create a new wrapper using whatever overrides the API user set
				BlockStateWrapper returnWrapper = new BlockStateWrapper(blockState, levelWrapper, eventParam);
				#if MC_VER <= MC_1_7_10
				WRAPPER_BY_BLOCK_ID_AND_META.putIfAbsent(packedIdMeta, returnWrapper);
				#else
				WRAPPER_BY_BLOCK_STATE.putIfAbsent(blockState, returnWrapper);
				#endif
				return returnWrapper;
			}
		}
	}

	#if MC_VER <= MC_1_12_2
	private BlockStateWrapper(@Nullable IBlockState blockState, ILevelWrapper levelWrapper, @Nullable DhApiBlockStateWrapperCreatedEvent.EventParam overrideEventParam)
	#else
	private BlockStateWrapper(@Nullable BlockState blockState, ILevelWrapper levelWrapper, @Nullable DhApiBlockStateWrapperCreatedEvent.EventParam overrideEventParam)
	#endif	
	{
		
		this.blockState = blockState;
		#if MC_VER <= MC_1_7_10
		// TODO store block/meta separately
		if (blockState != null && !isAir()) {
			this.wrappedMcObject = new Object[] {
				blockState.getBlock(),
				blockState.getMeta()
			};
		} else {
			this.wrappedMcObject = null;
		}
		#endif
		this.serialString = serialize(blockState, levelWrapper);
		this.hashCode = Objects.hash(this.serialString);
		String lowerCaseSerial = this.serialString.toLowerCase();
		
		
		
		// is liquid //
		{
			if (this.isAir()
				|| this.blockState == null) // == null isn't necessary since its handled in isAir() but is here to prevent intellij from complaining
			{
				this.isLiquid = false;
			}
			else
			{
				#if MC_VER <= MC_1_12_2
				this.isLiquid = this.blockState.getMaterial().isLiquid() || this.blockState.getBlock() instanceof IFluidBlock;
				#elif MC_VER < MC_1_20_1
				this.isLiquid = this.blockState.getMaterial().isLiquid() || !this.blockState.getFluidState().isEmpty();
				#else
				this.isLiquid = !this.blockState.getFluidState().isEmpty();
				#endif
			}
		}
		
		
		// API overriding //
		{
			if (overrideEventParam != null
				&& overrideEventParam.getBlockMaterial() != null)
			{
				this.blockMaterialId = overrideEventParam.getBlockMaterial().index;
			}
			else
			{
				// no API override, use the base logic
				this.blockMaterialId = calculateEDhApiBlockMaterialId(this.blockState, lowerCaseSerial, this.isLiquid).index;
			}
			
			// allow overriding if present 
			if (overrideEventParam != null
				&& overrideEventParam.getOpacity() != null)
			{
				this.opacity = overrideEventParam.getOpacity();
			}
			else
			{
				this.opacity = calculateOpacity(this.blockState, isAir(this.blockState), this.isLiquid);
			}
			
			// allow overriding if present 
			if (overrideEventParam != null
				&& overrideEventParam.getAllowApiColorOverride() != null)
			{
				this.allowApiColorOverride = overrideEventParam.getAllowApiColorOverride();
			}
			else
			{
				this.allowApiColorOverride = false;
			}
			
			// allow overriding if present 
			if (overrideEventParam != null
				&& overrideEventParam.getAllowApiTextureOverride() != null)
			{
				this.allowApiTextureOverride = overrideEventParam.getAllowApiTextureOverride();
			}
			else
			{
				this.allowApiTextureOverride = false;
			}
		}
		
		
		// beacon handling //
		{
			
			// beacon base blocks
			#if MC_VER <= MC_1_18_2
			
			// Used to handle older MC versions that don't have an simple way of getting the block's tags
			List<String> oldBeaconBaseBlockNameList = Arrays.asList(
				"iron_block",
				"gold_block",
				"diamond_block",
				"emerald_block",
				"netherite_block"
			);
			
			// Older MC versions are harder to get block tags, so just use a static list to determine beacon blocks
			boolean isBeaconBaseBlock = false;
			for (int i = 0; i < oldBeaconBaseBlockNameList.size(); i++)
			{
				String baseBlockName = oldBeaconBaseBlockNameList.get(i);
				if (lowerCaseSerial.contains(baseBlockName))
				{
					isBeaconBaseBlock = true;
					break;
				}
			}
			this.isBeaconBaseBlock = isBeaconBaseBlock;
			#else
			if (blockState != null)
			{
				this.isBeaconBaseBlock = blockTagInCsv(blockState, "beacon_base_blocks");
			}
			else
			{
				this.isBeaconBaseBlock = false;
			}
			#endif
			
			// beacon block
			this.isBeaconBlock = lowerCaseSerial.contains("minecraft:beacon");
			
			
			// beacon tint color
			Color beaconTintColor = null;
			if (this.blockState != null
				// beacon blocks also show up here, but since they block the beacon beam we don't want their color		
				&& !this.isBeaconBlock)
			{
				int colorInt;
				#if MC_VER <= MC_1_7_10
				Block block = this.blockState.getBlock();
				if (block instanceof BlockStainedGlass 
					|| block instanceof BlockStainedGlassPane)
				{
					colorInt = ItemDye.dyeColors[BlockColored.func_150032_b(this.blockState.getMeta())];
					beaconTintColor = ColorUtil.toColorObjRGB(colorInt);
				}
				#elif MC_VER <= MC_1_12_2
				Block block = this.blockState.getBlock();
				if (block instanceof BlockStainedGlass)
				{
					float[] c = blockState.getValue(BlockStainedGlass.COLOR).getColorComponentValues();
					beaconTintColor = new Color(c[0], c[1], c[2]);
				}
				else if (block instanceof BlockStainedGlassPane)
				{
					float[] c = blockState.getValue(BlockStainedGlassPane.COLOR).getColorComponentValues();
					beaconTintColor = new Color(c[0], c[1], c[2]);
				}
				#else
				Block block = this.blockState.getBlock();
				if (block instanceof BeaconBeamBlock)
				{
					#if MC_VER <= MC_1_19_4
					colorInt = ((BeaconBeamBlock) block).getColor().getMaterialColor().col;
					#else
					colorInt = ((BeaconBeamBlock) block).getColor().getMapColor().col;
					#endif
					
					beaconTintColor = ColorUtil.toColorObjRGB(colorInt);
				}
				#endif
			}
			this.beaconTintColor = beaconTintColor;
			
			
			// allow/deny beacon beam passage 
			boolean allowsBeaconBeamPassage;
			if (this.blockState != null)
			{
				// get block properties (defaults to the values used by air)
				boolean canOcclude = getCanOcclude(this.blockState);
				boolean propagatesSkyLightDown = getPropagatesSkyLightDown(this.blockState);
				
				if (lowerCaseSerial.contains("minecraft:bedrock"))
				{
					// bedrock is a special case fully opaque block that does allow beacons through
					allowsBeaconBeamPassage = true;
				}
				else if (lowerCaseSerial.contains("minecraft:tinted_glass"))
				{
					// tinted glass is a special case where it isn't fully opaque,
					// but should block beacons
					allowsBeaconBeamPassage = false;
				}
				else if (propagatesSkyLightDown || !canOcclude)
				{
					// stairs, cake, fences, etc.
					allowsBeaconBeamPassage = true;
				}
				else
				{
					// non-opaque blocks (glass, mob spawners, etc.)
					// all allow beacons through
					allowsBeaconBeamPassage = (this.opacity != LodUtil.BLOCK_FULLY_OPAQUE);
				}
			}
			else
			{
				// air allows beacons through
				allowsBeaconBeamPassage = true;
			}
			this.allowsBeaconBeamPassage = allowsBeaconBeamPassage;
		}
		
		
		// texture handling //
		{
			// texture ignoring //
			{
				String dontTextureNamesCsv = Config.Client.Advanced.Graphics.Texture.blocksDontRenderTextureCsv.get();
				this.renderTexture = !blockSerialInCsv(lowerCaseSerial, dontTextureNamesCsv);
			}
			
			
			// side texture ignoring //
			{
				String sideBlockNamesCsv = Config.Client.Advanced.Graphics.Texture.blocksDontUseSideTextureCsv.get();
				boolean isSideIgnoreBlock = blockSerialInCsv(lowerCaseSerial, sideBlockNamesCsv);
				
				String dontUseSideTextureTagsCsv = Config.Client.Advanced.Graphics.Texture.blockTagsDontUseSideTextureCsv.get();
				boolean hasSideIgnoreTags = blockTagInCsv(blockState, dontUseSideTextureTagsCsv);
				
				this.useBottomTextureForSides = hasSideIgnoreTags || isSideIgnoreBlock;
			}
			
			
			// always raster texture //
			{
				String alwaysRasterNamesCsv = Config.Client.Advanced.Graphics.Texture.blocksAlwaysRasterizeTextureCsv.get();
				this.alwaysRasterizeTexture = blockSerialInCsv(lowerCaseSerial, alwaysRasterNamesCsv);
			}
		}
		
		
		// map color //
		{
			if (this.blockState != null)
			{
				int mcColor = 0;
				
				#if MC_VER <= MC_1_12_2
				mcColor = this.blockState.getMaterial().getMaterialMapColor().colorValue;
				#elif MC_VER < MC_1_20_1
				mcColor = this.blockState.getMaterial().getColor().col;
		        #else
				mcColor = this.blockState.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
                #endif
				
				this.mapColor = ColorUtil.toColorObjRGB(mcColor);
			}
			else
			{
				this.mapColor = new Color(0, 0, 0, 0);
			}
		}
		
		
		// is solid //
		{
			if (this.isAir()
				|| this.blockState == null) // "== null" isn't necessary since its handled in isAir() but is here to prevent IntelliJ from complaining
			{
				this.isSolid = false;
			}
			else
			{
	        #if MC_VER < MC_1_20_1
			this.isSolid = this.blockState.getMaterial().isSolid();
	        #else
			this.isSolid = !this.blockState.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
            #endif
			}
		}
		
		
		// ice //
		{
			// ice block
			this.isIceBlock = lowerCaseSerial.contains("minecraft:ice");
			
		}
		
	}

	// static constructor helpers //
	//region
	
	#if MC_VER <= MC_1_12_2
	private static EDhApiBlockMaterial calculateEDhApiBlockMaterialId(@Nullable IBlockState blockState, String lowercaseSerialString, boolean isLiquid)
	#else
	private static EDhApiBlockMaterial calculateEDhApiBlockMaterialId(@Nullable BlockState blockState, String lowercaseSerialString, boolean isLiquid)
	#endif
	{
		if (isAir(blockState))
		{
			return EDhApiBlockMaterial.AIR;
		}
		
		
		
		//========//
		// leaves //
		//========//
		//region
		
		boolean isLeafBlock;
		#if MC_VER <= MC_1_7_10
		isLeafBlock = blockState.getBlock() instanceof BlockLeavesBase;
		#elif MC_VER <= MC_1_12_2
		isLeafBlock = blockState.getBlock() instanceof BlockLeaves;
		#else 
		isLeafBlock = blockState.is(BlockTags.LEAVES);
		#endif
		if (isLeafBlock
			|| lowercaseSerialString.contains("bamboo")
			|| lowercaseSerialString.contains("cactus")
			|| lowercaseSerialString.contains("chorus_flower")
			|| lowercaseSerialString.contains("mushroom")
			)
		{
			return EDhApiBlockMaterial.LEAVES;
		}
		
		//endregion
		
		
		
		//======//
		// lava //
		//======//
		//region
		
		boolean isLavaBlock;
		#if MC_VER <= MC_1_7_10
		isLavaBlock = blockState.getBlock() == Blocks.lava 
			|| blockState.getBlock() == Blocks.flowing_lava;
		#elif MC_VER <= MC_1_12_2
		isLavaBlock = blockState.getBlock() == Blocks.LAVA 
			|| blockState.getBlock() == Blocks.FLOWING_LAVA;
		#else
		isLavaBlock = blockState.is(Blocks.LAVA);
		#endif
		if (isLavaBlock)
		{
			return EDhApiBlockMaterial.LAVA;
		}
		
		//endregion
		
		
		
		//=======//
		// water //
		//=======//
		//region
		
		boolean isWaterBlock;
	    #if MC_VER <= MC_1_7_10
		isWaterBlock = blockState.getBlock() == Blocks.water 
			|| blockState.getBlock() == Blocks.flowing_water;
	    #elif MC_VER <= MC_1_12_2
		isWaterBlock = blockState.getBlock() == Blocks.WATER 
			|| blockState.getBlock() == Blocks.FLOWING_WATER;
		#else
		isWaterBlock = blockState.is(Blocks.WATER);
		#endif
		if (isLiquid
			|| isWaterBlock)
		{
			return EDhApiBlockMaterial.WATER;
		}
		
		//endregion
		
		
		
		//======//
		// wood //
		//======//
		//region
		
		boolean isWoodSoundingBlock;
		#if MC_VER <= MC_1_7_10
		isWoodSoundingBlock = blockState.getBlock().stepSound == Block.soundTypeWood;
		#elif MC_VER <= MC_1_12_2
		isWoodSoundingBlock = blockState.getBlock().getSoundType() == SoundType.WOOD;
		#else 
		isWoodSoundingBlock = blockState.getSoundType() == SoundType.WOOD;
		#endif
		
		boolean isCherryWood;
		#if MC_VER <= MC_1_19_2
		isCherryWood = false;
		#else
		isCherryWood = blockState.getSoundType() == SoundType.CHERRY_WOOD;
		#endif
		
		if (isWoodSoundingBlock
			|| lowercaseSerialString.contains("root")
			|| isCherryWood
			)
		{
			return EDhApiBlockMaterial.WOOD;
		}
		
		//endregion
		
		
		
		//=======//
		// metal //
		//=======//
		//region
		
		boolean isMetalSoundingBlock;
		#if MC_VER <= MC_1_7_10
		isMetalSoundingBlock = blockState.getBlock().stepSound == Block.soundTypeMetal;
		#elif MC_VER <= MC_1_12_2
		isMetalSoundingBlock = blockState.getBlock().getSoundType() == SoundType.METAL;
		#else
		isMetalSoundingBlock = blockState.getSoundType() == SoundType.METAL;
		#endif
		
		boolean isCopperSounding;
		#if MC_VER <= MC_1_18_2
		isCopperSounding = false;
		#elif MC_VER <= MC_1_20_2
		isCopperSounding = blockState.getSoundType() == SoundType.COPPER;
		#else
		isCopperSounding
		    = blockState.getSoundType() == SoundType.COPPER
				|| blockState.getSoundType() == SoundType.COPPER_BULB
				|| blockState.getSoundType() == SoundType.COPPER_GRATE;
		#endif
		
		if (isMetalSoundingBlock
			|| isCopperSounding)
		{
			return EDhApiBlockMaterial.METAL;
		}
		
		//endregion
		
		
		
		//=======//
		// grass //
		//=======//
		//region
		
		boolean isGrassBlock;
		#if MC_VER <= MC_1_7_10
		isGrassBlock = blockState.getBlock() instanceof BlockGrass;
		#else
		isGrassBlock = lowercaseSerialString.contains("grass_block");
		#endif
		
		if (isGrassBlock
			|| lowercaseSerialString.contains("grass_slab")
			)
		{
			return EDhApiBlockMaterial.GRASS;
		}
		
		//endregion
		
		
		
		//======//
		// dirt //
		//======//
		//region
		
		if (
			lowercaseSerialString.contains("dirt")
			|| lowercaseSerialString.contains("gravel")
			|| lowercaseSerialString.contains("mud")
			|| lowercaseSerialString.contains("podzol")
			|| lowercaseSerialString.contains("mycelium")
			)
		{
			return EDhApiBlockMaterial.DIRT;
		}
		
		//endregion
		
		
		
		//===========//
		// deepslate //
		//===========//
		//region
		
		#if MC_VER >= MC_1_17_1
		if (blockState.getSoundType() == SoundType.DEEPSLATE
			|| blockState.getSoundType() == SoundType.DEEPSLATE_BRICKS
			|| blockState.getSoundType() == SoundType.DEEPSLATE_TILES
			|| blockState.getSoundType() == SoundType.POLISHED_DEEPSLATE
			|| lowercaseSerialString.contains("deepslate") )
		{
			return EDhApiBlockMaterial.DEEPSLATE;
		} 
		#endif
		
		//endregion
		
		
		
		//============//
		// netherrack //
		//============//
		//region
		
		boolean isNetherRack;
		#if MC_VER <= MC_1_7_10
		isNetherRack = blockState.getBlock() == Blocks.netherrack 
			|| blockState.getBlock() == Blocks.nether_brick;
		#elif MC_VER <= MC_1_12_2
		isNetherRack = blockState.getBlock() == Blocks.NETHERRACK;
		#else
		isNetherRack = blockState.is(BlockTags.BASE_STONE_NETHER);
		#endif
		
		if (isNetherRack)
		{
			return EDhApiBlockMaterial.NETHER_STONE;
		}
		
		//endregion
		
		
		
		//=============//
		// misc/simple //
		//=============//
		//region
		
		if (lowercaseSerialString.contains("snow"))
		{
			return EDhApiBlockMaterial.SNOW;
		}
		
		if (lowercaseSerialString.contains("sand"))
		{
			return EDhApiBlockMaterial.SAND;
		}
		
		if (lowercaseSerialString.contains("terracotta"))
		{
			return EDhApiBlockMaterial.TERRACOTTA;
		}
		
		if (lowercaseSerialString.contains("stone")
			|| lowercaseSerialString.contains("ore"))
		{
			return EDhApiBlockMaterial.STONE;
		}
		
		if (getLightEmission(blockState) > 0)
		{
			return EDhApiBlockMaterial.ILLUMINATED;
		}
		
		//endregion
		
		
		
		return EDhApiBlockMaterial.UNKNOWN;
	}
	
	#if MC_VER <= MC_1_12_2
	private static int calculateOpacity(@Nullable IBlockState blockState, boolean isAir, boolean isLiquid)
	#else
	private static int calculateOpacity(@Nullable BlockState blockState, boolean isAir, boolean isLiquid)
	#endif
	{
		// get block properties (defaults to the values used by air)
		boolean canOcclude = getCanOcclude(blockState);
		boolean propagatesSkyLightDown = getPropagatesSkyLightDown(blockState);
		
		
		
		// this method isn't perfect, but works well enough for our use case
		int opacity;
		if (isAir)
		{
			opacity = LodUtil.BLOCK_FULLY_TRANSPARENT;
		}
		else if (isLiquid && !canOcclude)
		{
			// probably not a waterlogged block (which should block light entirely)
			
			// +1 to indicate that the block is translucent (in between transparent and opaque) 
			opacity = LodUtil.BLOCK_FULLY_TRANSPARENT + 1;
		}
		else if (propagatesSkyLightDown && !canOcclude)
		{
			// probably glass or some other fully transparent block
			
			// !canOcclude is required to ignore stairs and slabs since
			// propagateSkyLightDown is true for them, but they're solid and don't actually let light through
			
			opacity = LodUtil.BLOCK_FULLY_TRANSPARENT;
		}
		else
		{
			// default for all other blocks
			opacity = LodUtil.BLOCK_FULLY_OPAQUE;
		}
		
		
		return opacity;
	}
	
	#if MC_VER <= MC_1_12_2
	private static boolean getCanOcclude(@Nullable IBlockState blockState)
	#else
	private static boolean getCanOcclude(@Nullable BlockState blockState)
	#endif
	{
		// defaults to the value used by air
		boolean canOcclude = false;
		if (blockState != null)
		{
			#if MC_VER <= MC_1_12_2
			canOcclude = blockState.getMaterial().isSolid();
			#else
			canOcclude = blockState.canOcclude();
			#endif
		}
		
		return canOcclude;
	}
	
	#if MC_VER <= MC_1_12_2
	private static boolean getPropagatesSkyLightDown(@Nullable IBlockState blockState)
	#else
	private static boolean getPropagatesSkyLightDown(@Nullable BlockState blockState)
	#endif
	{
		// defaults to the value used by air
		boolean propagatesSkyLightDown = true;
		if (blockState != null)
		{
			#if MC_VER <= MC_1_7_10
			propagatesSkyLightDown = blockState.getBlock().getLightOpacity() == 0;
			#elif MC_VER <= MC_1_12_2
			propagatesSkyLightDown = blockState.getBlock().getLightOpacity(blockState) == 0;
			#elif MC_VER < MC_1_21_3
			propagatesSkyLightDown = blockState.propagatesSkylightDown(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			#else
			propagatesSkyLightDown = blockState.propagatesSkylightDown();
			#endif
		}
		
		return propagatesSkyLightDown;
	}
	
	#if MC_VER <= MC_1_12_2
	private static boolean blockTagInCsv(@Nullable IBlockState blockState, String blockTagsCsv)
	#else
	private static boolean blockTagInCsv(@Nullable BlockState blockState, String blockTagsCsv)
	#endif
	{
		// should only trigger for air
		if (blockState == null)
		{
			return false;
		}
		
		
		
		#if MC_VER <= MC_1_18_2
		// tags aren't present in MC 1.17 and older
		return false;
		#else
		
		Stream<TagKey<Block>> tags;
		#if MC_VER <= MC_1_21_11
		tags = blockState.getTags();
		#else
		tags = blockState.tags();
		#endif
		
		
		blockTagsCsv = blockTagsCsv.toLowerCase(); // lowercase to allow for case-insensitive checking
		List<String> sideBlockTagList = Arrays.asList(blockTagsCsv.split(",")); // duplicates could happen, but that isn't a problem since we'd just end up checking the same block twice, not a big deal
		
		
		boolean tagMatches = tags.anyMatch((TagKey<Block> tag) ->
		{
			String lowerTag = tag.location().getPath().toLowerCase();
			
			for (int i = 0; i < sideBlockTagList.size(); i++)
			{
				String sideBlockTag = sideBlockTagList.get(i);
				if (lowerTag.contains(sideBlockTag))
				{
					return true;
				}
			}
			
			return false;
		});
		
		return tagMatches;
		#endif
	}
	
	private static boolean blockSerialInCsv(String lowerCaseSerial, String blockNameCsv)
	{
		boolean blockMatches = false;
		
		// get block resource names
		blockNameCsv = blockNameCsv.toLowerCase(); // lowercase to allow for case-insensitive checking
		List<String> blockNameList = Arrays.asList(blockNameCsv.split(",")); // duplicates could happen, but that isn't a problem since we'd just end up checking the same block twice, not a big deal
		
		// check this block against the expected list
		for (int i = 0; i < blockNameList.size(); i++)
		{
			String baseBlockName = blockNameList.get(i);
			if (baseBlockName.trim().isEmpty())
			{
				continue;
			}
			
			if (lowerCaseSerial.contains(baseBlockName))
			{
				blockMatches = true;
				break;
			}
		}
		
		return blockMatches;
	}
	
	//endregion
	//endregion
	
	
	
	//====================//
	// LodBuilder methods //
	//====================//
	//region
	
	/**
	 * Each of the following methods require
	 * a {@link ILevelWrapper} since {@link BlockStateWrapper#deserialize(String,ILevelWrapper)} also requires one. 
	 * This way the method won't accidentally be called before the deserialization can be completed.
	 */
	
	public static ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredBlocks(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (rendererIgnoredBlocks != null)
		{
			return rendererIgnoredBlocks;
		}
		
		ObjectOpenHashSet<String> baseIgnoredBlock = new ObjectOpenHashSet<>();
		baseIgnoredBlock.add(AIR_STRING);
		rendererIgnoredBlocks = getAllBlockWrappers(Config.Client.Advanced.Graphics.Culling.ignoredRenderBlockCsv, baseIgnoredBlock, levelWrapper);
		return rendererIgnoredBlocks;
	}
	public static ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredCaveBlocks(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (rendererIgnoredCaveBlocks != null)
		{
			return rendererIgnoredCaveBlocks;
		}
		
		ObjectOpenHashSet<String> baseIgnoredBlock = new ObjectOpenHashSet<>();
		baseIgnoredBlock.add(AIR_STRING);
		rendererIgnoredCaveBlocks = getAllBlockWrappers(Config.Client.Advanced.Graphics.Culling.ignoredRenderCaveBlockCsv, baseIgnoredBlock, levelWrapper);
		return rendererIgnoredCaveBlocks;
	}
	public static ObjectOpenHashSet<IBlockStateWrapper> getWaterSurfaceReplacementBlocks(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (waterSurfaceReplacementBlocks != null)
		{
			return waterSurfaceReplacementBlocks;
		}
		
		ObjectOpenHashSet<String> baseIgnoredBlockResourceSet = new ObjectOpenHashSet<>();
		waterSurfaceReplacementBlocks = getAllBlockWrappers(Config.Client.Advanced.Graphics.Culling.waterSurfaceBlockReplacementCsv, baseIgnoredBlockResourceSet, levelWrapper);
		waterSurfaceReplacementBlocks.remove(AIR);
		
		return waterSurfaceReplacementBlocks;
	}
	public static ObjectOpenHashSet<IBlockStateWrapper> getWaterSubsurfaceReplacementBlocks(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (waterSubsurfaceReplacementBlocks != null)
		{
			return waterSubsurfaceReplacementBlocks;
		}
		
		ObjectOpenHashSet<String> baseIgnoredBlockResourceSet = new ObjectOpenHashSet<>();
		waterSubsurfaceReplacementBlocks = getAllBlockWrappers(Config.Client.Advanced.Graphics.Culling.waterSubSurfaceBlockReplacementCsv, baseIgnoredBlockResourceSet, levelWrapper);
		// air will be present if any invalid resource locations are present
		// but we don't want to replace air with water, that'll cause monoliths
		waterSubsurfaceReplacementBlocks.remove(AIR);
		
		return waterSubsurfaceReplacementBlocks;
	}
	
	//endregion
	
	
	
	//======================//
	// static block getters //
	//======================//
	//region
	
	public static BlockStateWrapper getWaterBlockStateWrapper(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (waterBlock != null)
		{
			return waterBlock;
		}
		
		waterBlock = BlockStateWrapper.deserializeOrDefault(WATER_RESOURCE_LOCATION_STRING, levelWrapper);
		if (waterBlock.equals(AIR))
		{
			LOGGER.warn("Failed to deserialize ["+WATER_RESOURCE_LOCATION_STRING+"], issues may occur.");
		}
		
		return waterBlock;
	}
	
	public static BlockStateWrapper getDirtBlockStateWrapper(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (dirtBlock != null)
		{
			return dirtBlock;
		}
		
		dirtBlock = BlockStateWrapper.deserializeOrDefault(DIRT_RESOURCE_LOCATION_STRING, levelWrapper);
		if (dirtBlock.equals(AIR))
		{
			LOGGER.warn("Failed to deserialize ["+DIRT_RESOURCE_LOCATION_STRING+"], issues may occur.");
		}
		
		return dirtBlock;
	}
	
	public static BlockStateWrapper getIceBlockStateWrapper(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (iceBlock != null)
		{
			return iceBlock;
		}
		
		iceBlock = BlockStateWrapper.deserializeOrDefault(ICE_RESOURCE_LOCATION_STRING, levelWrapper);
		if (iceBlock.equals(AIR))
		{
			LOGGER.warn("Failed to deserialize ["+ICE_RESOURCE_LOCATION_STRING+"], issues may occur.");
		}
		
		return iceBlock;
	}
	
	public static BlockStateWrapper getSnowBlockStateWrapper(ILevelWrapper levelWrapper)
	{
		// use the cached version if possible
		if (snowBlock != null)
		{
			return snowBlock;
		}
		
		snowBlock = BlockStateWrapper.deserializeOrDefault(SNOW_RESOURCE_LOCATION_STRING, levelWrapper);
		if (snowBlock.equals(AIR))
		{
			LOGGER.warn("Failed to deserialize ["+SNOW_RESOURCE_LOCATION_STRING+"], issues may occur.");
		}
		
		return snowBlock;
	}
	
	//endregion
	
	
	
	//=====================//
	// lod builder helpers //
	//=====================//
	//region
	
	private static ObjectOpenHashSet<IBlockStateWrapper> getAllBlockWrappers(ConfigEntry<String> config, ObjectOpenHashSet<String> baseResourceLocations, ILevelWrapper levelWrapper)
	{
		// get the base blocks 
		ObjectOpenHashSet<String> blockStringList = new ObjectOpenHashSet<>();
		if (baseResourceLocations != null)
		{
			blockStringList.addAll(baseResourceLocations);	
		}
		
		// get the config blocks
		String ignoreBlockCsv = config.get();
		if (ignoreBlockCsv != null)
		{
			blockStringList.addAll(Arrays.asList(ignoreBlockCsv.split(",")));
		}
		
		return getAllBlockWrappers(blockStringList, levelWrapper);
	}
	private static ObjectOpenHashSet<IBlockStateWrapper> getAllBlockWrappers(ObjectOpenHashSet<String> blockResourceLocationSet, ILevelWrapper levelWrapper)
	{
		// deserialize each of the given resource locations
		ObjectOpenHashSet<IBlockStateWrapper> blockStateWrappers = new ObjectOpenHashSet<>();
		for (String blockResourceLocation : blockResourceLocationSet)
		{
			try
			{
				if (blockResourceLocation == null)
				{
					// shouldn't happen, but just in case
					continue;
				}
				String cleanedResourceLocation = blockResourceLocation.trim();
				if (cleanedResourceLocation.length() == 0)
				{
					continue;
				}
				
				
				BlockStateWrapper defaultBlockStateToIgnore = (BlockStateWrapper) deserialize(cleanedResourceLocation, levelWrapper);
				blockStateWrappers.add(defaultBlockStateToIgnore);
				
				if (defaultBlockStateToIgnore != AIR)
				{
					#if MC_VER <= MC_1_7_10
					BlockStateWrapper newBlockToIgnore = BlockStateWrapper.fromBlockState(defaultBlockStateToIgnore.blockState, levelWrapper);
					blockStateWrappers.add(newBlockToIgnore);
					#else
					// add all possible blockstates (to account for light blocks with different light values and such)
					#if MC_VER <= MC_1_12_2
					List<IBlockState> blockStatesToIgnore = defaultBlockStateToIgnore.blockState.getBlock().getBlockState().getValidStates();
					#else
					List<BlockState> blockStatesToIgnore = defaultBlockStateToIgnore.blockState.getBlock().getStateDefinition().getPossibleStates();
					#endif

					#if MC_VER <= MC_1_12_2
					for (IBlockState blockState : blockStatesToIgnore)
					#else
					for (BlockState blockState : blockStatesToIgnore)
					#endif
					{
						BlockStateWrapper newBlockToIgnore = fromBlockState(blockState, levelWrapper);
						blockStateWrappers.add(newBlockToIgnore);
					}
					#endif
				}
				else
				{
					// air is a special case so it must be handled separately
					blockStateWrappers.add(AIR);
				}
			}
			catch (IOException e)
			{
				LOGGER.warn("Unable to deserialize block with the resource location: ["+blockResourceLocation+"]. Error: "+e.getMessage(), e);
			}
			catch (Exception e)
			{
				LOGGER.warn("Unexpected error deserializing block with the resource location: ["+blockResourceLocation+"]. Error: "+e.getMessage(), e);
			}
		}
		
		return blockStateWrappers;
	}
	
	public static void clearCachedIgnoreBlocks()
	{
		rendererIgnoredBlocks = null;
		rendererIgnoredCaveBlocks = null;
		waterSurfaceReplacementBlocks = null;
		waterSubsurfaceReplacementBlocks = null;
		
		waterBlock = null;
		dirtBlock = null;
		iceBlock = null;
		snowBlock = null;
	}
	
	//endregion
	
	
	
	//=================//
	// wrapper methods //
	//=================//
	//region
	
	@Override
	public int getOpacity() { return this.opacity; }
	
	@Override
	public int getLightEmission() { return getLightEmission(this.blockState); }
	#if MC_VER <= MC_1_12_2
	public static int getLightEmission(IBlockState blockState)
	#else
	public static int getLightEmission(BlockState blockState)
	#endif
	{
		if (blockState == null)
		{
			return 0;
		}
		
		#if MC_VER <= MC_1_12_2 
		return blockState.getLightValue();
		#else
		return blockState.getLightEmission();
		#endif
	}
	
	
	@Override
	public String getSerialString() { return this.serialString; }
	
	@Override
	public Object getWrappedMcObject() 
	{
		#if MC_VER <= MC_1_7_10
		return this.wrappedMcObject;
		#else
		return this.blockState;
		#endif
	}
	
	@Override
	public boolean isAir() { return isAir(this.blockState); }
	#if MC_VER <= MC_1_12_2
	public static boolean isAir(IBlockState blockState) 
	#else
	public static boolean isAir(BlockState blockState) 
	#endif
	{
		if (blockState == null)
		{
			return true;
		}
		
		#if MC_VER <= MC_1_7_10
		return blockState.getBlock() == Blocks.air;
		#elif MC_VER <= MC_1_12_2
		return blockState.getBlock() == Blocks.AIR;
		#else
		return blockState.isAir();
		#endif
	}
	
	@Override public boolean isSolid() { return this.isSolid; }
	@Override public boolean isLiquid() { return this.isLiquid; }
	@Override public boolean isBeaconBlock() { return this.isBeaconBlock; }
	@Override public boolean isBeaconBaseBlock() { return this.isBeaconBaseBlock; }
	@Override public boolean isIceBlock() { return this.isIceBlock; }
	@Override public boolean isBeaconTintBlock() { return this.beaconTintColor != null; }
	@Override public boolean allowsBeaconBeamPassage() { return this.allowsBeaconBeamPassage; }
	@Override public boolean allowApiColorOverride() { return this.allowApiColorOverride; }
	@Override public boolean allowApiTextureOverride() { return this.allowApiTextureOverride; }
	@Override public boolean renderTexture() { return this.renderTexture; }
	@Override public boolean useBottomTextureForSides() { return this.useBottomTextureForSides; }
	@Override public boolean alwaysRasterizeTexture() { return this.alwaysRasterizeTexture; }
	
	@Override public Color getMapColor() { return this.mapColor; }
	@Override public Color getBeaconTintColor() { return this.beaconTintColor; }
	
	@Override public byte getMaterialId() { return this.blockMaterialId; }
	
	//endregion
	
	
	
	//=======================//
	// serialization methods //
	//=======================//
	//region
	
	#if MC_VER <= MC_1_12_2
	private static String serialize(IBlockState blockState, ILevelWrapper levelWrapper)
	#else
	private static String serialize(BlockState blockState, ILevelWrapper levelWrapper)
	#endif
	{
		if (blockState == null)
		{
			return AIR_STRING;
		}
		
		#if MC_VER <= MC_1_7_10
		String serialString = GameData.getBlockRegistry().getNameForObject(blockState.getBlock());
		if (blockState.getMeta() != 0)
		{
			serialString += RESOURCE_LOCATION_SEPARATOR + blockState.getMeta();
		}
		return serialString;
		#else
		
		
		// older versions of MC have a static registry
		#if MC_VER <= MC_1_16_5
		#else
		Level level = (Level)levelWrapper.getWrappedMcObject();
		net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
		#endif
		
		#if MC_VER <= MC_1_21_10
		ResourceLocation resourceLocation;
		#else
		Identifier resourceLocation;
		#endif
		
		#if MC_VER <= MC_1_12_2
		resourceLocation = blockState.getBlock().getRegistryName();
		#elif MC_VER <= MC_1_17_1
		resourceLocation = Registry.BLOCK.getKey(blockState.getBlock());
		#elif MC_VER <= MC_1_19_2
		resourceLocation = registryAccess.registryOrThrow(Registry.BLOCK_REGISTRY).getKey(blockState.getBlock());
		#elif MC_VER <= MC_1_21_1
		resourceLocation = registryAccess.registryOrThrow(Registries.BLOCK).getKey(blockState.getBlock());
		#else
		resourceLocation = registryAccess.lookupOrThrow(Registries.BLOCK).getKey(blockState.getBlock());
		#endif
		
		
		
		if (resourceLocation == null)
		{
			LOGGER.warn("No ResourceLocation found, unable to serialize: " + blockState);
			return AIR_STRING;
		}
		
		String serialString = resourceLocation.getNamespace() + RESOURCE_LOCATION_SEPARATOR + resourceLocation.getPath()
				+ STATE_STRING_SEPARATOR + serializeBlockStateProperties(blockState);
		return serialString;
		#endif
	}
	
	
	/** returns AIR if the given string can't be deserialized */
	public static BlockStateWrapper deserializeOrDefault(String resourceStateString, ILevelWrapper levelWrapper)
	{
		try
		{
			return deserialize(resourceStateString, levelWrapper);
		}
		catch (IOException e)
		{
			return BlockStateWrapper.AIR;
		}
	}
	
	/** will only work if a level is currently loaded */
	public static BlockStateWrapper deserialize(String resourceStateString, ILevelWrapper levelWrapper) throws IOException
	{
		// we need the final string for the concurrent hash map later
		final String finalResourceStateString = resourceStateString;
		
		if (finalResourceStateString.equals(AIR_STRING) 
			|| finalResourceStateString.equals("")) // the empty string shouldn't normally happen, but just in case
		{
			return AIR;
		}
		
		// attempt to use the existing wrapper
		if (WRAPPER_BY_RESOURCE_LOCATION.containsKey(finalResourceStateString))
		{
			return WRAPPER_BY_RESOURCE_LOCATION.get(finalResourceStateString);
		}
		
		
		
		// if no wrapper is found, default to air
		BlockStateWrapper foundWrapper = AIR;
		try
		{
			#if MC_VER <= MC_1_7_10
			String metaString = null; // will be null if no meta was included
			int stateSeparatorIndex = resourceStateString.indexOf(STATE_STRING_SEPARATOR);
			if (stateSeparatorIndex != -1)
			{
				metaString = resourceStateString.substring(stateSeparatorIndex + STATE_STRING_SEPARATOR.length());
				resourceStateString = resourceStateString.substring(0, stateSeparatorIndex);
			}
			
			int separatorOne = resourceStateString.indexOf(RESOURCE_LOCATION_SEPARATOR);
			if (separatorOne != -1)
			{
				stateSeparatorIndex = resourceStateString.indexOf(RESOURCE_LOCATION_SEPARATOR, separatorOne + 1);
				if (stateSeparatorIndex != -1)
				{
					metaString = resourceStateString.substring(stateSeparatorIndex + 1);
					resourceStateString = resourceStateString.substring(0, stateSeparatorIndex);
				}
			}
			
			try
			{
				Block block = GameData.getBlockRegistry().getObject(resourceStateString);
				int meta = 0;
				if (metaString != null)
				{
					meta = Integer.parseInt(metaString);
				}
				
				foundWrapper = fromBlockState(block, meta, levelWrapper);
				return foundWrapper;
			}
			catch (Exception e)
			{
				throw new IOException("Failed to deserialize the string [" + finalResourceStateString + "] into a BlockStateWrapper: " + e.getMessage(), e);
			}
			
			#else
			
			// try to parse out the BlockState
			String blockStatePropertiesString = null; // will be null if no properties were included
			int stateSeparatorIndex = resourceStateString.indexOf(STATE_STRING_SEPARATOR);
			if (stateSeparatorIndex != -1)
			{
				// blockstate properties found
				blockStatePropertiesString = resourceStateString.substring(stateSeparatorIndex + STATE_STRING_SEPARATOR.length());
				resourceStateString = resourceStateString.substring(0, stateSeparatorIndex);
			}
			
			// parse the resource location
			int separatorIndex = resourceStateString.indexOf(RESOURCE_LOCATION_SEPARATOR);
			if (separatorIndex == -1)
			{
				throw new IOException("Unable to parse Resource Location out of string: [" + resourceStateString + "].");
			}
			
			#if MC_VER < MC_1_21_11
			ResourceLocation resourceLocation;
			#else
			Identifier resourceLocation;
			#endif
			
			try
			{
				#if MC_VER < MC_1_21_1
				resourceLocation = new ResourceLocation(resourceStateString.substring(0, separatorIndex), resourceStateString.substring(separatorIndex + 1));
				#elif MC_VER <= MC_1_21_10
				resourceLocation = ResourceLocation.fromNamespaceAndPath(resourceStateString.substring(0, separatorIndex), resourceStateString.substring(separatorIndex + 1));
				#else
				resourceLocation = Identifier.fromNamespaceAndPath(resourceStateString.substring(0, separatorIndex), resourceStateString.substring(separatorIndex + 1));
				#endif
			}
			catch (Exception e)
			{
				throw new IOException("No Resource Location found for the string: [" + resourceStateString + "] Error: [" + e.getMessage() + "].");
			}
			
			
			
			// attempt to get the BlockState from all possible BlockStates
			try
			{
				
				#if MC_VER <= MC_1_16_5
				#else
				LodUtil.assertTrue(levelWrapper != null && levelWrapper.getWrappedMcObject() != null);
				Level level = (Level)levelWrapper.getWrappedMcObject();
				#endif
				
				Block block;
				#if MC_VER <= MC_1_12_2
				block = Block.REGISTRY.getObject(resourceLocation);
				#elif MC_VER <= MC_1_17_1
				block = Registry.BLOCK.get(resourceLocation);
				#elif MC_VER <= MC_1_19_2
				net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
				block = registryAccess.registryOrThrow(Registry.BLOCK_REGISTRY).get(resourceLocation);
				#elif MC_VER <= MC_1_21_1
				net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
				block = registryAccess.registryOrThrow(Registries.BLOCK).get(resourceLocation);
				#else
				net.minecraft.core.RegistryAccess registryAccess = level.registryAccess();
				Optional<Holder.Reference<Block>> optionalBlockHolder = registryAccess.lookupOrThrow(Registries.BLOCK).get(resourceLocation);
				block = optionalBlockHolder.isPresent() ? optionalBlockHolder.get().value() : null;
				#endif
				
				
				if (block == null)
				{
					// shouldn't normally happen, but here to make the compiler happy
					if (!BROKEN_RESOURCE_LOCATIONS.contains(resourceLocation))
					{
						BROKEN_RESOURCE_LOCATIONS.add(resourceLocation);
						LOGGER.warn("Unable to find BlockState with the resourceLocation [" + resourceLocation + "] and properties: [" + blockStatePropertiesString + "]. Air will be used instead, some data may be lost.");
					}
					
					return AIR;
				}
				
				
				// attempt to find the blockstate from all possibilities
				#if MC_VER <= MC_1_12_2
				IBlockState foundState = null;
				#else
				BlockState foundState = null;
				#endif
				if (blockStatePropertiesString != null)
				{
					#if MC_VER <= MC_1_12_2
					List<IBlockState> possibleStateList = block.getBlockState().getValidStates();
					#else
					List<BlockState> possibleStateList = block.getStateDefinition().getPossibleStates();
					#endif
					
					#if MC_VER <= MC_1_12_2
					for (IBlockState possibleState : possibleStateList)
					#else
					for (BlockState possibleState : possibleStateList)
					#endif
					{
						String possibleStatePropertiesString = serializeBlockStateProperties(possibleState);
						
						// ignoring case is important since the plugin may send properties
						// that don't match with what MC gives us (Bukkit returns all lowercase)
						if (possibleStatePropertiesString.equalsIgnoreCase(blockStatePropertiesString))
						{
							foundState = possibleState;
							break;
						}
					}
				}
				
				// use the default if no state was found or given
				if (foundState == null)
				{
					if (blockStatePropertiesString != null)
					{
						// we should have found a blockstate, but didn't
						if (!BROKEN_RESOURCE_LOCATIONS.contains(resourceLocation))
						{
							BROKEN_RESOURCE_LOCATIONS.add(resourceLocation);
							LOGGER.warn("Unable to find BlockState for Block [" + resourceLocation + "] with properties: [" + blockStatePropertiesString + "]. Using the default block state.");
						}
					}
					
					
					#if MC_VER <= MC_1_12_2
					foundState = block.getDefaultState(); 
					#else 
					foundState = block.defaultBlockState(); 
					#endif
				}
				
				foundWrapper = fromBlockState(foundState, levelWrapper);
				return foundWrapper;
			}
			catch (Exception e)
			{
				throw new IOException("Failed to deserialize the string [" + finalResourceStateString + "] into a BlockStateWrapper: " + e.getMessage(), e);
			}
			#endif
		}
		finally
		{
			// put if absent in case two threads deserialize at the same time
			// unfortunately we can't put everything in a computeIfAbsent() since we also throw exceptions
			WRAPPER_BY_RESOURCE_LOCATION.putIfAbsent(finalResourceStateString, foundWrapper);
			
			if (foundWrapper != AIR)
			{
				#if MC_VER <= MC_1_7_10
				
				int blockIdAndMeta = 0;
				// should always be true (the only exception should be air), 
				// but just in case
				if (foundWrapper.blockState instanceof FakeBlockState)
				{
					blockIdAndMeta = ((FakeBlockState)foundWrapper.blockState).getIdAndMeta();
				}
				
				WRAPPER_BY_BLOCK_ID_AND_META.putIfAbsent(blockIdAndMeta, foundWrapper);
				#else
				WRAPPER_BY_BLOCK_STATE.putIfAbsent(foundWrapper.blockState, foundWrapper);
				#endif
			}
		}
	}
	
	/** used to compare and save BlockStates based on their properties */
	#if MC_VER <= MC_1_7_10
	#elif MC_VER <= MC_1_12_2
	private static String serializeBlockStateProperties(IBlockState blockState)
	#else
	private static String serializeBlockStateProperties(BlockState blockState)
	#endif
	
	#if MC_VER <= MC_1_7_10
	// block properties aren't available in 1.7.10
	#else
	{
		// get the property list for this block (doesn't contain this block state's values, just the names and possible values)
		#if MC_VER <= MC_1_12_2
		java.util.Collection<IProperty<?>> blockPropertyCollection = blockState.getPropertyKeys();
		List<IProperty<?>> sortedBlockPropteryList = new ArrayList<>(blockPropertyCollection);
		#else
		java.util.Collection<net.minecraft.world.level.block.state.properties.Property<?>> blockPropertyCollection = blockState.getProperties();;
		List<net.minecraft.world.level.block.state.properties.Property<?>> sortedBlockPropteryList = new ArrayList<>(blockPropertyCollection);
		#endif
		
		// alphabetically sort the list so they are always in the same order
		sortedBlockPropteryList.sort((a, b) -> a.getName().compareTo(b.getName()));
		
		
		StringBuilder stringBuilder = new StringBuilder();
		#if MC_VER <= MC_1_12_2
		for (IProperty<?> property : sortedBlockPropteryList)
		#else
		for (net.minecraft.world.level.block.state.properties.Property<?> property : sortedBlockPropteryList)
		#endif
		{
			String propertyName = property.getName();
			
			String value = "NULL";
			
			#if MC_VER <= MC_1_12_2
			value = blockState.getValue(property).toString();
			#else
			if (blockState.hasProperty(property))
			{
				value = blockState.getValue(property).toString();
			}
			#endif
			
			stringBuilder.append("{");
			stringBuilder.append(propertyName).append(RESOURCE_LOCATION_SEPARATOR).append(value);
			stringBuilder.append("}");
		}
		
		return stringBuilder.toString();
	}
	#endif
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		
		if (obj == null || this.getClass() != obj.getClass())
		{
			return false;
		}
		
		BlockStateWrapper that = (BlockStateWrapper) obj;
		// the serialized value is used so we can test the contents instead of the references
		return Objects.equals(this.getSerialString(), that.getSerialString());
	}
	
	@Override
	public int hashCode() { return this.hashCode; }
	
	@Override
	public String toString() { return this.getSerialString(); }
	
	//endregion
	
	
	
}
