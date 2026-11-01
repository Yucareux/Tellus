package com.seibel.distanthorizons.lwjgl.lwjgl2;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;
import com.seibel.distanthorizons.lwjgl.EDebugExtension;
import com.seibel.distanthorizons.lwjgl.IDebugMessageHandler;

/**
 * LWJGL2 debug callback setup helper.
 */
final class LWJGL2DebugSupport 
{
    private static final Logger LOGGER = LogManager.getLogger("DistantHorizons/LWJGL2Debug");

    private KHRDebugCallback debugCallbackKHR;
    private ARBDebugOutputCallback debugCallbackARB;
    private AMDDebugOutputCallback debugCallbackAMD;
	
	
	// TODO replace return type with an enum, 0 as an error code is not standard behavior
	/** @return 0=unsupported, 1=success, 2=restart needed */
    int setupDebugCallback(IDebugMessageHandler handler) 
    {
        ContextCapabilities caps = GLContext.getCapabilities();

        if (caps.OpenGL43) 
		{
            LOGGER.info("Using OpenGL 4.3 for debug output");
            this.debugCallbackKHR = new KHRDebugCallback(new KHRDebugCallback.Handler() 
            {
                @Override
                public void handleMessage(int source, int type, int id, int severity, String message) 
                {
                    handler.handle(source, type, id, severity, message, EDebugExtension.GL43);
                }
            });
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_HIGH, null, true);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_MEDIUM, null, false);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_LOW, null, false);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, null, false);
            KHRDebug.glDebugMessageCallback(this.debugCallbackKHR);

            if ((GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & KHRDebug.GL_CONTEXT_FLAG_DEBUG_BIT) == 0) 
			{
                LOGGER.warn("Non-debug context may not produce debug output");
                GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
                return 2;
            }
			
            return 1;
        } 
		else if (caps.GL_KHR_debug) 
		{
            LOGGER.info("Using KHR_debug for debug output");
			this.debugCallbackKHR = new KHRDebugCallback(new KHRDebugCallback.Handler() 
            {
                @Override
                public void handleMessage(int source, int type, int id, int severity, String message) 
                {
                    handler.handle(source, type, id, severity, message, EDebugExtension.KHR_DEBUG);
                }
            });
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_HIGH, null, true);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_MEDIUM, null, false);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_LOW, null, false);
            KHRDebug.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, KHRDebug.GL_DEBUG_SEVERITY_NOTIFICATION, null, false);
            KHRDebug.glDebugMessageCallback(this.debugCallbackKHR);

            if (caps.OpenGL30
	            && (GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & KHRDebug.GL_CONTEXT_FLAG_DEBUG_BIT) == 0) 
			{
                LOGGER.warn("Non-debug context may not produce debug output");
                GL11.glEnable(KHRDebug.GL_DEBUG_OUTPUT);
                return 2;
            }
			
            return 1;
        } 
		else if (caps.GL_ARB_debug_output) 
		{
            LOGGER.info("Using ARB_debug_output for debug output");
			this.debugCallbackARB = new ARBDebugOutputCallback(new ARBDebugOutputCallback.Handler() 
            {
                @Override
                public void handleMessage(int source, int type, int id, int severity, String message) 
                {
                    handler.handle(source, type, id, severity, message, EDebugExtension.ARB_DEBUG_OUTPUT);
                }
            });
            ARBDebugOutput.glDebugMessageControlARB(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB, null, true);
            ARBDebugOutput.glDebugMessageControlARB(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_ARB, null, false);
            ARBDebugOutput.glDebugMessageControlARB(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_LOW_ARB, null, false);
            ARBDebugOutput.glDebugMessageCallbackARB(this.debugCallbackARB);
			
            return 1;
        } 
		else if (caps.GL_AMD_debug_output) 
		{
            LOGGER.info("Using AMD_debug_output for debug output");
			this.debugCallbackAMD = new AMDDebugOutputCallback(new AMDDebugOutputCallback.Handler() 
            {
                @Override
                public void handleMessage(int id, int category, int severity, String message) 
                {
                    // AMD callback has different signature - category instead of source/type
                    handler.handle(category, 0, id, severity, message, EDebugExtension.AMD_DEBUG_OUTPUT);
                }
            });
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_HIGH_AMD, null, true);
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_AMD, null, false);
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_LOW_AMD, null, false);
            AMDDebugOutput.glDebugMessageCallbackAMD(this.debugCallbackAMD);
			
            return 1;
        } 
		else 
		{
            LOGGER.info("No debug output implementation available");
            return 0;
        }
    }

    void disableDebugCallback() 
    {
        ContextCapabilities caps = GLContext.getCapabilities();

        if (caps.OpenGL43 
	        || caps.GL_KHR_debug) 
		{
            KHRDebug.glDebugMessageCallback(null);
            if (caps.OpenGL30 && (GL11.glGetInteger(GL30.GL_CONTEXT_FLAGS) & KHRDebug.GL_CONTEXT_FLAG_DEBUG_BIT) == 0) 
			{
                GL11.glDisable(KHRDebug.GL_DEBUG_OUTPUT);
            }
        }
		else if (caps.GL_ARB_debug_output) 
		{
            ARBDebugOutput.glDebugMessageCallbackARB(null);
        } 
		else if (caps.GL_AMD_debug_output) 
		{
            AMDDebugOutput.glDebugMessageCallbackAMD(null);
        }

        // In LWJGL2, callbacks don't need explicit freeing as they are GC'd
	    this.debugCallbackKHR = null;
	    this.debugCallbackARB = null;
	    this.debugCallbackAMD = null;
    }
	
	
	
}
