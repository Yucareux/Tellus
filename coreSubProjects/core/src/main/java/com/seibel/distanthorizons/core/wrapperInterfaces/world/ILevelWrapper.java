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

package com.seibel.distanthorizons.core.wrapperInterfaces.world;

import com.google.common.io.BaseEncoding;
import com.google.common.primitives.Longs;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import org.jetbrains.annotations.Nullable;

/**
 * A Level wrapper handles all MC related logic related to a given MC level.
 * A Level in this context is defined as a Minecraft dimension the player can play in
 * (IE the overworld, nether, end, etc.). <br><br>
 *
 * This is different from a {@link IDhLevel}
 * in the following ways: <br>
 * - A level wrapper holds a specific MC level object and will exist before a corresponding {@link IDhLevel} is created <br>
 * - A level wrapper handles all MC logic (IE getting min/max world height) <br>
 * - A level wrapper is accessible via the {@link DhApi} <br>
 * - A level wrapper can access some DH logic when the API needs it 
 *   (in general DH logic should be handled via a {@link IDhLevel} but due to how the API is currently configured
 *   it's easier to handle that logic here). <br>
 * 
 * @see IDhLevel
 * @see IClientLevelWrapper
 * @see IServerLevelWrapper
 */
public interface ILevelWrapper extends IDhApiLevelWrapper, IBindable
{
	@Override
	IDimensionTypeWrapper getDimensionType();
	
	/**
	 * Includes both the namespace and name. <br> 
	 * example: "minecraft:overworld" 
	 */
	@Override
	String getDimensionName();
	
	long getHashedSeed();
	/**
	 * Returns the result of {@link #getHashedSeed()}, encoded into a short string. <br>
	 * Prefer using this method over stringifying the number directly.
	 */
	default String getHashedSeedEncoded()
	{
		String encoded = BaseEncoding.base32Hex().encode(Longs.toByteArray(this.getHashedSeed()));
		return encoded.substring(0, 13).toLowerCase(); // Remaining 3 chars are padding
	}
	
	/** A string that uniquely identifies this level. */
	@Override
	String getDhIdentifier();
	
	@Override
	boolean hasCeiling();
	
	@Override
	boolean hasSkyLight();
	
	@Override
	int getMaxHeight();
	@Override
	int getMinHeight();
	
	// Future note: don't use MC's getSeaLevel()
	// this method returns an incorrect value for The End
	// on MC 1.21.1,
	// use the chunk generator's sea level instead
	//int getSeaLevel();
	
	/** Fired when the level is being unloaded. Doesn't unload the level. */
	void onUnload();
	
	/** 
	 * Used so we can access DH related methods/objects
	 * from the {@link DhApi}.
	 */
	void setDhLevel(IDhLevel parentLevel);
	@Nullable
	IDhLevel getDhLevel();
	
	
	
}
