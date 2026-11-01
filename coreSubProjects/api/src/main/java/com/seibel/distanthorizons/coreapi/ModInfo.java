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

package com.seibel.distanthorizons.coreapi;

/**
 * This file is similar to mcmod.info
 * and contains most meta-information related to Distant Horizons.
 */
public final class ModInfo
{
	public static final String ID = "distanthorizons";
	
	public static final String RESOURCE_NAMESPACE = "distant_horizons";
	public static final String DEDICATED_SERVER_INITIAL_PATH = "dedicated_server_initial";
	
	/** Incremented every time any packets are added, changed or removed, with a few exceptions. */
	public static final int PROTOCOL_VERSION = 16;

	/** 
	 * The full plugin channel name (RESOURCE_NAMESPACE:WRAPPER_PACKET_PATH) 
	 * must be 20 characters or fewer for compatibility with MC 1.13 and older. 
	 */
	public static final String WRAPPER_PACKET_PATH = "msg";
	
	/** The internal mod name */
	public static final String NAME = "DistantHorizons";
	/** Human-readable version of NAME */
	public static final String READABLE_NAME = "Distant Horizons";
	public static final String VERSION = "3.3.4-tellus.1";
	/** Returns true if the current build is an unstable developer build, false otherwise. */
	public static final boolean IS_DEV_BUILD = VERSION.toLowerCase().contains("dev");
	
	/** This version should only be updated when breaking changes are introduced to the DH API */
	public static final int API_MAJOR_VERSION = 7;
	/** This version should be updated whenever new methods are added to the DH API */
	public static final int API_MINOR_VERSION = 2;
	/** This version should be updated whenever non-breaking fixes are added to the DH API */
	public static final int API_PATCH_VERSION = 0;
	
	/** If the config file has an older version it'll be re-created from scratch. */
	public static final int CONFIG_FILE_VERSION = 5;
	
	/** All DH owned threads should start with this string to allow for easier debugging and profiling. */
	public static final String THREAD_NAME_PREFIX = "DH-";
	
	
	
}
