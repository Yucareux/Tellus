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

package com.seibel.distanthorizons.core.config.types;


import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.util.NumberUtil;
import com.seibel.distanthorizons.core.config.file.ConfigFileHandler;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.config.types.enums.EConfigEntryAppearance;
import com.seibel.distanthorizons.core.config.types.enums.EConfigValidity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * This config type allows for entering text, number, or enum values.
 *
 * @author coolGi
 */
public class ConfigEntry<T> extends AbstractConfigBase<T>
{
	private final String comment;
	private T min;
	private T max;
	private final ArrayList<IConfigListener> listenerList;
	private final String chatCommandName;
	@Nullable
	private final IShowEnumOptionFunc showEnumOptionFunc;
	
	/**
	 * If true this config can be controlled by the API <br>
	 * and any get() method calls will return the apiValue if it is set.
	 */
	private final boolean allowApiOverride;
	/** Will be null if un-set */
	@Nullable
	private T apiValue;
	@Nullable
	private String apiUser;
	/** 
	 * When this option was last modified by the API.
	 * This is present to prevent aliasing if the option is
	 * rapidly set/cleared.
	 */
	private long apiValueLastSetMs = 0L;
	
	/** 
	 * Will be null if un-set. <br> <br>
	 * 
	 * Some options aren't supported on all Minecraft versions,
	 * in those cases this value will be set to override the 
	 * config file option.
	 */
	@Nullable
	private T mcVersionOverrideValue;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private ConfigEntry(
		EConfigEntryAppearance appearance, 
		String comment, String chatCommandName, 
		@Nullable IShowEnumOptionFunc showEnumOptionFunc,
		T value, T min, T max,
		boolean allowApiOverride, 
		ArrayList<IConfigListener> listenerList)
	{
		super(appearance, value);
		
		this.comment = comment;
		this.min = min;
		this.max = max;
		this.chatCommandName = chatCommandName;
		this.showEnumOptionFunc = showEnumOptionFunc;
		this.allowApiOverride = allowApiOverride;
		this.listenerList = listenerList;
	}
	
	//endregion
	
	
	
	//==========================//
	// property getters/setters //
	//==========================//
	//region
	
	/** the string used when entering the config into the command line or chat */
	public String getChatCommandName() { return this.chatCommandName; }
	
	public String getComment() { return this.comment; }
	
	@Nullable
	public IShowEnumOptionFunc getShowEnumOptionFunc() { return this.showEnumOptionFunc; }
	
	/**
	 * If true this config can be controlled by the API <br>
	 * and any get() method calls will return the apiValue if it is set.
	 */
	public boolean getAllowApiOverride() { return this.allowApiOverride; }
	
	public T getMin() { return this.min; }
	public void setMin(T newMin) { this.min = newMin; }
	public T getMax() { return this.max; }
	public void setMax(T newMax) { this.max = newMax; }
	
	//endregion
	
	
	
	//===============//
	// value setters //
	//===============//
	//region

	/**
	 * Pre 3.3.2 signature, kept for mods that call it reflectively
	 * (Tellus 0.8.3 builds made for the 3.3.1 fork).
	 */
	public void setApiValue(T newApiValue) { this.setApiValue(newApiValue, null); }
	public void setApiValue(T newApiValue, String apiUser)
	{
		this.apiValue = newApiValue;
		if (apiUser != null)
		{
			// don't ever pass in a null value so we can see who the last set API user was
			this.apiUser = apiUser;
		}
		
		if (newApiValue != null)
		{
			// only update the last set time if we're changing to a new value,
			// otherwise constant null sets 
			// (which may occur if the API isn't being used)
			// incorrectly causes the UI to think the API was used recently 
			this.apiValueLastSetMs = System.currentTimeMillis();
		}
		
		synchronized (this.listenerList)
		{
			this.listenerList.forEach(IConfigListener::onConfigValueSet);
		}
	}
	
	public boolean apiIsOverriding() 
	{ 
		if (!this.allowApiOverride)
		{
			// this config can't be controlled via the API
			return false;
		}
		
		if (this.apiValue != null)
		{
			// an API value is currently present
			return true;
		}
		
		long timeSinceLastApiSet = (System.currentTimeMillis() - this.apiValueLastSetMs);
		if (timeSinceLastApiSet < 500L)
		{
			// an API value was present very recently
			
			// This check is necessary to fix an issue where rendering mods may change
			// an API option on a per-frame basis then disable it after the frame
			// is over.
			// Causing the UI to appear like the option can be changed,
			// when it can't.
			return true;
		}
		
		// this option can be controlled like normal
		return false;
	}
	
