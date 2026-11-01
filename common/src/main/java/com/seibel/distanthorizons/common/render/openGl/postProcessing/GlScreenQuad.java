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

package com.seibel.distanthorizons.common.render.openGl.postProcessing;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import org.lwjgl.opengl.GL11;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders a full-screen textured quad to the screen. 
 * Used in composite / deferred rendering (IE fog).
 */
public class GlScreenQuad
{
	public static GlScreenQuad INSTANCE = new GlScreenQuad();
	
	private static final float[] BOX_VERTICES = {
			-1, -1,
			1, -1,
			1, 1,
		
			-1, -1,
			1, 1,
			-1, 1,
	};
	
	private GLVertexBuffer boxBuffer;
	private GlAbstractVertexAttribute va;
	private boolean init = false;

	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlScreenQuad() { }
	
	public void init()
	{
		if (this.init) return;
		this.init = true;
		
		this.va = GlAbstractVertexAttribute.create();
		this.va.bind();
		
		// Pos
		this.va.setVertexAttribute(0, 0, GlVertexPointer.addVec2Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 2);
		
		// Framebuffer
		this.createBuffer();
	}
	private void createBuffer()
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		buffer.order(ByteOrder.nativeOrder());
		buffer.asFloatBuffer().put(BOX_VERTICES);
		buffer.rewind();
		
		this.boxBuffer = new GLVertexBuffer(false);
		this.boxBuffer.bind();
		this.boxBuffer.uploadBuffer(buffer, BOX_VERTICES.length, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void render()
	{
		this.init();
		
		this.boxBuffer.bind();
		
		this.va.bind();
		this.va.bindBufferToAllBindingPoints(this.boxBuffer.getId());
		
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
		
		LWJGL.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
		
		this.boxBuffer.unbind();
	}
	
	//endregion
	
	
	
}
