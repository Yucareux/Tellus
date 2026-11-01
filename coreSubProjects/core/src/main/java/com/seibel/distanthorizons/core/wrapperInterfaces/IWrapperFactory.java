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

package com.seibel.distanthorizons.core.wrapperInterfaces;

import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IChunkGenerator;
import com.seibel.distanthorizons.core.wrapperInterfaces.worldGeneration.IRoughGenerator;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * This handles creating abstract wrapper objects.
 *
 * @author James Seibel
 * @version 2022-12-5
 */
public interface IWrapperFactory extends IDhApiWrapperFactory, IBindable
{
	IChunkGenerator createChunkGenerator(IDhLevel targetLevel);
	/** Returns null for MC versions that don't support rough surface generation */
	@Nullable
	IRoughGenerator createRoughGenerator(IDhLevel targetLevel, IChunkGenerator batchChunkGenerator);
	
	IBiomeWrapper deserializeBiomeWrapper(String str, ILevelWrapper levelWrapper) throws IOException;
	IBiomeWrapper getPlainsBiomeWrapper(ILevelWrapper levelWrapper); 
	default IBiomeWrapper deserializeBiomeWrapperOrGetDefault(String str, ILevelWrapper levelWrapper)
	{
		IBiomeWrapper biome;
		try
		{
			biome = this.deserializeBiomeWrapper(str, levelWrapper);
		}
		catch (IOException e)
		{
			biome = this.getPlainsBiomeWrapper(levelWrapper);
		}
		
		return biome;
	}
	
	
	IBlockStateWrapper deserializeBlockStateWrapper(String str, ILevelWrapper levelWrapper) throws IOException;
	IBlockStateWrapper getAirBlockStateWrapper();
	IBlockStateWrapper getWaterBlockStateWrapper(ILevelWrapper levelWrapper);
	/** Returns AIR if the deserialization fails */
	default IBlockStateWrapper deserializeBlockStateWrapperOrGetDefault(String str, ILevelWrapper levelWrapper)
	{
		IBlockStateWrapper blockState;
		try
		{
			blockState = this.deserializeBlockStateWrapper(str, levelWrapper);
		}
		catch (IOException e)
		{
			blockState = this.getAirBlockStateWrapper();
		}
		
		return blockState;
	}
	
	
	/**
	 * Returns the set of {@link IBlockStateWrapper}'s that shouldn't be rendered. <br>
	 * Generally this contains blocks like: air, barriers, light blocks, etc. 
	 */
	ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredBlocks(ILevelWrapper levelWrapper);
	/**
	 * Returns the set of {@link IBlockStateWrapper}'s that shouldn't be rendered in caves. <br>
	 * Generally this contains blocks like: air, rails, glow lichen, etc. 
	 */
	ObjectOpenHashSet<IBlockStateWrapper> getRendererIgnoredCaveBlocks(ILevelWrapper levelWrapper);
	
	ObjectOpenHashSet<IBlockStateWrapper> getWaterSubsurfaceReplacementBlocks(ILevelWrapper levelWrapper);
	ObjectOpenHashSet<IBlockStateWrapper> getWaterSurfaceReplacementBlocks(ILevelWrapper levelWrapper);
	/** clears the cached values */
	void resetCachedIgnoredBlocksSets();
	
	
	/**
	 * Specifically designed to be used with the API.
	 *
	 * @throws ClassCastException with instructions on expected objects if the object couldn't be cast
	 */
	IChunkWrapper createChunkWrapper(Object[] objectArray) throws ClassCastException;
	
	
	
	IVertexBufferWrapper createVboWrapper(String name);
	ILodContainerUniformBufferWrapper createLodContainerUniformWrapper();
	IDhGenericObjectVertexBufferContainer createGenericObjectVboContainer();
	IDhGenericRenderer createGenericRenderer();
	
}
