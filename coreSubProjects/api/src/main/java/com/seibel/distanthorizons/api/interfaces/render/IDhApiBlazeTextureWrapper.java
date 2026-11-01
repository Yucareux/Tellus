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

package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.IDhApiUnsafeWrapper;

/**
 * Holds a Blaze3D texture.
 * 
 * @author James Seibel
 * @version 2026-07-11
 * @since API 7.1.0
 */
public interface IDhApiBlazeTextureWrapper extends IDhApiUnsafeWrapper
{
	/** 
	 * Roughly describes the purpose of this texture. <br/>
	 * Not guaranteed to be unique. 
	 */
	String getName();
	
	/** @return -1 if the texture is null */
	int getWidth();
	/** @return -1 if the texture is null */
	int getHeight();
	
	
}
