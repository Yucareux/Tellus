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

package com.seibel.distanthorizons.api.interfaces.config.client;

import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigGroup;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;

/**
 * Distant Horizons' texture configuration. <br><br>
 *
 * Not to be confused with {@link IDhApiNoiseTextureConfig}
 * which handles simulated textures.
 * 
 * @author James Seibel
 * @version 2026-09-12
 * @since API 7.1.0
 */
public interface IDhApiTextureConfig extends IDhApiConfigGroup
{
	/** If enabled a texture will be rendered on the LODs. */
	IDhApiConfigValue<Boolean> texturesEnabled();
	/** defines what LOD detail level textures will be rendered on */
	IDhApiConfigValue<Integer> maxTexturedLodDetailLevel();
	
	/** 
	 * defines a CSV list of block resource locations that won't use textures in DH rendering.  <Br><Br>
	 *
	 * Example: "minecraft:grass_block,nylium" <Br><Br>
	 * 
	 * Changes require a restart. 
	 */
	IDhApiConfigValue<String> blocksDontRenderTextureCsv();
	/** 
	 * defines a CSV list of block resource locations
	 * that will use their bottom texture for the sides instead of the actual side. <Br><Br>
	 * 
	 * This is helpful for things like grass blocks where the side texture would
	 * repeat, which looks bad. <br><br>
	 * 
	 * Changes require a restart.
	 */
	IDhApiConfigValue<String> blocksDontUseSideTextureCsv();
	/**
	 * defines a CSV list of block tags
	 * that will use their bottom texture for the sides instead of the actual side. <Br><Br>
	 *
	 * This is helpful for things like grass blocks where the side texture would
	 * repeat, which looks bad. <br><br>
	 * 
	 * Example: "minecraft:grass_block,nylium" <br><br>
	 * 
	 * Changes require a restart.
	 */
	IDhApiConfigValue<String> blockTagsDontUseSideTextureCsv();
	/**
	 * Can be used to fix issues with mods/blocks where
	 * a side uses just part of a modeled block's texture. <br><br>
	 *
	 * Example: "grass_blocks,nylium" <br><br>
	 *
	 * Example: "minecraft:beacon" 
	 */
	IDhApiConfigValue<String> blocksAlwaysRasterizeTextureCsv();
	
	
	
}
