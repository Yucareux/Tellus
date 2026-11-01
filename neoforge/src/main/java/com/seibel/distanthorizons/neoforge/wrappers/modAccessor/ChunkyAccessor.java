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

package com.seibel.distanthorizons.neoforge.wrappers.modAccessor;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractChunkyAccessor;

#if MC_VER <= MC_1_18_2
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.coreapi.ModInfo;
#else
import org.popcraft.chunky.ChunkyProvider;
#endif

public class ChunkyAccessor extends AbstractChunkyAccessor
{
	public ChunkyAccessor()
	{
		#if MC_VER <= MC_1_18_2
		LOGGER.info("Chunky API not present, disabling DH world gen.");
		Config.Common.WorldGenerator.generatorPlan.setApiValue(EDhApiGeneratorPlan.DISABLED, ModInfo.READABLE_NAME + " / Chunky");
		#else
		// chunky API present and supported
		#endif
	}
	
	
	
	@Override
	public String getModName() { return "chunky"; }
	
	@Override
	protected void bindOnGenerationProgressEvent()
	{
		#if MC_VER <= MC_1_18_2
		// API not present
		#else
		ChunkyProvider.get().getApi().onGenerationProgress((event) -> this.onGenEvent());
		#endif
	}
}
