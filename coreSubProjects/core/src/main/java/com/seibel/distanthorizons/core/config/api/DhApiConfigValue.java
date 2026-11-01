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

package com.seibel.distanthorizons.core.config.api;

import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.interfaces.config.IConverter;
import com.seibel.distanthorizons.core.config.api.converters.DefaultConverter;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A wrapper used to interface with Distant Horizon's Config. <br> <br>
 *
 * When using this object you need to explicitly define the generic types,
 * otherwise Intellij won't do any type checking and the wrong types can be used. <br>
 * For example a method returning {@literal IDhApiConfig<Integer> } when the config should be a Boolean.
 *
 * @param <apiType> The datatype you, an API dev will use.
 * @param <coreType> The datatype Distant Horizons uses in the background; implementing developers can ignore this.
 * @author James Seibel
 * @version 2022-6-30
 * @since API 1.0.0
 */
public class DhApiConfigValue<coreType, apiType> implements IDhApiConfigValue<apiType>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final Set<String> CONFIG_NAME_CALLED_DEPRECATED_API = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	
	private final ConfigEntry<coreType> configBase;
	
	private final IConverter<coreType, apiType> configConverter;
	
	
	
	//==============//
 	// constructors //
	//==============//
	//region
	
	/**
	 * This constructor should only be called internally. <br>
	 * There is no reason for API users to create this object. <br><br>
	 *
	 * Uses the default object converter, this requires coreType and apiType to be the same.
	 */
	@SuppressWarnings("unchecked") // DefaultConverter's cast is safe
	public DhApiConfigValue(ConfigEntry<coreType> configBase)
	{
		this.configBase = configBase;
		this.configConverter = (IConverter<coreType, apiType>) new DefaultConverter<coreType>();
	}
	
	/**
	 * This constructor should only be called internally. <br>
	 * There is no reason for API users to create this object. <br><br>
	 */
	public DhApiConfigValue(ConfigEntry<coreType> configBase, IConverter<coreType, apiType> newConverter)
	{
		this.configBase = configBase;
		this.configConverter = newConverter;
	}
	
	//endregion
	
	
	
	//===========//
 	// overrides //
	//===========//
	//region
	
	@Override public apiType getValue() { return this.configConverter.convertToApiType(this.configBase.get()); }
	@Override public apiType getTrueValue() { return this.configConverter.convertToApiType(this.configBase.getTrueValue()); }
	@Override public apiType getApiValue() 
	{
		// if no API value is set, this should return null
		if (this.configBase.getApiValue() == null)
		{
			return null;
		}
		
		return this.configConverter.convertToApiType(this.configBase.getApiValue()); 
	}
	
	@Deprecated
	@Override
	public boolean setValue(apiType newValue)
	{ 
		if (CONFIG_NAME_CALLED_DEPRECATED_API.add(this.configBase.name))
		{
			// alternate method to show a stack trace snippet
			//StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
			//StackTraceElement[] trimmedElements = Arrays.copyOfRange(stackTraceElements, 
			//	// cut out this method and the getStackTrace()
			//	2, 
			//	// only go up an additional 3 stacks, that should be far enough
			//	Math.min(5, stackTraceElements.length));
			//String stackTraceString = StringUtil.join("\n", trimmedElements);
			
			String callerClass = this.getCallerClassName();
			LOGGER.warn("Config ["+this.configBase.name+"] was set by ["+callerClass+"] via the deprecated API method that doesn't define the API caller. \n" +
				"If you are a player this can be ignored. \n" +
				"If you are a developer please make sure your mod passes in a display name so users can know what your mod controls."
			);
		}
		
		return this.setValue(newValue, "UNKNOWN"); 
	}
	private String getCallerClassName()
	{
		StackTraceElement[] stack = new Throwable().getStackTrace();
		
		// find the first stack element that isn't part of this class
		String thisClass = this.getClass().getName();
		for (StackTraceElement element : stack)
		{
			if (!element.getClassName().equals(thisClass))
			{
				return element.getClassName();
			}
		}
		
		return "UNKNOWN";
	}
	
	@Override
	public boolean setValue(apiType newValue, String apiUserDisplayName)
	{
		if (this.configBase.getAllowApiOverride())
		{
			this.configBase.setApiValue(this.configConverter.convertToCoreType(newValue), apiUserDisplayName);
			return true;
		}
		else
		{
			return false;
		}
	}
	
	@Override
	public boolean clearValue()
	{
		if (this.configBase.getAllowApiOverride())
		{
			// no converter should be used here since null objects may need to be handled differently
			this.configBase.setApiValue(null, null);
			return true;
		}
		else
		{
			return false;
		}
	}
	
	@Override
	public boolean getCanBeOverrodeByApi() { return this.configBase.getAllowApiOverride(); }
	
	@Override public apiType getDefaultValue() { return this.configConverter.convertToApiType(this.configBase.getDefaultValue()); }
	@Override public apiType getMaxValue() { return this.configConverter.convertToApiType(this.configBase.getMax()); }
	@Override public apiType getMinValue() { return this.configConverter.convertToApiType(this.configBase.getMin()); }
	
	
	@Override
	public void addChangeListener(Consumer<apiType> onValueChangeFunc) 
	{
		this.configBase.addValueChangeListener((coreValue) -> 
		{
			apiType apiValue = this.configConverter.convertToApiType(coreValue);
			onValueChangeFunc.accept(apiValue);
		}); 
	}
	
	//endregion
	
}
