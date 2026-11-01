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

package com.seibel.distanthorizons.common.render.openGl.test;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.apply.GlDhApplyShader;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTestTriangleRenderer;
import org.lwjgl.opengl.GL11;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders a UV colored quad
 * to the center of the screen to confirm DH's
 * apply shader is running correctly
 */
public class GlTestTriangleRenderer implements IDhTestTriangleRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build(); 
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	public static final GlTestTriangleRenderer INSTANCE = new GlTestTriangleRenderer();
	
	// Render a square with uv color
	private static final float[] VERTICES =
	{
		// PosX,Y,    ColorR,G,B,A
		-0.5f, -0.5f,   1.0f, 0.0f, 0.0f, 1.0f,
		0.5f, -0.5f,   0.0f, 1.0f, 0.0f, 1.0f,
		0.0f,  0.5f,   0.0f, 0.0f, 1.0f, 1.0f,
	};
	
	
	
	GlShaderProgram basicShader;
	GLVertexBuffer vbo;
	GlAbstractVertexAttribute va;
	boolean init = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlTestTriangleRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		
		LOGGER.info("init");
		this.init = true;
		this.va = GlAbstractVertexAttribute.create();
		this.va.bind();
		// Pos
		this.va.setVertexAttribute(0, 0, GlVertexPointer.addVec2Pointer(false));
		// Color
		this.va.setVertexAttribute(0, 1, GlVertexPointer.addVec4Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 6);
		this.basicShader = new GlShaderProgram(
			"assets/distanthorizons/shaders/test/gl/vert.vert",
			"assets/distanthorizons/shaders/test/gl/frag.frag",
			new String[]{"vPosition", "color"});
		
		this.createBuffer();
	}
	
	private void createBuffer()
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(VERTICES.length * Float.BYTES);
		// Fill buffer with vertices.
		buffer.order(ByteOrder.nativeOrder());
		buffer.asFloatBuffer().put(VERTICES);
		buffer.rewind();
		
		this.vbo = new GLVertexBuffer(false);
		this.vbo.bind();
		this.vbo.uploadBuffer(buffer, 3, EDhApiGpuUploadMethod.DATA, VERTICES.length * Float.BYTES);
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		this.init();
		
		this.basicShader.bind();
		this.va.bind();
		
		this.vbo.bind();
		this.va.bindBufferToAllBindingPoints(this.vbo.getId());
		
		LWJGL.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		
		GlDhApplyShader.INSTANCE.render(renderParams);
	}
	
	//endregion
	
	
	
}
