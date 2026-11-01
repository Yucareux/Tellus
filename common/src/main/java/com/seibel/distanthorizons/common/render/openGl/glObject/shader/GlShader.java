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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * This object holds a OpenGL reference to a shader
 * and allows for reading in and compiling a shader file.
 */
public class GlShader
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logRendererGLEventToFile)
			.chatLevelConfig(Config.Common.Logging.logRendererGLEventToChat)
			.build();
	
	
	/** OpenGL shader ID */
	public final int id;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	/**
	 * Creates a shader with specified type.
	 *
	 * @param type Either GL_VERTEX_SHADER or GL_FRAGMENT_SHADER.
	 * @param sourceString File path of the shader
	 * @throws RuntimeException if the shader fails to compile
	 */
	public GlShader(int type, String sourceString)
	{
		LOGGER.info("Loading shader with type: ["+type+"]");
		LOGGER.debug("Source: \n["+sourceString+"]");
		if (sourceString == null || sourceString.isEmpty())
		{
			throw new IllegalArgumentException("No shader source given.");
		}
		
		// Create an empty shader object
		this.id = LWJGL.glCreateShader(type);
		if (this.id == 0)
		{
			throw new IllegalArgumentException("Failed to create shader with type ["+type+"] and Source: \n["+sourceString+"].");
		}
		
		LWJGL.glShaderSourceSafe(this.id, sourceString);
		LWJGL.glCompileShader(this.id);
		// check if the shader compiled
		int status = LWJGL.glGetShaderi(this.id, GL20.GL_COMPILE_STATUS);
		if (status != GL11.GL_TRUE)
		{
			
			String message = "Shader compiler error. Details: [" + LWJGL.glGetShaderInfoLog(this.id, 1024) + "]\n";
			message += "Source: \n[" + sourceString + "]";
			this.free(); // important!
			throw new RuntimeException(message);
		}
		LOGGER.info("Shader loaded sucessfully.");
	}
	
	//endregion
	
	
	
	//=========//
	// helpers //
	//=========//
	//region
	
	public void free() { LWJGL.glDeleteShader(this.id); }
	
	public static String loadFile(String path, boolean absoluteFilePath)
	{
		StringBuilder stringBuilder = new StringBuilder();
		
		try
		{
			// open the file
			InputStream in;
			if (absoluteFilePath)
			{
				// Throws FileNotFoundException
				in = new FileInputStream(path); // Note: this should use OS path seperator
			}
			else
			{
				in = GlShader.class.getClassLoader().getResourceAsStream(path); // Note: path seperator should be '/'
				if (in == null)
				{
					throw new FileNotFoundException("Shader file not found in resource: " + path);
				}
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			
			// read in the file
			String line;
			while ((line = reader.readLine()) != null)
			{
				stringBuilder.append(line).append("\n");
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException("Unable to load shader from file [" + path + "]. Error: " + e.getMessage());
		}
		
		return stringBuilder.toString();
	}
	
	//endregion
	
	
	
}
