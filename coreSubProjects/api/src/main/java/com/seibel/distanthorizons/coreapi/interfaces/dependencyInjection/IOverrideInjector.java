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

package com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection;


import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;

public interface IOverrideInjector<BindableType extends IBindable>
{
	/**
	 * All core overrides should have this priority. <Br>
	 * Should be lower than {@link IOverrideInjector#MIN_NON_CORE_OVERRIDE_PRIORITY}.
	 */
	int CORE_PRIORITY = -1;
	/**
	 * The lowest priority non-core overrides can have.
	 * Should be higher than {@link IOverrideInjector#CORE_PRIORITY}.
	 */
	int MIN_NON_CORE_OVERRIDE_PRIORITY = 0;
	/** The priority given to overrides that don't explicitly define a priority. */
	int DEFAULT_NON_CORE_OVERRIDE_PRIORITY = 10;
	
	
	
	/**
	 * Links the given implementation object to an interface, so it can be referenced later. <br>
	 * If another override with the given priority already has been bound it will be silently replaced
	 * by this new override.
	 *
	 * @param dependencyInterface The interface (or parent class) the implementation object should implement.
	 * @param dependencyImplementation An object that implements the dependencyInterface interface.
	 * 
	 * @throws IllegalArgumentException if a non-Distant Horizons Override with the priority {@link IOverrideInjector#CORE_PRIORITY} is passed in
	 * or an override is passed in with an invalid priority value.
	 * 
	 * @apiNote since 7.1.0 IllegalStateException no longer throws if another override is already bound with the same priority.
	 * 
	 * @see IDependencyInjector#bind(Class, IBindable)
	 */
	void bind(Class<? extends IDhApiOverrideable> dependencyInterface, IDhApiOverrideable dependencyImplementation) throws IllegalStateException, IllegalArgumentException;
	
	/**
	 * Returns the bound dependency with the highest priority. <br>
	 * See {@link IDependencyInjector#get(Class, boolean) get(Class, boolean)} for full documentation.
	 *
	 * @see IDependencyInjector#get(Class, boolean)
	 */
	<T extends IDhApiOverrideable> T get(Class<T> interfaceClass) throws ClassCastException;
	
	/**
	 * Returns a dependency of type T with the specified priority if one has been bound. <br>
	 * If there is a dependency, but it was bound with a different priority this will return null. <br> <br>
	 *
	 * See {@link IDependencyInjector#get(Class, boolean) get(Class, boolean)} for more documentation.
	 *
	 * @see IDependencyInjector#get(Class, boolean)
	 */
	<T extends IDhApiOverrideable> T get(Class<T> interfaceClass, int priority) throws ClassCastException;
	
	/** Removes the given concrete {@link IDhApiOverrideable} bound to the given interface. */
	void unbind(Class<? extends IDhApiOverrideable> dependencyInterface, IDhApiOverrideable dependencyImplementation);
	
	/** 
	 * Removes all bound implementations of the given interface.
	 * 
	 * @since API 7.1.0 
	 */
	void unbindAll(Class<? extends IDhApiOverrideable> dependencyInterface);
	
	/** Removes all bound overrides. */
	void clear();
	
	
}
