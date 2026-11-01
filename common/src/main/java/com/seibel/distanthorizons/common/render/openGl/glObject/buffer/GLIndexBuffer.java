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

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * AKA the GLElementBuffer
 *
 * @author James Seibel
 * @version 11-20-2021
 */
public class GLIndexBuffer extends GLBuffer
{
	/**
	 * When uploading to a buffer that is too small, recreate it this many times
	 * bigger than the upload payload
	 */
	protected int indicesCount = 0;
	protected int glType = GL11.GL_UNSIGNED_INT;
	public int getGlType() { return this.glType; }
	
	
	
	public GLIndexBuffer(boolean isBufferStorage) { super(isBufferStorage); }
	
	
	
	@Override
	public void destroyAsync()
	{
		super.destroyAsync();
		this.indicesCount = 0;
	}
	
	@Override
	public int getBufferBindingTarget() { return GL15.GL_ELEMENT_ARRAY_BUFFER; }
	
}