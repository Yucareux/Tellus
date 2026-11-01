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

package com.seibel.distanthorizons.core.config.api.converters;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.coreapi.interfaces.config.IConverter;

/**
 * Used for supporting the new {@link EDhApiGeneratorPlan}.
 */
public class ApiGeneratorModeToBoolConverter implements IConverter<EDhApiGeneratorPlan, Boolean>
{
	
	@Override 
	public EDhApiGeneratorPlan convertToCoreType(Boolean genEnabled)
	{
		if (genEnabled)
		{
			return EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS;
		}
		else
		{
			return EDhApiGeneratorPlan.DISABLED;	
		}
	}
	
	@Override 
	public Boolean convertToApiType(EDhApiGeneratorPlan genMode)
	{ return genMode.generationEnabled; }
	
	
	
}
