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

package com.seibel.distanthorizons.common.render.openGl;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLIndexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles rendering the wireframe particles 
 * that are used for seeing what the system's doing.
 */
public class GlDhDebugWireframeRenderer extends AbstractDebugWireframeRenderer
{
	public static GlDhDebugWireframeRenderer INSTANCE = new GlDhDebugWireframeRenderer();
	
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	
	// rendering setup
	private GlShaderProgram basicShader;
	private GLVertexBuffer vertexBuffer;
	private GLIndexBuffer indexBuffer;
	private GlAbstractVertexAttribute va;
	private boolean init = false;
	
	
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
		//region
			// Pos x y z
			0, 0, 0,
			1, 0, 0,
			1, 1, 0,
			0, 1, 0,
			0, 0, 1,
			1, 0, 1,
			1, 1, 1,
			0, 1, 1,
		//endregion
	};

	private static final int[] BOX_OUTLINE_INDICES = {
		//region
			0, 1,
			1, 2,
			2, 3,
			3, 0,

			4, 5,
			5, 6,
			6, 7,
			7, 4,

			0, 4,
			1, 5,
			2, 6,
			3, 7,
		//endregion
	};
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlDhDebugWireframeRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;

		this.va = GlAbstractVertexAttribute.create();
		this.va.bind();
		// Pos
		this.va.setVertexAttribute(0, 0, GlVertexPointer.addVec3Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 3);
		this.basicShader = new GlShaderProgram(
			"assets/distanthorizons/shaders/debug/gl/vert.vert",
			"assets/distanthorizons/shaders/debug/gl/frag.frag",
			"vPosition"
		);
		this.createBuffer();
	}
	
	private void createBuffer()
	{
		// box vertices 
		ByteBuffer boxVerticesBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		boxVerticesBuffer.order(ByteOrder.nativeOrder());
		boxVerticesBuffer.asFloatBuffer().put(BOX_VERTICES);
		boxVerticesBuffer.rewind();
		this.vertexBuffer = new GLVertexBuffer(false);
		this.vertexBuffer.bind();
		this.vertexBuffer.uploadBuffer(boxVerticesBuffer, 8, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
		
		
		// outline vertex indexes
		ByteBuffer boxOutlineBuffer = ByteBuffer.allocateDirect(BOX_OUTLINE_INDICES.length * Integer.BYTES);
		boxOutlineBuffer.order(ByteOrder.nativeOrder());
		boxOutlineBuffer.asIntBuffer().put(BOX_OUTLINE_INDICES);
		boxOutlineBuffer.rewind();
		this.indexBuffer = new GLIndexBuffer(false);
		this.indexBuffer.uploadBuffer(boxOutlineBuffer, EDhApiGpuUploadMethod.DATA, BOX_OUTLINE_INDICES.length * Integer.BYTES, GL15.GL_STATIC_DRAW);
		
	}
	
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		this.init();
		
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
		GLMC.enableDepthTest();
		
		this.basicShader.bind();
		this.va.bind();
		this.va.bindBufferToAllBindingPoints(this.vertexBuffer.getId());
		
		this.indexBuffer.bind();
		
		super.render(renderParams);
		
		// revert to prevent issues with the following passes
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
	}
	
	@Override
	public void renderBox(Box box)
	{
		DhMat4f boxTransform = DhMat4f.createTranslateMatrix(box.minPos.x - this.camPosFloatThisFrame.x, box.minPos.y - this.camPosFloatThisFrame.y, box.minPos.z - this.camPosFloatThisFrame.z);
		boxTransform.multiply(DhMat4f.createScaleMatrix(box.maxPos.x - box.minPos.x, box.maxPos.y - box.minPos.y, box.maxPos.z - box.minPos.z));

		DhMat4f transformMatrix = this.dhMvmProjMatrixThisFrame.copy();
		transformMatrix.multiply(boxTransform);
		this.basicShader.setUniform(this.basicShader.getUniformLocation("uTransform"), transformMatrix);

		this.basicShader.setUniform(this.basicShader.getUniformLocation("uColor"), box.color);

		LWJGL.glDrawElements(GL11.GL_LINES, BOX_OUTLINE_INDICES.length, GL11.GL_UNSIGNED_INT, 0);
	}
	
	//endregion
	
	
	
}
