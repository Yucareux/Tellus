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

package com.seibel.distanthorizons.common.render.openGl.glObject.shader;

import java.awt.Color;
import java.nio.FloatBuffer;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import com.seibel.distanthorizons.lwjgl.AbstractMemoryStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3f;


/**
 * This object holds the reference to a OpenGL shader program
 * and contains a few methods that can be used with OpenGL shader programs.
 * The reason for many of these simple wrapper methods is as reminders of what
 * can (and needs to be) done with a shader program.
 */
public class GlShaderProgram
{
	/** Stores the handle of the program. */
	public final int id;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlShaderProgram(String vertResourcePath, String fragResourcePath, String attribute) { this(vertResourcePath, fragResourcePath, new String[]{ attribute }); }
	/**
	 * @param vertResourcePath the relative path the vertex shader should be found 
	 * @param fragResourcePath the relative path the fragment shader should be found 
	 */
	public GlShaderProgram(String vertResourcePath, String fragResourcePath, String[] attributes)
	{
		this.id = LWJGL.glCreateProgram();
		
		{
			String shaderString = GlShader.loadFile(vertResourcePath, false);
			GlShader vertShader = new GlShader(GL20.GL_VERTEX_SHADER, shaderString);
			LWJGL.glAttachShader(this.id, vertShader.id);
			vertShader.free();
		}
		
		{
			String shaderString = GlShader.loadFile(fragResourcePath, false);
			GlShader fragShader = new GlShader(GL20.GL_FRAGMENT_SHADER, shaderString);
			LWJGL.glAttachShader(this.id, fragShader.id);
			fragShader.free();
		}
		
		for (int i = 0; i < attributes.length; i++)
		{
			LWJGL.glBindAttribLocation(this.id, i, attributes[i]);
		}
		LWJGL.glLinkProgram(this.id);
		
		int status = LWJGL.glGetProgrami(this.id, GL20.GL_LINK_STATUS);
		if (status != GL11.GL_TRUE)
		{
			String message = "Shader Link Error. Details: " + LWJGL.glGetProgramInfoLog(this.id,1024);
			this.free(); // important!
			throw new RuntimeException(message);
		}
		LWJGL.glUseProgram(this.id); // This HAVE to be a direct call to prevent calling the overloaded version
	}
	
	//endregion
	
	
	
	//=========//
	// binding //
	//=========//
	//region
	
	public void bind() { LWJGL.glUseProgram(this.id); }
	public void unbind() { LWJGL.glUseProgram(0); }
	
	public void free() { LWJGL.glDeleteProgram(this.id); }
	
	//endregion
	
	
	
	//============//
	// attributes //
	//============//
	//region
	
	/**
	 * WARNING: Slow native call! Cache it if possible!
	 * Gets the location of an attribute variable with specified name.
	 * Calls GL20.glGetAttribLocation(id, name)
	 *
	 * @param name Attribute name
	 * @return Location of the attribute
	 * @throws RuntimeException if attribute not found
	 */
	public int getAttributeLocation(CharSequence name)
	{
		int i = LWJGL.glGetAttribLocation(id, name);
		if (i == -1) throw new RuntimeException("Attribute name not found: " + name);
		return i;
	}
	/**
	 * Same as above but without throwing errors. <br>
	 * Returns -1 if the attribute doesn't exist or has been optimized out.
	 */
	public int tryGetAttributeLocation(CharSequence name)
	{ return LWJGL.glGetAttribLocation(this.id, name); }
	
	//endregion
	
	
	
	//==========//
	// uniforms //
	//==========//
	//region
	
	/**
	 * WARNING: Slow native call! Cache it if possible!
	 * Gets the location of a uniform variable with specified name.
	 * Calls GL20.glGetUniformLocation(id, name)
	 *
	 * @param name Uniform name
	 * @return Location of the Uniform
	 * @throws RuntimeException if uniform not found
	 */
	public int getUniformLocation(CharSequence name) throws RuntimeException
	{
		int i = LWJGL.glGetUniformLocation(id, name);
		if (i == -1)
		{
			throw new RuntimeException("Uniform name not found: " + name);
		}
		return i;
	}
	
