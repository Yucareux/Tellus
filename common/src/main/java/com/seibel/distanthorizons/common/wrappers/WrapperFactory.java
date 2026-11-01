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

package com.seibel.distanthorizons.common.wrappers;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhChunkGenerator;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.DhRoughSurfaceGenerator;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IChunkGenerator;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IRoughGenerator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

#if MC_VER > MC_1_17_1
import net.minecraft.core.Holder;
#endif

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.FakeBlockState;
import com.seibel.distanthorizons.common.backports.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
#elif MC_VER <= MC_1_12_2
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
#else
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
#endif

import java.io.IOException;

/**
 * This handles creating abstract wrapper objects.
 */
public class WrapperFactory implements IWrapperFactory
{
	public static final WrapperFactory INSTANCE = new WrapperFactory();
	
	
	
	//=====================//
	// internal properties //
	//=====================//
	//region
	
	private AbstractDhRenderApiDefinition renderDefinition;
	private AbstractDhRenderApiDefinition getRenderDefinition()
	{
		// delayed get to make sure we don't accidentally set the variable before it's bound
		if (this.renderDefinition != null)
		{
			return this.renderDefinition;
		}
		
		this.renderDefinition = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
		return this.renderDefinition;
	}
	
	//endregion
	
	
	
	//==============//
	// core methods //
	//==============//
	//region
	
	@Override
	public IChunkGenerator createChunkGenerator(IDhLevel targetLevel)
	{
		if (targetLevel instanceof IDhServerLevel)
		{
			return new DhChunkGenerator((IDhServerLevel) targetLevel);
		}
		else
		{
			throw new IllegalArgumentException("The target level must be a server-side level.");
		}
	}
	
	@Override
	public IRoughGenerator createRoughGenerator(IDhLevel targetLevel, IChunkGenerator batchChunkGenerator)
	{
		#if MC_VER <= MC_1_18_2
		return null;
		#else
		if (targetLevel instanceof IDhServerLevel)
		{
			return new DhRoughSurfaceGenerator(((IDhServerLevel) targetLevel).getServerLevelWrapper(), (DhChunkGenerator) batchChunkGenerator);
		}
		else
		{
			throw new IllegalArgumentException("The target level must be a server-side level.");
		}
		#endif
	}
	
	@Override
	public IDhApiBiomeWrapper getBiomeWrapper(String resourceLocationString, IDhApiLevelWrapper levelWrapper) throws IOException, ClassCastException
	{
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			throw new ClassCastException("levelWrapper must be returned by DH and of type ["+ILevelWrapper.class.getName()+"].");
		}
		
