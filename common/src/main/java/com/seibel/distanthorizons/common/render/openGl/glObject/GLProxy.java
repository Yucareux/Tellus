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

package com.seibel.distanthorizons.common.render.openGl.glObject;

import com.seibel.distanthorizons.api.enums.config.EDhApiGLErrorHandlingMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.GLMessages.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.lwjgl.EGLExtension;
import com.seibel.distanthorizons.lwjgl.EGLFeature;
import org.lwjgl.opengl.GL11;

#if MC_VER > MC_1_12_2
import org.lwjgl.opengl.GLUtil;
#endif

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A singleton that holds references to different openGL contexts
 * and GPU capabilities.
 */
public class GLProxy
{
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	public static final DhLogger LOGGER;
	static
	{
		DhLoggerBuilder loggerBuilder = new DhLoggerBuilder();
		loggerBuilder.fileLevelConfig(Config.Common.Logging.logRendererGLEventToFile);
		
		// don't send chat messages if Iris is present since
		// Iris is known to cause (harmless) GL errors
		// and this can confuse users
		boolean irisPresent = (IRIS_ACCESSOR != null);
		if (!irisPresent)
		{
			loggerBuilder.chatLevelConfig(Config.Common.Logging.logRendererGLEventToChat);
		}
		
		LOGGER = loggerBuilder.build();
		
		if (irisPresent)
		{
			LOGGER.info("Iris detected, Distant Horizons OpenGL error logging won't be sent in the chat due to Iris throwing known (harmless) OpenGL errors. This is a bug with Iris, not Distant Horizons.");
		}
	}
	
	/** 
	 * disabled for now since GL 3.3 may not appear in the capabilities
	 * on older MC versions (James found this issue on 1.17 and 1.20.6 specifically),
	 * but may still be accessible for our needs. 
	 */
	private static final boolean CHECK_GL_VERSION_ON_STARTUP = false;
	
	public static final Set<String> LOGGED_GL_MESSAGES = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	
	
	
	private static GLProxy instance = null;
	
	
	public boolean bufferStorageSupported = false; // ~OpenGL 4.4
	public boolean vertexAttributeBufferBindingSupported = false; // ~OpenGL 4.3
	public boolean instancedArraysSupported = false;
	public boolean vertexAttribDivisorSupported = false; // OpenGL 3.3 or newer
	
	private final EDhApiGpuUploadMethod preferredUploadMethod;
	
