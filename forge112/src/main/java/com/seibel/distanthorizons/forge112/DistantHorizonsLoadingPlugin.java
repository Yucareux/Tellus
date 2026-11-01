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

package com.seibel.distanthorizons.forge112;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class DistantHorizonsLoadingPlugin implements IFMLLoadingPlugin
{
	@Override
	public @Nullable String[] getASMTransformerClass()
	{
		return new String[0];
	}
	@Override
	public @Nullable String getModContainerClass()
	{
		return null;
	}
	@Override
	public @Nullable String getSetupClass()
	{
		return null;
	}
	@Override
	public void injectData(Map<String, Object> data) { }
	@Override
	public @Nullable String getAccessTransformerClass()
	{
		return null;
	}
	
}