		return BiomeWrapper.deserialize(resourceLocationString, (ILevelWrapper)levelWrapper);
	}
	@Override
	public IDhApiBlockStateWrapper getDefaultBlockStateWrapper(String resourceLocationString, IDhApiLevelWrapper levelWrapper) throws IOException, ClassCastException
	{
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			throw new ClassCastException("Invalid ["+IDhApiLevelWrapper.class.getSimpleName()+"] value given. Level wrapper object must be one given by the DH API (it can't be a custom implementation), specifically of type ["+ILevelWrapper.class.getName()+"].");
		}
		
		return BlockStateWrapper.deserialize(resourceLocationString, (ILevelWrapper)levelWrapper);
	}
	
	@Override
	public IBiomeWrapper deserializeBiomeWrapper(String str, ILevelWrapper levelWrapper) throws IOException { return BiomeWrapper.deserialize(str, levelWrapper); }
	@Override
	public IBiomeWrapper getPlainsBiomeWrapper(ILevelWrapper levelWrapper)
	{
		try
		{
			return BiomeWrapper.deserialize(BiomeWrapper.PLAINS_RESOURCE_LOCATION_STRING, levelWrapper);
		}
		catch (IOException e)
		{
			throw new LodUtil.AssertFailureException("Unable to parse plains resource string ["+BiomeWrapper.PLAINS_RESOURCE_LOCATION_STRING+"], error:\n " + e.getMessage());
		}
	}
	
	@Override
	public IBlockStateWrapper deserializeBlockStateWrapper(String str, ILevelWrapper levelWrapper) throws IOException { return BlockStateWrapper.deserialize(str, levelWrapper); }
	@Override
	public IBlockStateWrapper getAirBlockStateWrapper() { return BlockStateWrapper.AIR; }
	@Override 
	public IBlockStateWrapper getWaterBlockStateWrapper(ILevelWrapper levelWrapper) { return BlockStateWrapper.getWaterBlockStateWrapper(levelWrapper); }
	@Override
	public ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredBlocks(ILevelWrapper levelWrapper) { return BlockStateWrapper.getRendererIgnoredBlocks(levelWrapper); }
	@Override
	public ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredCaveBlocks(ILevelWrapper levelWrapper) { return BlockStateWrapper.getRendererIgnoredCaveBlocks(levelWrapper); }
	@Override
	public ObjectOpenHashSet<IBlockStateWrapper> getWaterSubsurfaceReplacementBlocks(ILevelWrapper levelWrapper) { return BlockStateWrapper.getWaterSubsurfaceReplacementBlocks(levelWrapper); }
	@Override
	public ObjectOpenHashSet<IBlockStateWrapper> getWaterSurfaceReplacementBlocks(ILevelWrapper levelWrapper) { return BlockStateWrapper.getWaterSurfaceReplacementBlocks(levelWrapper); }
	
	@Override
	public void resetCachedIgnoredBlocksSets() { BlockStateWrapper.clearCachedIgnoreBlocks(); }
	
	
	/**
	 * Note: when this is updated for different MC versions, make sure you also update the documentation in
	 * {@link IDhApiWorldGenerator#generateChunks} and the type list in {@link WrapperFactory#createChunkWrapperErrorMessage}. <br><br>
	 *
	 * For full method documentation please see: {@link IWrapperFactory#createChunkWrapper}
	 *
	 * @see IWrapperFactory#createChunkWrapper
	 */
	public IChunkWrapper createChunkWrapper(Object[] objectArray) throws ClassCastException
	{
		if (objectArray.length == 1 
			&& objectArray[0] instanceof IChunkWrapper)
		{
			try
			{
				// this path should only happen when called by Distant Horizons code
				// API implementors should never hit this path
				return (IChunkWrapper) objectArray[0];
			}
			catch (Exception e)
			{
				throw new ClassCastException(createChunkWrapperErrorMessage(objectArray));
			}
		}
		
		else if (objectArray.length != 2)
		{
			throw new ClassCastException(createChunkWrapperErrorMessage(objectArray));
		}
		
		
		// correct number of parameters from the API
		
		//=======//
		// chunk //
		//=======//
		//region
		
		boolean chunkClassCorrect;
		#if MC_VER <= MC_1_12_2
		chunkClassCorrect = (objectArray[0] instanceof Chunk);
		#else
		chunkClassCorrect = (objectArray[0] instanceof ChunkAccess);
		#endif
		if (!chunkClassCorrect)
		{
			throw new ClassCastException(createChunkWrapperErrorMessage(objectArray));
		}
		
		#if MC_VER <= MC_1_12_2
		Chunk chunk = (Chunk) objectArray[0];
		#else
		ChunkAccess chunk = (ChunkAccess) objectArray[0];
		#endif
		
		//endregion
		
		
		
		//=======//
		// level //
		//=======//
		//region
		
		boolean levelClassCorrect;
		#if MC_VER <= MC_1_12_2
		levelClassCorrect = (objectArray[1] instanceof World);
		#else
		levelClassCorrect = (objectArray[1] instanceof Level);
		#endif
		
		if (!levelClassCorrect)
		{
			throw new ClassCastException(createChunkWrapperErrorMessage(objectArray));
		}
		
		#if MC_VER <= MC_1_12_2
		World level = (World) objectArray[1];
		#else
		Level level = (Level) objectArray[1];
		#endif
		
		//endregion
		
		
		
		//===============//
		// level wrapper //
		//===============//
		//region
		
		boolean isClientSide;
		#if MC_VER <= MC_1_12_2
		isClientSide = level.isRemote;
		#else
		isClientSide = level.isClientSide();
		#endif
		
		ILevelWrapper levelWrapper;
		if (isClientSide)
		{
			#if MC_VER <= MC_1_12_2
			levelWrapper = ClientLevelWrapper.getWrapper((WorldClient)level);
			#else
			levelWrapper = ClientLevelWrapper.getWrapper((ClientLevel)level);
			#endif
		}
		else
		{
			#if MC_VER <= MC_1_12_2
			levelWrapper = ServerLevelWrapper.getWrapper((WorldServer)level);
			#else
			levelWrapper = ServerLevelWrapper.getWrapper((ServerLevel)level);
			#endif
		}
		
		//endregion
		
		
		
		return new ChunkWrapper(chunk, levelWrapper);
	}
	/**
	 * Note: when this is updated for different MC versions,
	 * make sure you also update the documentation in {@link IDhApiWorldGenerator#generateChunks}.
	 */
	private static String createChunkWrapperErrorMessage(Object[] objectArray)
	{
		String[] expectedClassNames;
		
		#if MC_VER <= MC_1_12_2
		expectedClassNames = new String[]
			{
				Chunk.class.getName(),
				"[WorldClient] or [WorldServer]" // Classes are not referenced by names to avoid exception when one of them is missing
			};
		#else
		expectedClassNames = new String[]
			{
				ChunkAccess.class.getName(),
				"[ServerLevel] or [ClientLevel]" // Classes are not referenced by names to avoid exception when one of them is missing
			};
		#endif
		
		return createWrapperErrorMessage("Chunk wrapper", expectedClassNames, objectArray);
	}
	
	
	@Override public IVertexBufferWrapper createVboWrapper(String name) { return this.getRenderDefinition().createVboWrapper(name); }
	@Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return this.getRenderDefinition().createLodContainerUniformWrapper(); }
	@Override public IDhGenericObjectVertexBufferContainer createGenericObjectVboContainer() { return this.getRenderDefinition().createGenericVboContainer(); }
	@Override public IDhGenericRenderer createGenericRenderer() { return this.getRenderDefinition().createGenericRenderer(); }
	
	//endregion
	
	
	
	//=============//
	// api methods //
	//=============//
	//region
	
	// documentation should be in the API interface
	
	public IDhApiBiomeWrapper getBiomeWrapper(Object[] objectArray, IDhApiLevelWrapper levelWrapper)
	{
		// confirm the API level wrapper is also a Core wrapper 
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			throw new ClassCastException("Invalid ["+IDhApiLevelWrapper.class.getSimpleName()+"] value given. Level wrapper object must be one given by the DH API (it can't be a custom implementation), specifically of type ["+ILevelWrapper.class.getName()+"].");
		}
		ILevelWrapper coreLevelWrapper = (ILevelWrapper) levelWrapper;
		
		
		
		#if MC_VER < MC_1_20_4
		if (objectArray.length != 1)
		{
			throw new ClassCastException(createBiomeWrapperErrorMessage(objectArray));
		}
		#endif
		
		#if MC_VER <= MC_1_7_10
		if (!(objectArray[0] instanceof BiomeGenBase))
		{
			throw new ClassCastException(createBiomeWrapperErrorMessage(objectArray));
		}
		
		BiomeGenBase biome = (BiomeGenBase) objectArray[0];
		return BiomeWrapper.getBiomeWrapper(biome, coreLevelWrapper);
		#elif MC_VER < MC_1_18_2
		if (!(objectArray[0] instanceof Biome))
		{
			throw new ClassCastException(createBiomeWrapperErrorMessage(objectArray));
		}
		
		Biome biome = (Biome) objectArray[0];
		return BiomeWrapper.getBiomeWrapper(biome, coreLevelWrapper);
		#else
		if (!(objectArray[0] instanceof Holder) || !(((Holder<?>) objectArray[0]).value() instanceof Biome))
		{
			throw new ClassCastException(createBiomeWrapperErrorMessage(objectArray));
		}
		
		Holder<Biome> biomeHolder = (Holder<Biome>) objectArray[0];
		return BiomeWrapper.getBiomeWrapper(biomeHolder, coreLevelWrapper);
		#endif
	}
	/**
	 * Note: when this is updated for different MC versions,
	 * make sure you also update the documentation in {@link IDhApiWrapperFactory#getBiomeWrapper}.
	 */
	private static String createBiomeWrapperErrorMessage(Object[] objectArray)
	{
		String[] expectedClassNames;
		
		#if MC_VER <= MC_1_7_10
		expectedClassNames = new String[] { BiomeGenBase.class.getName() };
		#elif MC_VER < MC_1_18_2
		expectedClassNames = new String[] { Biome.class.getName() };
		#else
		expectedClassNames = new String[] { Holder.class.getName()+"<"+Biome.class.getName()+">" };
		#endif
		
		return createWrapperErrorMessage("Biome wrapper", expectedClassNames, objectArray);
	}
	
	public IDhApiBlockStateWrapper getBlockStateWrapper(Object[] objectArray, IDhApiLevelWrapper levelWrapper)
	{
		// confirm the API level wrapper is also a Core wrapper 
		if (!(levelWrapper instanceof ILevelWrapper))
		{
			throw new ClassCastException("Invalid ["+IDhApiLevelWrapper.class.getSimpleName()+"] value given. Level wrapper object must be one given by the DH API (it can't be a custom implementation), specifically of type ["+ILevelWrapper.class.getName()+"].");
		}
		ILevelWrapper coreLevelWrapper = (ILevelWrapper) levelWrapper;
		
		
		
		#if MC_VER <= MC_1_7_10
		if (objectArray.length != 3
			&& objectArray.length != 1)
		{
			throw new ClassCastException(createBlockStateWrapperErrorMessage(objectArray));
		}
		#else
		if (objectArray.length != 1)
		{
			throw new ClassCastException(createBlockStateWrapperErrorMessage(objectArray));
		}
		#endif
		
		boolean blockClassCorrect;
		#if MC_VER <= MC_1_7_10
		blockClassCorrect =
			(
				(objectArray[0] instanceof Block)
				&& (objectArray[1] instanceof Integer)
				&& (objectArray[2] instanceof Integer)
			)
			||
			(objectArray[0] instanceof IBlockState)
		;
		#elif MC_VER <= MC_1_12_2
		blockClassCorrect = (objectArray[0] instanceof IBlockState);
		#else
		blockClassCorrect = (objectArray[0] instanceof BlockState);
		#endif
		if (!blockClassCorrect)
		{
			throw new ClassCastException(createBlockStateWrapperErrorMessage(objectArray));
		}
		
		#if MC_VER <= MC_1_7_10
		IBlockState fakeBlockState;
		if (objectArray[0] instanceof IBlockState)
		{
			fakeBlockState = (IBlockState)objectArray[0];
		}
		else
		{
			fakeBlockState = new FakeBlockState(
				(Block) objectArray[0], // block 
				(Integer) objectArray[1], // meta 
				(Integer) objectArray[2] // blockId
			);
		}
		
		return BlockStateWrapper.fromBlockState(fakeBlockState, coreLevelWrapper);
		#elif MC_VER <= MC_1_12_2
		IBlockState blockState = (IBlockState) objectArray[0];
		return BlockStateWrapper.fromBlockState(blockState, coreLevelWrapper);
		#else
		BlockState blockState = (BlockState) objectArray[0];
		return BlockStateWrapper.fromBlockState(blockState, coreLevelWrapper);
		#endif
	}
	/**
	 * Note: when this is updated for different MC versions,
	 * make sure you also update the documentation in {@link IDhApiWrapperFactory#getBlockStateWrapper}.
	 */
	private static String createBlockStateWrapperErrorMessage(Object[] objectArray)
	{
		String[] expectedClassNames;
		
		#if MC_VER <= MC_1_12_2
		expectedClassNames = new String[] { IBlockState.class.getName() };
		#elif MC_VER <= MC_1_17_1
		expectedClassNames = new String[] { Biome.class.getName() };
		#else
		expectedClassNames = new String[] { Holder.class.getName()+"<"+Biome.class.getName()+">" };
		#endif
		
		return createWrapperErrorMessage("BlockState wrapper", expectedClassNames, objectArray);
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static String createWrapperErrorMessage(String wrapperName, String[] expectedClassNames, Object[] objectArray)
	{
		// error header
		StringBuilder message = new StringBuilder(
			wrapperName + " creation failed. \n" +
				"Expected object array parameters: \n");
		
		
		// expected parameters
		for (String expectedClassName : expectedClassNames)
		{
			message.append("[").append(expectedClassName).append("], \n");
		}
		
		
		// given parameters
		if (objectArray.length != 0)
		{
			message.append("Given parameters: ");
			for (Object obj : objectArray)
			{
				String objClassName = (obj != null) ? obj.getClass().getName() : "NULL";
				message.append("[").append(objClassName).append("], ");
			}
		}
		else
		{
			message.append(" No parameters given.");
		}
		
		
		return message.toString();
	}
	
	//endregion
	
	
	
}
