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

package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.StringUtil;

public class IgnoredDimensionCsvHandler extends DhApiBeforeRenderEvent implements IConfigListener
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static IgnoredDimensionCsvHandler INSTANCE = new IgnoredDimensionCsvHandler();
	
	private String[] dimensionNames = null;
	
	
	
	//=============//
 	// constructor //
	//=============//
	
	/** private since we only ever need one handler at a time */
	private IgnoredDimensionCsvHandler() { }
	
	
	
	//=================//
	// config handling //
	//=================//
	
	@Override
	public void onConfigValueSet()
	{
		String ignoredDimensionCsvString = Config.Client.Advanced.Graphics.Experimental.ignoredDimensionCsv.get();
		if (ignoredDimensionCsvString == null 
			|| ignoredDimensionCsvString.isEmpty())
		{
			LOGGER.info("Dimension ignoring disabled, DH will render all dimensions.");
			this.dimensionNames = null;
		}
		else
		{
			try
			{
				this.dimensionNames = ignoredDimensionCsvString.split(",");
				LOGGER.info("DH set to ignore dimensions: ["+ StringUtil.join(", ", this.dimensionNames)+"].");
			}
			catch (Exception e)
			{
				LOGGER.error("Failed to separate ignored dimensions from CSV string, error: ["+e.getMessage()+"].", e);
				this.dimensionNames = null;
			}
		}
		
	}
	
	
	
	//===================//
	// external handling //
	//===================//
	
	@Override
	public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event)
	{
		String dimName = event.value.clientLevelWrapper.getDimensionName();
		if (IgnoredDimensionCsvHandler.INSTANCE.dimensionNameShouldBeIgnored(dimName))
		{
			event.cancelEvent();
			Config.Client.Advanced.Graphics.Fog.enableVanillaFog.setApiValue(true, ModInfo.READABLE_NAME);
			Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.setApiValue(EDhApiMcRenderingFadeMode.NONE, ModInfo.READABLE_NAME);
		}
		else
		{
			Config.Client.Advanced.Graphics.Fog.enableVanillaFog.setApiValue(null, ModInfo.READABLE_NAME);
			Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.setApiValue(null, ModInfo.READABLE_NAME);
		}
	}
	
	public boolean dimensionNameShouldBeIgnored(String dimName)
	{
		if (this.dimensionNames == null 
			|| this.dimensionNames.length == 0)
		{
			return false;
		}
		
		// remove the server hash if present so we can just compare the dimension name
		int atIndex = dimName.indexOf('@');
		if (atIndex >= 0)
		{
			dimName = dimName.substring(atIndex + 1);
		}
		
		for (int i = 0; i < this.dimensionNames.length; i++)
		{
			String dimNameToIgnore = this.dimensionNames[i];
			if (dimName.equalsIgnoreCase(dimNameToIgnore))
			{
				return true;
			}
		}
		
		return false;
	}
	
	
}
