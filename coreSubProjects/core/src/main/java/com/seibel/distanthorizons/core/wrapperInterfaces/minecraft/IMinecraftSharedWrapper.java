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

package com.seibel.distanthorizons.core.wrapperInterfaces.minecraft;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface IMinecraftSharedWrapper extends IBindable
{
	boolean isDedicatedServer();
	
	File getInstallationDirectory();
	
	int getPlayerCount();
	
	/** If used on the client will only return a non-null object if the client is hosting a LAN server */
	@Nullable
	IServerLevelWrapper getLevelWrapper(String dimensionResourceLocation);
	
	/** returns true if MC's server thread is running in an acceptable amount of time */
	boolean isServerThreadHealthy();
	
	/** returns true if this MC version supports surface world generation */
	boolean supportsSurfaceGeneration();
	
	
}