	public final GLMessageBuilder vanillaDebugMessageBuilder = 
		new GLMessageBuilder(
			(type) ->
			{
				if (type == EGLMessageType.POP_GROUP)
					return false;
				else if (type == EGLMessageType.PUSH_GROUP)
					return false;
				else if (type == EGLMessageType.MARKER)
					return false;
				else
					return true;
			},
			(severity) ->
			{
				// notifications can generally be ignored (if they are logged at all)
				if (severity == EGLMessageSeverity.NOTIFICATION)
					return false;
				else
					return true;
			},
			null
	);
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GLProxy() throws IllegalStateException
	{
		if (RENDER_DEF.getRenderApi() != EDhApiRenderingApi.OPEN_GL)
		{
			throw new IllegalStateException("[" + GLProxy.class.getSimpleName() + "] was created with the wrong Rendering API ["+RENDER_DEF.getRenderApi()+"]!"); 
		}
		
		// this must be created on minecraft's render context to work correctly
		if (!runningOnRenderThread())
		{
			String message = "[" + GLProxy.class.getSimpleName() + "] was created outside the render thread!";
			IllegalStateException exception = new IllegalStateException(message);
			MC_CLIENT.crashMinecraft(message, exception);
			throw exception;
		}
		
		LOGGER.info("Creating [" + GLProxy.class.getSimpleName() + "]... If this is the last message you see there must have been an OpenGL error.");
		LOGGER.info("Lod Render OpenGL version [" + LWJGL.glGetString(GL11.GL_VERSION) + "].");
		
		
		
		
		//============================//
		// get Minecraft's GL context //
		//============================//
		
		// crash the game if the GPU doesn't support OpenGL 3.3
		if (CHECK_GL_VERSION_ON_STARTUP)
		{
			if (!LWJGL.isOpenGLVersionSupported(3,3))
			{
				String supportedVersionInfo = this.getFailedVersionInfo();
				
				// See full requirement at above.
				String errorMessage = ModInfo.READABLE_NAME + " was initializing " + GLProxy.class.getSimpleName()
					+ " and discovered this GPU doesn't meet the OpenGL requirements. Sorry I couldn't tell you sooner :(\n" +
					"Additional info:\n" + supportedVersionInfo;
				IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
				MC.crashMinecraft(errorMessage, new UnsupportedOperationException("Distant Horizon OpenGL requirements not met"));
			}
		}
	 	LOGGER.info("minecraftGlCapabilities:\n" + this.versionInfoToString());
		
		if (Config.Client.Advanced.Debugging.OpenGl.overrideVanillaGLLogger.get())
		{
			#if MC_VER > MC_1_12_2
			GLUtil.setupDebugMessageCallback(new PrintStream(new GLMessageOutputStream(GLProxy::logMessage, this.vanillaDebugMessageBuilder), true));
			#endif
		}
		
		
		
		//======================//
		// get GPU capabilities //
		//======================//
		
		// Check if we can use the Buffer Storage, which is available in GL4.4 or after
		this.bufferStorageSupported = LWJGL.isFeatureSupported(EGLFeature.glBufferStorage);
		if (!this.bufferStorageSupported)
		{
			LOGGER.info("This GPU doesn't support Buffer Storage (OpenGL 4.4), falling back to using other methods.");
		}
		
		// Check if we can use the make-over version of Vertex Attribute, which is available in GL4.3 or after
		this.vertexAttributeBufferBindingSupported = LWJGL.isFeatureSupported(EGLFeature.glBindVertexBuffer);
		
		// used by instanced rendering
		this.vertexAttribDivisorSupported = LWJGL.isOpenGLVersionSupported(3,3);
		// denotes if ARBInstancedArrays.glVertexAttribDivisorARB() is available or not
		// can be used as a backup if MC didn't create a GL 3.3+ context
		this.instancedArraysSupported = LWJGL.isExtensionSupported(EGLExtension.ARB_instanced_arrays);
		
		// get the best automatic upload method
		String vendor = LWJGL.glGetString(GL11.GL_VENDOR).toUpperCase(); // example return: "NVIDIA CORPORATION"
		if (EPlatform.get() != EPlatform.MACOS)
		{
			if (vendor.contains("NVIDIA") || vendor.contains("GEFORCE"))
			{
				// NVIDIA card
				this.preferredUploadMethod = this.bufferStorageSupported ? EDhApiGpuUploadMethod.BUFFER_STORAGE : EDhApiGpuUploadMethod.SUB_DATA;
			}
			else
			{
				// AMD or Intel card
				this.preferredUploadMethod = this.bufferStorageSupported ? EDhApiGpuUploadMethod.BUFFER_STORAGE : EDhApiGpuUploadMethod.DATA;
			}
		}
		else
		{
			// Mac may have an issue with Buffer Storage, so default to the most basic
			// form of uploading
			this.preferredUploadMethod = EDhApiGpuUploadMethod.DATA;
		}
		LOGGER.info("GPU Vendor [" + vendor + "] with OS [" + EPlatform.get().getName() + "], Preferred upload method is [" + this.preferredUploadMethod + "].");
		
		
		
		//==========//
		// clean up //
		//==========//
		
		// GLProxy creation success
		LOGGER.info(GLProxy.class.getSimpleName() + " creation successful. OpenGL smiles upon you this day.");
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public static boolean hasInstance() { return instance != null; }
	/** @throws IllegalStateException if the Proxy hasn't been created yet and this is called outside the render thread */
	public static GLProxy getInstance() throws IllegalStateException
	{
		if (instance == null)
		{
			instance = new GLProxy();
		}
		
		return instance;
	}
	
	public EDhApiGpuUploadMethod getGpuUploadMethod() { return this.preferredUploadMethod; }
	
	public static boolean runningOnRenderThread()
	{
		boolean isRenderThread;
		
		#if MC_VER <= MC_26_2_0
		isRenderThread = LWJGL.isRunningOnRenderThread();
		#else
		Thread thread = Thread.currentThread();
		isRenderThread = thread.getName().toLowerCase().contains("render thread");
		#endif
		
		return isRenderThread;
	}
	
	//endregion
	
	
	
	//=========//
	// logging //
	//=========//
	//region
	
	/** this method is called on the render thread at the point of the GL Error */
	private static void logMessage(GLMessage glMessage)
	{
		EDhApiGLErrorHandlingMode errorHandlingMode = Config.Client.Advanced.Debugging.OpenGl.glErrorHandlingMode.get();
		if (errorHandlingMode == EDhApiGLErrorHandlingMode.IGNORE)
		{
			return;
		}
		
		
		
		boolean onlyLogOnce = Config.Client.Advanced.Debugging.OpenGl.onlyLogGlErrorsOnce.get();
		if (onlyLogOnce
			&& !LOGGED_GL_MESSAGES.add(glMessage.message))
		{
			// this message has already been logged
			return;
		}
		
		String errorMessage = "GL ERROR [" + glMessage.id + "] from [" + glMessage.source + "]: [" + glMessage.message + "].";
		if (onlyLogOnce)
		{
			errorMessage += " This message will only be logged once.";
			errorMessage += " Note: Distant Horizons will catch and log OpenGL errors from other mods, not just DH itself; if everything is rendering correctly these errors can probably be ignored.";
		}
		
		
		
		// create an exception so we get a stacktrace of where the message was triggered from
		RuntimeException exception = new RuntimeException(errorMessage);
		
		if (glMessage.type == EGLMessageType.ERROR || glMessage.type == EGLMessageType.UNDEFINED_BEHAVIOR)
		{
			// critical error
			
			LOGGER.error(exception.getMessage(), exception);
			
			if (errorHandlingMode == EDhApiGLErrorHandlingMode.LOG_THROW)
			{
				// will probably crash the game,
				// good for quickly checking if there's a problem while preventing log spam
				throw exception;
			}
		}
		else
		{
			// non-critical log
			
			EGLMessageSeverity severity = glMessage.severity;
			if (severity == null)
			{
				// just in case the message was malformed
				severity = EGLMessageSeverity.LOW;
			}
			
			switch (severity)
			{
				case HIGH:
					LOGGER.error(exception.getMessage(), exception);
					break;
				case MEDIUM:
					LOGGER.warn(exception.getMessage(), exception);
					break;
				case LOW:
					LOGGER.info(exception.getMessage(), exception);
					break;
				case NOTIFICATION:
					LOGGER.debug(exception.getMessage(), exception);
					break;
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private String getFailedVersionInfo()
	{
		return "Your OpenGL support:\n" +
				"openGL version 3.3+: [" + LWJGL.isOpenGLVersionSupported(3,3) + "] <- REQUIRED\n" +
				"Vertex Attribute Buffer Binding: [" + LWJGL.isOpenGLVersionSupported(4,3) + "] <- optional improvement\n" +
				"Buffer Storage: [" + LWJGL.isExtensionSupported(EGLExtension.ARB_buffer_storage) + "] <- optional improvement\n" +
				"If you noticed that your computer supports higher OpenGL versions"
				+ " but not the required version, try running the game in compatibility mode."
				+ " (How you turn that on, I have no clue~)";
	}
	
	private String versionInfoToString()
	{
		return "Your OpenGL support:\n" +
				"openGL version 3.3+: [" + LWJGL.isOpenGLVersionSupported(3,3) + "] <- REQUIRED\n" +
				"Vertex Attribute Buffer Binding: [" + LWJGL.isOpenGLVersionSupported(4,3) + "] <- optional improvement\n" +
				"Buffer Storage: [" + LWJGL.isExtensionSupported(EGLExtension.ARB_buffer_storage) + "] <- optional improvement\n";
	}
	
	//endregion
	
	
	
}
