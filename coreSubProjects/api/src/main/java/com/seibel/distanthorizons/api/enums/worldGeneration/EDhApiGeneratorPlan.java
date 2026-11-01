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

package com.seibel.distanthorizons.api.enums.worldGeneration;

/**
 * SURFACE_THEN_CHUNK <br>
 * SURFACE_ONLY <br>
 * DISABLED <br>
 * CHUNK_ONLY <br><br>
 *
 * @author James Seibel
 * @version 2026-09-13
 * @since API 7.1.0
 */
public enum EDhApiGeneratorPlan
{
	/** 
	 * The rough surface will be generated first 
	 * then chunks will be generated to fill in
	 * missing features (ie trees and villages). <Br><br>
	 * 
	 * Recommended for modded or vanilla worlds.
	 */
	SURFACE_THEN_CHUNKS(true, true, true),
	
	/**
	 * Only the rough surface will be generated.
	 * Chunk features like trees and villages
	 * won't be generated. <Br><br>
	 * 
	 * Recommended for computers that don't have
	 * enough CPU power or RAM space to handle
	 * the chunk generator.
	 */
	SURFACE_ONLY(true, false, true),
	
	/**
	 * Slower than either surface options,
	 * but provides more accurate terrain. <Br><br>
	 * 
	 * Recommended for custom worlds
	 * where the surface generator would
	 * generate incorrect terrain.
	 */
	CHUNKS_ONLY(true, true, false),
	
	/**
	 * No distant terrain will be generated.
	 */
	DISABLED(false, false, false);
	
	
	/** if true then some sort of generation will happen */
	public final boolean generationEnabled;
	public final boolean chunkGenEnabled;
	public final boolean surfaceGenEnabled;
	
	EDhApiGeneratorPlan(
		boolean genEnabled,
		boolean chunkGenEnabled, boolean surfaceGenEnabled) 
	{
		this.generationEnabled = genEnabled;
		this.chunkGenEnabled = chunkGenEnabled;
		this.surfaceGenEnabled = surfaceGenEnabled;
	}
	
	
}