	/** setting to null will allow the config to be used normally */
	public void setMcVersionOverrideValue(@Nullable T value)
	{ this.mcVersionOverrideValue = value; }
	
	public boolean mcVersionOverridePresent()
	{ return this.mcVersionOverrideValue != null; }
	
	/** 
	 * Should only be used when loading the config from file. <Br>
	 * Sets the value without informing the rest of the code (ie, it doesn't call listeners, or saving the value to file).
	 * @see ConfigFileHandler
	 */
	public void setWithoutFiringEvents(T newValue) { super.set(newValue); }
	
	/** Sets the value without saving */
	public void setWithoutSaving(T newValue)
	{
		super.set(newValue);
		
		synchronized (this.listenerList)
		{
			this.listenerList.forEach(IConfigListener::onConfigValueSet);
		}
	}
	@Override
	public void set(T newValue)
	{
		this.setWithoutSaving(newValue);
		this.save();
	}
	
	public void uiSetWithoutSaving(T newValue)
	{
		this.setWithoutSaving(newValue);
		
		synchronized (this.listenerList)
		{
			this.listenerList.forEach(IConfigListener::onUiModify);
		}
	}
	public void uiSet(T newValue)
	{
		this.set(newValue);
		
		synchronized (this.listenerList)
		{
			this.listenerList.forEach(IConfigListener::onUiModify);
		}
	}
	
	//endregion
	
	
	
	//===============//
	// value getters //
	//===============//
	//region
	
	@Override
	public T get()
	{
		// always use the MC version specific option if defined
		if (this.mcVersionOverrideValue != null)
		{
			return this.mcVersionOverrideValue;
		}
		
		if (this.allowApiOverride 
			&& this.apiValue != null)
		{
			return this.apiValue;
		}
		
		return super.get();
	}
	/** Ignores the API value if set. */
	public T getTrueValue() { return super.get(); }
	
	public T getDefaultValue() { return super.defaultValue; }
	
	@Nullable
	public T getApiValue() { return this.apiValue; }
	@Nullable
	public String getApiUser() { return this.apiUser; }
	
	//endregion
	
	
	
	//===========//
	// listeners //
	//===========//
	//region
	
	/** Fired whenever the config value changes to a new value. */
	public void addValueChangeListener(Consumer<T> onValueChangeFunc)
	{
		ConfigChangeListener<T> changeListener = new ConfigChangeListener<>(this, onValueChangeFunc);
		this.addListener(changeListener);
	}
	/** Fired whenever the config value is updated, including when the value doesn't change (IE when the UI changes state or the config is reloaded). */
	public void addListener(IConfigListener newListener) 
	{
		synchronized (this.listenerList)
		{
			this.listenerList.add(newListener);
		}
	}
	
	public void removeListener(IConfigListener oldListener) 
	{
		synchronized (this.listenerList)
		{
			this.listenerList.remove(oldListener);
		}
	}
	
	public void clearListeners() 
	{
		synchronized (this.listenerList)
		{
			this.listenerList.clear();
		}
	}
	
	//endregion
	
	
	
	//====================//
	// min/max validation //
	//====================//
	//region
	
	/** Checks if this config's current value is valid */
	public EConfigValidity getValidity() { return this.getValidity(this.value, this.min, this.max); }
	/** Checks if the given value is valid */
	public EConfigValidity getValidity(@Nullable T value) { return this.getValidity(value, this.min, this.max); }
	/** Checks if the given value is valid */
	public EConfigValidity getValidity(@Nullable T value, @Nullable T min, @Nullable T max)
	{
		if (!ConfigHandler.INSTANCE.runMinMaxValidation)
		{
			return EConfigValidity.VALID;
		}
		else if (min == null 
				&& max == null)
		{
			// no validation is needed for this field
			return EConfigValidity.VALID;
		}
		else if (value == null 
				|| this.value == null
				|| value.getClass() != this.value.getClass())
		{
			// If the 2 variables aren't the same type
			// or the input is missing
			// then it will be invalid
			return EConfigValidity.INVALID;
		}
		else if (value instanceof Number)
		{ 
			// Only check min/max if this config's type is a number
			if (max != null 
				&& NumberUtil.greaterThan((Number) value, (Number) max))
			{
				return EConfigValidity.NUMBER_TOO_HIGH;
			}
			
			if (min != null 
				&& NumberUtil.lessThan((Number) value, (Number) min))
			{
				return EConfigValidity.NUMBER_TOO_LOW;
			}
			
			return EConfigValidity.VALID;
		}
		else
		{
			return EConfigValidity.VALID;
		}
	}
	