	// Same as above but without throwing errors.
	// Return -1 if uniform doesn't exist or has been optimized out
	public int tryGetUniformLocation(CharSequence name)
	{ return LWJGL.glGetUniformLocation(this.id, name); }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, boolean value) { LWJGL.glUniform1i(location, value ? 1 : 0); }
	/** @see GlShaderProgram#setUniform(int, boolean) */
	public void trySetUniform(int location, boolean value) { if (location != -1) { this.setUniform(location, value); } }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, int value) { LWJGL.glUniform1i(location, value); }
	/** @see GlShaderProgram#setUniform(int, int) */
	public void trySetUniform(int location, int value) { if (location != -1) { this.setUniform(location, value); } }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, float value) { LWJGL.glUniform1f(location, value); }
	/** @see GlShaderProgram#setUniform(int, float) */
	public void trySetUniform(int location, float value) { if (location != -1) { this.setUniform(location, value); } }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, DhVec3f value) { LWJGL.glUniform3f(location, value.x, value.y, value.z); }
	/** @see GlShaderProgram#setUniform(int, DhVec3f) */
	public void trySetUniform(int location, DhVec3f value) { if (location != -1) { this.setUniform(location, value); } }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, DhApiVec3i value) { LWJGL.glUniform3i(location, value.x, value.y, value.z); }
	/** @see GlShaderProgram#setUniform(int, DhApiMat4f) */
	public void trySetUniform(int location, DhApiVec3i value) { if (location != -1) { this.setUniform(location, value); } }
	
	/** Requires a bound ShaderProgram. */
	public void setUniform(int location, DhApiMat4f value)
	{
		try (AbstractMemoryStack stack = AbstractMemoryStack.stackPush())
		{
			FloatBuffer buffer = stack.mallocFloat(16);
			storeMatrixInBuffer(value, buffer);
			LWJGL.glUniformMatrix4fv(location, false, buffer);
		}
	}
	private static void storeMatrixInBuffer(DhApiMat4f matrix, FloatBuffer floatBuffer)
	{
		floatBuffer.put(bufferIndex(0, 0), matrix.m00);
		floatBuffer.put(bufferIndex(0, 1), matrix.m01);
		floatBuffer.put(bufferIndex(0, 2), matrix.m02);
		floatBuffer.put(bufferIndex(0, 3), matrix.m03);
		
		floatBuffer.put(bufferIndex(1, 0), matrix.m10);
		floatBuffer.put(bufferIndex(1, 1), matrix.m11);
		floatBuffer.put(bufferIndex(1, 2), matrix.m12);
		floatBuffer.put(bufferIndex(1, 3), matrix.m13);
		
		floatBuffer.put(bufferIndex(2, 0), matrix.m20);
		floatBuffer.put(bufferIndex(2, 1), matrix.m21);
		floatBuffer.put(bufferIndex(2, 2), matrix.m22);
		floatBuffer.put(bufferIndex(2, 3), matrix.m23);
		
		floatBuffer.put(bufferIndex(3, 0), matrix.m30);
		floatBuffer.put(bufferIndex(3, 1), matrix.m31);
		floatBuffer.put(bufferIndex(3, 2), matrix.m32);
		floatBuffer.put(bufferIndex(3, 3), matrix.m33);
	}
	private static int bufferIndex(int xIndex, int zIndex) { return (zIndex * 4) + xIndex; }
	
	/** @see GlShaderProgram#setUniform(int, DhApiMat4f) */
	public void trySetUniform(int location, DhMat4f value) { if (location != -1) { this.setUniform(location, value); } }
	
	/**
	 * Converts the color's RGBA values into values between 0 and 1. <br>
	 * Requires a bound ShaderProgram.
	 */
	public void setUniform(int location, Color value)
	{
		LWJGL.glUniform4f(location, 
				value.getRed()   / 256.0f, 
				value.getGreen() / 256.0f, 
				value.getBlue()  / 256.0f, 
				value.getAlpha() / 256.0f);
	}
	/** @see GlShaderProgram#setUniform(int, Color) */
	public void trySetUniform(int location, Color value) { if (location != -1) { this.setUniform(location, value); } }
	
	//endregion
	
	
	
}
