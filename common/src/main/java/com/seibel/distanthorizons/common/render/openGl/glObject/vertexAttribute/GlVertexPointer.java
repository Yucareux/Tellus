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

package com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute;

import com.seibel.distanthorizons.coreapi.util.MathUtil;
import org.lwjgl.opengl.GL11;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public final class GlVertexPointer
{
	public final int elementCount;
	public final int glType;
	public final boolean normalized;
	public final int byteSize;
	public final boolean useInteger;
	
	
	
	// basic constructors //
	
	public GlVertexPointer(int elementCount, int glType, boolean normalized, int byteSize, boolean useInteger)
	{
		this.elementCount = elementCount;
		this.glType = glType;
		this.normalized = normalized;
		this.byteSize = byteSize;
		this.useInteger = useInteger;
	}
	public GlVertexPointer(int elementCount, int glType, boolean normalized, int byteSize)
	{
		this(elementCount, glType, normalized, byteSize, false);
	}
	private static int _align(int bytes) { return MathUtil.ceilDiv(bytes, 4) * 4; }
	
	
	
	// named constructors //
	
	public static GlVertexPointer addFloatPointer(boolean normalized) { return new GlVertexPointer(1, GL11.GL_FLOAT, normalized, Float.BYTES); }
	public static GlVertexPointer addVec2Pointer(boolean normalized) { return new GlVertexPointer(2, GL11.GL_FLOAT, normalized, Float.BYTES * 2); }
	public static GlVertexPointer addVec3Pointer(boolean normalized) { return new GlVertexPointer(3, GL11.GL_FLOAT, normalized, Float.BYTES * 3); }
	public static GlVertexPointer addVec4Pointer(boolean normalized) { return new GlVertexPointer(4, GL11.GL_FLOAT, normalized, Float.BYTES * 4); }
	/** Always aligned to 4 bytes */
	public static GlVertexPointer addUnsignedBytePointer(boolean normalized, boolean useInteger) { return new GlVertexPointer(1, GL11.GL_UNSIGNED_BYTE, normalized, 4, useInteger); }
	/** aligned to 4 bytes */
	public static GlVertexPointer addUnsignedBytesPointer(int elementCount, boolean normalized, boolean useInteger) 
	{ return new GlVertexPointer(elementCount, GL11.GL_UNSIGNED_BYTE, normalized, _align(elementCount), useInteger); }
	public static GlVertexPointer addUnsignedShortsPointer(int elementCount, boolean normalized, boolean useInteger) 
	{ return new GlVertexPointer(elementCount, GL11.GL_UNSIGNED_SHORT, normalized, _align(elementCount * 2), useInteger); }
	public static GlVertexPointer addShortsPointer(int elementCount, boolean normalized, boolean useInteger) { return new GlVertexPointer(elementCount, GL11.GL_SHORT, normalized, _align(elementCount * 2), useInteger); }
	public static GlVertexPointer addIntPointer(boolean normalized, boolean useInteger) { return new GlVertexPointer(1, GL11.GL_INT, normalized, 4, useInteger); }
	public static GlVertexPointer addIVec2Pointer(boolean normalized, boolean useInteger) { return new GlVertexPointer(2, GL11.GL_INT, normalized, 8, useInteger); }
	public static GlVertexPointer addIVec3Pointer(boolean normalized, boolean useInteger) { return new GlVertexPointer(3, GL11.GL_INT, normalized, 12, useInteger); }
	public static GlVertexPointer addIVec4Pointer(boolean normalized, boolean useInteger) { return new GlVertexPointer(4, GL11.GL_INT, normalized, 16, useInteger); }
	
}
