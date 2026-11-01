package com.seibel.distanthorizons.lwjgl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * Thread-local stack allocator abstraction. Use with try-with-resources:
 * <pre>{@code
 * try (MemoryStack stack = LWJGL.stackPush()) {
 *     IntBuffer buf = stack.mallocInt(1);
 *     // use buf...
 * } // automatically freed here
 * }</pre>
 */
public abstract class AbstractMemoryStack implements AutoCloseable 
{

    public static AbstractMemoryStack stackPush() { return LWJGLServiceProvider.LWJGL.stackPush();  }

	
	
    @Override
    public abstract void close();

    public abstract long getPointer();
    public abstract void setPointer(long pointer);
    public abstract long getAddress();
    public abstract int getSize();

	
	
    // ===================== MALLOC OPERATIONS =====================
	//region

    public abstract ByteBuffer malloc(int size);
    public abstract ShortBuffer mallocShort(int count);
    public abstract IntBuffer mallocInt(int count);
    public abstract LongBuffer mallocLong(int count);
    public abstract FloatBuffer mallocFloat(int count);
	
	//endregion
	
	
	
    // ===================== CALLOC OPERATIONS =====================
	//region
	
    public abstract ByteBuffer calloc(int size);
    public abstract ShortBuffer callocShort(int count);
    public abstract IntBuffer callocInt(int count);
    public abstract LongBuffer callocLong(int count);
    public abstract FloatBuffer callocFloat(int count);
	
	//endregion
	
	
	
    // ===================== PRIMITIVE ALLOCATIONS =====================
	//region
	
    public IntBuffer ints(int value) 
    {
        IntBuffer buf = mallocInt(1);
        buf.put(0, value);
        return buf;
    }

    public IntBuffer ints(int... values) 
    {
        IntBuffer buf = mallocInt(values.length);
        buf.put(values).flip();
        return buf;
    }

    public FloatBuffer floats(float value) 
    {
        FloatBuffer buf = mallocFloat(1);
        buf.put(0, value);
        return buf;
    }

    public FloatBuffer floats(float... values) 
    {
        FloatBuffer buf = mallocFloat(values.length);
        buf.put(values).flip();
        return buf;
    }
	
	//endregion
	
	
	
    // ===================== POINTER OPERATIONS =====================
	//region
	
    public abstract long nmalloc(int size);
    public abstract long nmalloc(int alignment, int size);
    public abstract long ncalloc(int alignment, int count, int size);
	
	//endregion
	
	
	
}
