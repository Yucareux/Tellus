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

package com.seibel.distanthorizons.core.api.external.methods.config.client;

import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiNoiseTextureConfig;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiTextureConfig;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.api.DhApiConfigValue;

public class DhApiTextureConfig implements IDhApiTextureConfig
{
	public static DhApiTextureConfig INSTANCE = new DhApiTextureConfig();
	
	private DhApiTextureConfig() { }
	
	
	
	@Override
	public IDhApiConfigValue<Boolean> texturesEnabled()
	{ return new DhApiConfigValue<Boolean, Boolean>(Config.Client.Advanced.Graphics.Texture.enableTexturedLods); }
	@Override
	public IDhApiConfigValue<Integer> maxTexturedLodDetailLevel()
	{ return new DhApiConfigValue<Integer, Integer>(Config.Client.Advanced.Graphics.Texture.maxTexturedLodDetailLevel); }
	
	
	
	@Override
	public IDhApiConfigValue<String> blocksDontRenderTextureCsv()
	{ return new DhApiConfigValue<String, String>(Config.Client.Advanced.Graphics.Texture.blocksDontRenderTextureCsv); }
	@Override
	public IDhApiConfigValue<String> blocksDontUseSideTextureCsv()
	{ return new DhApiConfigValue<String, String>(Config.Client.Advanced.Graphics.Texture.blocksDontUseSideTextureCsv); }
	@Override
	public IDhApiConfigValue<String> blockTagsDontUseSideTextureCsv()
	{ return new DhApiConfigValue<String, String>(Config.Client.Advanced.Graphics.Texture.blockTagsDontUseSideTextureCsv); }
	@Override
	public IDhApiConfigValue<String> blocksAlwaysRasterizeTextureCsv()
	{ return new DhApiConfigValue<String, String>(Config.Client.Advanced.Graphics.Texture.blocksAlwaysRasterizeTextureCsv); }
	
}
