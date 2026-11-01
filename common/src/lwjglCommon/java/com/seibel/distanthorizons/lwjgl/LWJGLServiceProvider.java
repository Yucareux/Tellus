package com.seibel.distanthorizons.lwjgl;

import java.lang.reflect.Method;

/**
 * Loads LWJGLService via ServiceLoader, 
 * picking the newer LWJGL 3 over 2 if available.
 */
public final class LWJGLServiceProvider 
{
    public static final ILWJGLService LWJGL = createInstance();
    public static final int POINTER_SIZE = LWJGL.getPointerSize();
    public static final long NULL = 0L;

	
	
    private LWJGLServiceProvider() {}
	
	
	
    static ILWJGLService createInstance() 
    {
		// Reflection is used to grab the included LWJGL instance
	    // since we don't know which one will be available at compile-time.
        try 
        {
            Class.forName("org.lwjgl.opengl.GL11C");
            return constructInstance("com.seibel.distanthorizons.lwjgl.lwjgl3.LWJGL3Service");
        } 
		catch (ClassNotFoundException e) 
		{
            return constructInstance("com.seibel.distanthorizons.lwjgl.lwjgl2.LWJGL2Service");
        }
    }
	static ILWJGLService constructInstance(String className)
	{
		try
		{
			Class<?> clz = Class.forName(className);
			Method method = clz.getDeclaredMethod("create");
			return (ILWJGLService) method.invoke(null);
		}
		catch (ReflectiveOperationException e)
		{
			// shouldn't happen, but just in case
			throw new AssertionError(e);
		}
	}
	
	
	
}
