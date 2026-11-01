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

package com.seibel.distanthorizons.common.render.openGl.glObject.buffer;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.enums.GLEnums;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

/** aka GlQuadElementBuffer */
public class GlQuadIndexBuffer extends GLIndexBuffer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlQuadIndexBuffer() { super(false); }
	
	public void upload(ByteBuffer buffer, int quadCount)
	{
		if (quadCount < 0)
		{
			throw new IllegalArgumentException("quadCount must be greater than 0");
		}
		if (quadCount == 0)
		{
			// shouldn't happen, but just in case
			return;
		}
		
		this.indicesCount = quadCount * 6; // 2 triangles per quad
		if (this.indicesCount >= this.getCapacity()
			&& this.indicesCount < this.getCapacity() * BUFFER_SHRINK_TRIGGER)
		{
			return;
		}
		
		this.glType = GL11.GL_UNSIGNED_INT;
		super.uploadBuffer(buffer, EDhApiGpuUploadMethod.DATA,
			this.indicesCount * GLEnums.getTypeSize(this.glType), GL15.GL_STATIC_DRAW);
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public int getCapacity() { return super.getSize() / GLEnums.getTypeSize(this.getGlType()); }
	
	//endregion
	
	
	
}
