package com.seibel.distanthorizons.lwjgl.lwjgl3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.*;
import com.seibel.distanthorizons.lwjgl.EDebugExtension;
import com.seibel.distanthorizons.lwjgl.IDebugMessageHandler;

/**
 * LWJGL3 debug callback setup helper.
 */
final class LWJGL3DebugSupport 
{
    private static final Logger LOGGER = LogManager.getLogger("DistantHorizons/LWJGL3Debug");

    private GLDebugMessageCallback debugCallback;
    private GLDebugMessageARBCallback debugCallbackARB;
    private GLDebugMessageAMDCallback debugCallbackAMD;
	
	
	
	// TODO replace return type with an enum, 0 as an error code is not standard behavior
	/** @return 0=unsupported, 1=success, 2=restart needed */
    int setupDebugCallback(IDebugMessageHandler handler)
    {
        GLCapabilities caps = GL.getCapabilities();

        if (caps.OpenGL43) 
		{
            LOGGER.info("Using OpenGL 4.3 for debug output");
            debugCallback = GLDebugMessageCallback.create(
				(source, type, id, severity, length, message, userParam) -> 
	            {
	                handler.handle(source, type, id, severity, GLDebugMessageCallback.getMessage(length, message), EDebugExtension.GL43);
	            });
            GL43C.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_HIGH, (int[]) null, true);
            GL43C.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_MEDIUM, (int[]) null, false);
            GL43C.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_LOW, (int[]) null, false);
            GL43C.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_NOTIFICATION, (int[]) null, false);
            GL43C.glDebugMessageCallback(debugCallback, 0L);

            if ((GL43C.glGetInteger(GL30C.GL_CONTEXT_FLAGS) & GL43C.GL_CONTEXT_FLAG_DEBUG_BIT) == 0) 
			{
                LOGGER.warn("Non-debug context may not produce debug output");
                GL43C.glEnable(GL43C.GL_DEBUG_OUTPUT);
                return 2;
            }
            return 1;
        } 
		else if (caps.GL_KHR_debug) 
		{
            LOGGER.info("Using KHR_debug for debug output");
            debugCallback = GLDebugMessageCallback.create(
				(source, type, id, severity, length, message, userParam) -> 
	            {
	                handler.handle(source, type, id, severity, GLDebugMessageCallback.getMessage(length, message), EDebugExtension.KHR_DEBUG);
	            });
            KHRDebug.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_HIGH, (int[]) null, true);
            KHRDebug.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_MEDIUM, (int[]) null, false);
            KHRDebug.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_LOW, (int[]) null, false);
            KHRDebug.glDebugMessageControl(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, GL43C.GL_DEBUG_SEVERITY_NOTIFICATION, (int[]) null, false);
            KHRDebug.glDebugMessageCallback(debugCallback, 0L);

            if (caps.OpenGL30 && (GL43C.glGetInteger(GL30C.GL_CONTEXT_FLAGS) & GL43C.GL_CONTEXT_FLAG_DEBUG_BIT) == 0) 
			{
                LOGGER.warn("Non-debug context may not produce debug output");
                GL43C.glEnable(GL43C.GL_DEBUG_OUTPUT);
                return 2;
            }
            return 1;
        } 
		else if (caps.GL_ARB_debug_output) 
		{
            LOGGER.info("Using ARB_debug_output for debug output");
            debugCallbackARB = GLDebugMessageARBCallback.create(
				(source, type, id, severity, length, message, userParam) -> 
				{
	                handler.handle(source, type, id, severity, GLDebugMessageARBCallback.getMessage(length, message), EDebugExtension.ARB_DEBUG_OUTPUT);
	            });
            ARBDebugOutput.glDebugMessageControlARB(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_HIGH_ARB, (int[]) null, true);
            ARBDebugOutput.glDebugMessageControlARB(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_ARB, (int[]) null, false);
            ARBDebugOutput.glDebugMessageControlARB(GL11C.GL_DONT_CARE, GL11C.GL_DONT_CARE, ARBDebugOutput.GL_DEBUG_SEVERITY_LOW_ARB, (int[]) null, false);
            // Note: ARB_debug_output doesn't have NOTIFICATION severity
            ARBDebugOutput.glDebugMessageCallbackARB(debugCallbackARB, 0L);
            return 1;
        } 
		else if (caps.GL_AMD_debug_output) 
		{
            LOGGER.info("Using AMD_debug_output for debug output");
            debugCallbackAMD = GLDebugMessageAMDCallback.create(
				(id, category, severity, length, message, userParam) -> 
	            {
	                // AMD callback has different signature - category instead of source/type
	                handler.handle(category, 0, id, severity, GLDebugMessageAMDCallback.getMessage(length, message), EDebugExtension.AMD_DEBUG_OUTPUT);
	            });
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_HIGH_AMD, (int[]) null, true);
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_MEDIUM_AMD, (int[]) null, false);
            AMDDebugOutput.glDebugMessageEnableAMD(0, AMDDebugOutput.GL_DEBUG_SEVERITY_LOW_AMD, (int[]) null, false);
            // Note: AMD_debug_output doesn't have NOTIFICATION severity
            AMDDebugOutput.glDebugMessageCallbackAMD(debugCallbackAMD, 0L);
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
        GLCapabilities caps = GL.getCapabilities();

        if (caps.OpenGL43)
        {
	        GL43C.glDebugMessageCallback(null, 0L);
        }
        else if (caps.GL_KHR_debug)
        {
	        KHRDebug.glDebugMessageCallback(null, 0L);
	        if (caps.OpenGL30 && (GL43C.glGetInteger(GL30C.GL_CONTEXT_FLAGS) & GL43C.GL_CONTEXT_FLAG_DEBUG_BIT) == 0)
	        {
		        GL43C.glDisable(GL43C.GL_DEBUG_OUTPUT);
	        }
        }
        else if (caps.GL_ARB_debug_output)
        {
	        ARBDebugOutput.glDebugMessageCallbackARB(null, 0L);
        }
        else if (caps.GL_AMD_debug_output)
        {
	        AMDDebugOutput.glDebugMessageCallbackAMD(null, 0L);
        }
	    
	    // Free callbacks to prevent memory leaks
	    if (debugCallback != null)
	    {
		    debugCallback.free();
		    debugCallback = null;
	    }
		
	    if (debugCallbackARB != null)
	    {
		    debugCallbackARB.free();
		    debugCallbackARB = null;
	    }
		
	    if (debugCallbackAMD != null)
	    {
		    debugCallbackAMD.free();
		    debugCallbackAMD = null;
	    }
    }
	
	
	
}
