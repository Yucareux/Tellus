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

import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Base for binding/unbinding Vertex Attribute objects (VAO's).
 * 
 * @see GlVertexAttributePostGL43
 * @see GlVertexAttributePreGL43
 */
public abstract class GlAbstractVertexAttribute
{
	/** Stores the handle of the AbstractVertexAttribute. */
	public final int id;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	// This will bind AbstractVertexAttribute
	protected GlAbstractVertexAttribute()
	{
		this.id = LWJGL.glGenVertexArrays();
		LWJGL.glBindVertexArray(this.id);
	}
	
	public static GlAbstractVertexAttribute create()
	{
		if (GLProxy.getInstance().vertexAttributeBufferBindingSupported)
		{
			return new GlVertexAttributePostGL43();
		}
		else
		{
			return new GlVertexAttributePreGL43();
		}
	}
	
	
	
	//=========//
	// binding //
	//=========//
	
	public void bind() { LWJGL.glBindVertexArray(this.id); }
	public void unbind() { LWJGL.glBindVertexArray(0); }
	
	/** Always remember to always free your resources! */
	public void free() { LWJGL.glDeleteVertexArrays(this.id); }
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	/** Requires both AbstractVertexAttribute and VertexBuffer to be bound */
	public abstract void bindBufferToAllBindingPoints(int buffer);
	/** Requires both AbstractVertexAttribute and VertexBuffer to be bound */
	public abstract void bindBufferToBindingPoint(int buffer, int bindingPoint);
	/** Requires both AbstractVertexAttribute to be bound */
	public abstract void unbindBuffersFromAllBindingPoint();
	/** Requires both AbstractVertexAttribute to be bound */
	public abstract void unbindBuffersFromBindingPoint(int bindingPoint);
	/** Requires both AbstractVertexAttribute to be bound */
	public abstract void setVertexAttribute(int bindingPoint, int attributeIndex, GlVertexPointer attribute);
	/** Requires both AbstractVertexAttribute to be bound */
	public abstract void completeAndCheck(int expectedStrideSize);
	
}
