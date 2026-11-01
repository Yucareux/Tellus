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

package com.seibel.distanthorizons.api.enums.config;

/**
 * FULL <br>
 * NORMAL <br>
 * OFF <br><br>
 *
 * Based on SQLite's Synchronization Pragma:
 * <a href="https://www.sqlite.org/pragma.html#pragma_synchronous">https://www.sqlite.org/pragma.html#pragma_synchronous</a>
 *
 * @version 2026-8-22
 * @since API 7.1.0
 */
public enum EDhApiDatabaseSyncMode
{
	/** 
	 * ACID - data will never be lost or corrupted <Br>
	 * slow <Br>
	 * At max throughput: ~2k saves per sec, ~70% disk usage 
	 */
	FULL(2),
	/** 
	 * May not be durable - some data may be lost on power loss, but DB should be safe from corruption. <Br>
	 * fast <Br>
	 * At max throughput: ~14k saves per sec, ~40% disk usage 
	 */
	NORMAL(1),
	/** 
	 * Not consistent - database may be corrupted during power loss <Br>
	 * faster <Br>
	 * At max throughput: ~16k saves per sec, ~6% disk usage 
	 */
	OFF(0);
	
	
	
	/** More stable than using the ordinal of the enum */
	public final byte value;
	
	EDhApiDatabaseSyncMode(int value) { this.value = (byte) value; }
	
	
	public static EDhApiDatabaseSyncMode getFromValue(byte value)
	{
		EDhApiDatabaseSyncMode[] enumList = EDhApiDatabaseSyncMode.values();
		for (int i = 0; i < enumList.length; i++)
		{
			if (enumList[i].value == value)
			{
				return enumList[i];
			}
		}
		
		throw new IllegalArgumentException("No sync mode with the value ["+value+"]");
	}
	
	
}