	//endregion
	
	
	
	//===============//
	// file handling //
	//===============//
	//region
	
	/** This should normally not be called since set() automatically calls this */
	public void save() { ConfigHandler.INSTANCE.configFileHandler.saveEntry(this); }
	/** This should normally not be called except for special circumstances */
	public void load() { ConfigHandler.INSTANCE.configFileHandler.loadEntry(this); }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override 
	public String toString() { return this.name + ": [" + this.get() + "]"; }
	
	public boolean equals(AbstractConfigBase<?> obj) 
	{
		return obj.getClass() == ConfigEntry.class 
				&& this.equals((ConfigEntry<?>) obj); 
	}
	/** Is the value of this equal to another */
	public boolean equals(ConfigEntry<?> obj)
	{
		// Can all of this just be "return this.value.equals(obj.value)"?
		
		if (Number.class.isAssignableFrom(this.value.getClass()))
		{
			return this.value == obj.value;
		}
		else
		{
			return this.value.equals(obj.value);
		}
	}
	
	//endregion
	
	
	
	//=========//
	// builder //
	//=========//
	//region
	
	public static class Builder<T> extends AbstractConfigBase.Builder<T, Builder<T>>
	{
		private String tmpComment = null;
		private T tmpMin = null;
		private T tmpMax = null;
		protected String tmpChatCommandName = null;
		protected IShowEnumOptionFunc tmpShowEnumOptionFunc = null;
		private boolean tmpUseApiOverwrite = true;
		protected ArrayList<IConfigListener> tmpIConfigListener = new ArrayList<>();
		
		
		
		public Builder<T> comment(String newComment)
		{
			this.tmpComment = newComment;
			return this;
		}
		
		/** Allows most values to be set by 1 setter */
		public Builder<T> setMinDefaultMax(T newMin, T newDefault, T newMax)
		{
			this.set(newDefault);
			this.setMinMax(newMin, newMax);
			return this;
		}
		
		public Builder<T> setMinMax(T newMin, T newMax)
		{
			this.tmpMin = newMin;
			this.tmpMax = newMax;
			return this;
		}
		
		public Builder<T> setMin(T newMin)
		{
			this.tmpMin = newMin;
			return this;
		}
		
		public Builder<T> setMax(T newMax)
		{
			this.tmpMax = newMax;
			return this;
		}
		
		public Builder<T> setChatCommandName(String name)
		{
			this.tmpChatCommandName = name;
			return this;
		}
		
		public Builder<T> setShowEnumOptionFunc(IShowEnumOptionFunc func)
		{
			this.tmpShowEnumOptionFunc = func;
			return this;
		}
		
		public Builder<T> setUseApiOverwrite(boolean newUseApiOverwrite)
		{
			this.tmpUseApiOverwrite = newUseApiOverwrite;
			return this;
		}
		
		
		
		public Builder<T> replaceListeners(ArrayList<IConfigListener> newConfigListener)
		{
			this.tmpIConfigListener = newConfigListener;
			return this;
		}
		
		public Builder<T> addListeners(IConfigListener... newConfigListener)
		{
			this.tmpIConfigListener.addAll(Arrays.asList(newConfigListener));
			return this;
		}
		
		public Builder<T> addListener(IConfigListener newConfigListener)
		{
			this.tmpIConfigListener.add(newConfigListener);
			return this;
		}
		
		public Builder<T> clearListeners()
		{
			this.tmpIConfigListener.clear();
			return this;
		}
		
		
		
		// build //
		
		public ConfigEntry<T> build()
		{
			return new ConfigEntry<>(
				this.tmpAppearance,
				this.tmpComment, this.tmpChatCommandName, 
				this.tmpShowEnumOptionFunc,
				this.tmpValue, this.tmpMin, this.tmpMax,
				this.tmpUseApiOverwrite, 
				this.tmpIConfigListener);
		}
		
	}
	
	//endregion
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	@FunctionalInterface
	public interface IShowEnumOptionFunc
	{
		boolean shouldShowEnum(Enum<?> enumValue);
	}
	
	//endregion
	
	
}
