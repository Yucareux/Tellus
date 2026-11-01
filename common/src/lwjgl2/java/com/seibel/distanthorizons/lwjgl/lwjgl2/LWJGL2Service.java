package com.seibel.distanthorizons.lwjgl.lwjgl2;

import com.seibel.distanthorizons.lwjgl.*;
import org.lwjgl.BufferUtils;
import com.seibel.distanthorizons.lwjgl.lwjgl2.memory.MemoryStack;
import com.seibel.distanthorizons.lwjgl.lwjgl2.memory.MemoryUtilities;
import com.seibel.distanthorizons.lwjgl.lwjgl2.memory.Pointer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL2 implementation of {@link ILWJGLService}.
 */
public class LWJGL2Service implements ILWJGLService
{
	private static final Logger LOGGER = LogManager.getLogger("DistantHorizons/LWJGL2Service");
	private static final LWJGL2DebugSupport debugSupport = new LWJGL2DebugSupport();
	
	private final VAOMode vaoMode;
	private final TimerQueryMode timerQueryMode;
	private final DebugMode debugMode;
	private final VertexAttribIMode vertexAttribIMode;
	private final Long2ObjectOpenHashMap<GLSync> syncObjects = new Long2ObjectOpenHashMap<GLSync>();
	
	
	
	private LWJGL2Service(
		VAOMode vaoMode, 
		TimerQueryMode timerQueryMode, 
		DebugMode debugMode, 
		VertexAttribIMode vertexAttribIMode) 
	{ 
		this.vaoMode = vaoMode;
		this.timerQueryMode = timerQueryMode;
		this.debugMode = debugMode;
		this.vertexAttribIMode = vertexAttribIMode;
	}
	
	
	
	private enum VAOMode
	{
		CORE
		{
			@Override public int gen() { return GL30.glGenVertexArrays(); }
			@Override public void delete(int array) { GL30.glDeleteVertexArrays(array); }
			@Override public void bind(int array) { GL30.glBindVertexArray(array); }
		},
		ARB
		{
			@Override public int gen() { return ARBVertexArrayObject.glGenVertexArrays(); }
			@Override public void delete(int array) { ARBVertexArrayObject.glDeleteVertexArrays(array); }
			@Override public void bind(int array) { ARBVertexArrayObject.glBindVertexArray(array); }
		},
		APPLE
		{
			@Override public int gen() { return APPLEVertexArrayObject.glGenVertexArraysAPPLE(); }
			@Override public void delete(int array) { APPLEVertexArrayObject.glDeleteVertexArraysAPPLE(array); }
			@Override public void bind(int array) { APPLEVertexArrayObject.glBindVertexArrayAPPLE(array); }
		},
		NONE
		{
			@Override public int gen() { throw new UnsupportedOperationException("VAO not supported"); }
			@Override public void delete(int array) { throw new UnsupportedOperationException("VAO not supported"); }
			@Override public void bind(int array) { throw new UnsupportedOperationException("VAO not supported"); }
		};
		
		public abstract int gen();
		public abstract void delete(int array);
		public abstract void bind(int array);
	}
	
	private enum TimerQueryMode
	{
		CORE
		{
			@Override public void queryCounter(int id, int target) { GL33.glQueryCounter(id, target); }
			@Override public long getQueryObjectui64(int id, int pname) { return GL33.glGetQueryObjectui64(id, pname); }
		},
		ARB
		{
			@Override public void queryCounter(int id, int target) { ARBTimerQuery.glQueryCounter(id, target); }
			@Override public long getQueryObjectui64(int id, int pname) { return ARBTimerQuery.glGetQueryObjectui64(id, pname); }
		},
		NONE
		{
			@Override public void queryCounter(int id, int target) { /* no-op */ }
			@Override public long getQueryObjectui64(int id, int pname) { return 0L; }
		};
		
		public abstract void queryCounter(int id, int target);
		public abstract long getQueryObjectui64(int id, int pname);
	}
	
	private enum DebugMode
	{
		KHR
		{
			@Override public void objectLabel(int identifier, int name, CharSequence label) { KHRDebug.glObjectLabel(identifier, name, label); }
			@Override public void pushDebugGroup(int source, int id, CharSequence message) { KHRDebug.glPushDebugGroup(source, id, message); }
			@Override public void popDebugGroup() { KHRDebug.glPopDebugGroup(); }
		},
		NONE
		{
			@Override public void objectLabel(int identifier, int name, CharSequence label) { /* no-op */ }
			@Override public void pushDebugGroup(int source, int id, CharSequence message) { /* no-op */ }
			@Override public void popDebugGroup() { /* no-op */ }
		};
		
		public abstract void objectLabel(int identifier, int name, CharSequence label);
		public abstract void pushDebugGroup(int source, int id, CharSequence message);
		public abstract void popDebugGroup();
	}
	
	private enum VertexAttribIMode
	{
		CORE
		{
			@Override 
			public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer)
			{
				GL30.glVertexAttribIPointer(index, size, type, stride, pointer);
			}
		},
		EXT
		{
			@Override
			public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer)
			{
				EXTGpuShader4.glVertexAttribIPointerEXT(index, size, type, stride, pointer);
			}
		},
		NONE
		{
			@Override 
			public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer)
			{
				throw new UnsupportedOperationException("glVertexAttribIPointer not supported");
			}
		};
		
		public abstract void vertexAttribIPointer(int index, int size, int type, int stride, long pointer);
	}
	
	
	
	public static LWJGL2Service create()
	{
		ContextCapabilities caps = GLContext.getCapabilities();
		
		VAOMode vaoMode;
		if (caps.OpenGL30)
		{
			vaoMode = VAOMode.CORE;
		}
		else if (caps.GL_ARB_vertex_array_object)
		{
			vaoMode = VAOMode.ARB;
		}
		else if (caps.GL_APPLE_vertex_array_object)
		{
			vaoMode = VAOMode.APPLE;
		}
		else
		{
			vaoMode = VAOMode.NONE;
		}
		
		TimerQueryMode timerQueryMode;
		if (caps.OpenGL33)
		{
			timerQueryMode = TimerQueryMode.CORE;
		}
		else if (caps.GL_ARB_timer_query)
		{
			timerQueryMode = TimerQueryMode.ARB;
		}
		else
		{
			timerQueryMode = TimerQueryMode.NONE;
			LOGGER.warn("ARB_timer_query extension not available - GPU profiling will be disabled");
		}
		
		DebugMode debugMode;
		if (caps.GL_KHR_debug || caps.OpenGL43)
		{
			debugMode = DebugMode.KHR;
		}
		else
		{
			debugMode = DebugMode.NONE;
		}
		
		VertexAttribIMode vertexAttribIMode;
		if (caps.OpenGL30)
		{
			vertexAttribIMode = VertexAttribIMode.CORE;
		}
		else if (caps.GL_EXT_gpu_shader4)
		{
			vertexAttribIMode = VertexAttribIMode.EXT;
		}
		else
		{
			vertexAttribIMode = VertexAttribIMode.NONE;
		}
		
		return new LWJGL2Service(vaoMode, timerQueryMode, debugMode, vertexAttribIMode);
	}
	
	
	
	
	// ===================== CAPABILITIES =====================
	
	@Override
	public boolean isOpenGLVersionSupported(int major, int minor)
	{
		ContextCapabilities caps = GLContext.getCapabilities();
		switch (major * 10 + minor)
		{
			case 11:
				return caps.OpenGL11;
			case 12:
				return caps.OpenGL12;
			case 13:
				return caps.OpenGL13;
			case 14:
				return caps.OpenGL14;
			case 15:
				return caps.OpenGL15;
			case 20:
				return caps.OpenGL20;
			case 21:
				return caps.OpenGL21;
			case 30:
				return caps.OpenGL30;
			case 31:
				return caps.OpenGL31;
			case 32:
				return caps.OpenGL32;
			case 33:
				return caps.OpenGL33;
			case 40:
				return caps.OpenGL40;
			case 41:
				return caps.OpenGL41;
			case 42:
				return caps.OpenGL42;
			case 43:
				return caps.OpenGL43;
			case 44:
				return caps.OpenGL44;
			default:
				return false;
		}
	}
	
	@Override
	public boolean isExtensionSupported(EGLExtension extension)
	{
		ContextCapabilities caps = GLContext.getCapabilities();
		switch (extension)
		{
			case ARB_buffer_storage: return caps.GL_ARB_buffer_storage;
			case ARB_multi_draw_indirect: return caps.GL_ARB_multi_draw_indirect;
			case ARB_draw_elements_base_vertex: return caps.GL_ARB_draw_elements_base_vertex;
			case ARB_shader_storage_buffer_object: return caps.GL_ARB_shader_storage_buffer_object;
			case ARB_sync: return caps.GL_ARB_sync;
			case ARB_timer_query: return caps.GL_ARB_timer_query;
			case ARB_debug_output: return caps.GL_ARB_debug_output;
			case KHR_debug: return caps.GL_KHR_debug;
			case AMD_debug_output: return caps.GL_AMD_debug_output;
			case ARB_uniform_buffer_object: return caps.GL_ARB_uniform_buffer_object;
			case ARB_vertex_array_object: return caps.GL_ARB_vertex_array_object;
			case ARB_map_buffer_range: return caps.GL_ARB_map_buffer_range;
			case ARB_copy_buffer: return caps.GL_ARB_copy_buffer;
			case ARB_texture_storage: return caps.GL_ARB_texture_storage;
			case ARB_base_instance: return caps.GL_ARB_base_instance;
			case ARB_compatibility: return caps.GL_ARB_compatibility;
			case ARB_instanced_arrays: return caps.GL_ARB_instanced_arrays;
			
			default: throw new UnsupportedOperationException("No logic defined for extension ["+extension+"].");
		}
	}
	
	@Override
	public boolean isFeatureSupported(EGLFeature feature)
	{
		ContextCapabilities caps = GLContext.getCapabilities();
		switch (feature)
		{
			case glBufferStorage: return reflectivelyGetCapabilitySupported(caps, "glBufferStorage");
			case glBindVertexBuffer: return reflectivelyGetCapabilitySupported(caps, "glBindVertexBuffer");
			
			default: throw new UnsupportedOperationException("No logic defined for feature ["+feature+"].");
		}
	}
	private static boolean reflectivelyGetCapabilitySupported(ContextCapabilities caps, String fieldName)
	{
		// try finding the field by its name
		Field desiredField = null;
		Field[] fields = caps.getClass().getDeclaredFields();
		for (int i = 0; i < fields.length; i++)
		{
			Field field = fields[i];
			if (field.getName().equals(fieldName))
			{
				desiredField = field;
				break;
			}
		}
		
		if (desiredField == null)
		{
			// LWJGL 2 should be stable enough that if this comes up it means we screwed up the capitalization or something similar
			throw new NullPointerException("No capability field found with the name ["+fieldName+"].");
		}
		
		// the field is normally private, we need it public
		desiredField.setAccessible(true);
		try
		{
			Object obj = desiredField.get(caps);
			return ((Long) obj) != 0; // if the pointer is non-null, then this is a valid method
		}
		catch (IllegalAccessException e)
		{
			LOGGER.error("Unable to access GL capability field ["+fieldName+"] due to error: ["+e.getMessage()+"].", e);
			return false;
		}
	}
	
	
	@Override
	public int getPointerSize()
	{
		return Pointer.POINTER_SIZE;
	}
	
	// ===================== BUFFER OPERATIONS =====================
	
	@Override
	public int glGenBuffers()
	{
		return GL15.glGenBuffers();
	}
	
	@Override
	public void glDeleteBuffers(int buffer)
	{
		GL15.glDeleteBuffers(buffer);
	}
	
	@Override
	public void glBindBuffer(int target, int buffer)
	{
		GL15.glBindBuffer(target, buffer);
	}
	
	@Override
	public void glBufferData(int target, long size, int usage)
	{
		GL15.glBufferData(target, size, usage);
	}
	
	@Override
	public void glBufferData(int target, ByteBuffer data, int usage)
	{
		GL15.glBufferData(target, data, usage);
	}
	
	@Override
	public void glBufferData(int target, long size, long data, int usage)
	{
		// LWJGL2 nglBufferData has different signature - wrap the pointer
		if (data == 0)
		{
			GL15.glBufferData(target, size, usage);
		}
		else
		{
			ByteBuffer buf = MemoryUtilities.memByteBuffer(data, (int) size);
			GL15.glBufferData(target, buf, usage);
		}
	}
	
	@Override
	public void glBufferData(int target, int[] data, int usage)
	{
		IntBuffer buf = MemoryUtilities.memAllocInt(data.length);
		buf.put(data).flip();
		GL15.glBufferData(target, buf, usage);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public void glBufferData(int target, float[] data, int usage)
	{
		FloatBuffer buf = MemoryUtilities.memAllocFloat(data.length);
		buf.put(data).flip();
		GL15.glBufferData(target, buf, usage);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public void glBufferStorage(int target, long size, int flags)
	{
		ARBBufferStorage.glBufferStorage(target, size, flags);
	}
	
	@Override
	public void glBufferStorage(int target, ByteBuffer bb, int flags)
	{
		ARBBufferStorage.glBufferStorage(target, bb, flags);
	}
	
	@Override
	public void glBufferSubData(int target, long size, ByteBuffer data)
	{
		GL15.glBufferSubData(target, size, data);
	}
	
	@Override
	public ByteBuffer glMapBufferRange(int target, long offset, long length, int flags)
	{
		return GL30.glMapBufferRange(target, offset, length, flags, null);
	}
	
	@Override
	public long nglMapBuffer(int target, int access)
	{
		ByteBuffer buf = GL15.glMapBuffer(target, access, null);
		return buf != null ? MemoryUtilities.memAddress(buf) : 0L;
	}
	
	@Override
	public ByteBuffer glMapBuffer(int target, int access)
	{
		return GL15.glMapBuffer(target, access, null);
	}
	
	@Override
	public void glUnmapBuffer(int target)
	{
		GL15.glUnmapBuffer(target);
	}
	
	@Override
	public void glFlushMappedBufferRange(int target, long offset, long length)
	{
		GL30.glFlushMappedBufferRange(target, offset, length);
	}
	
	@Override
	public void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size)
	{
		GL31.glCopyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
	}
	
	@Override
	public void glBindBufferBase(int target, int index, int buffer)
	{
		GL30.glBindBufferBase(target, index, buffer);
	}
	
	@Override
	public void glBindVertexBuffer(int bindingindex, int buffer, int offset, int stride)
	{
		GL43.glBindVertexBuffer(bindingindex, buffer, offset, stride);
	}
	
	// ===================== VAO OPERATIONS =====================
	
	@Override
	public int glGenVertexArrays()
	{
		return vaoMode.gen();
	}
	
	@Override
	public void glDeleteVertexArrays(int array)
	{
		vaoMode.delete(array);
	}
	
	@Override
	public void glBindVertexArray(int array)
	{
		vaoMode.bind(array);
	}
	
	@Override
	public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer)
	{
		GL20.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
	}
	
	@Override
	public void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer)
	{
		vertexAttribIMode.vertexAttribIPointer(index, size, type, stride, pointer);
	}
	
	@Override
	public void glEnableVertexAttribArray(int index)
	{
		GL20.glEnableVertexAttribArray(index);
	}
	
	@Override
	public void glDisableVertexAttribArray(int index)
	{
		GL20.glDisableVertexAttribArray(index);
	}
	
	@Override
	public void glVertexAttribDivisor(int index, int divisor)
	{
		GL33.glVertexAttribDivisor(index, divisor);
	}
	
	@Override
	public void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset)
	{
		GL43.glVertexAttribFormat(attribindex, size, type, normalized, relativeoffset);
	}
	
	@Override
	public void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset)
	{
		GL43.glVertexAttribIFormat(attribindex, size, type, relativeoffset);
	}
	
	@Override
	public void glVertexAttribBinding(int attribindex, int bindingindex)
	{
		GL43.glVertexAttribBinding(attribindex, bindingindex);
	}
	
	// ===================== SHADER OPERATIONS =====================
	
	@Override
	public int glCreateShader(int type)
	{
		return GL20.glCreateShader(type);
	}
	
	@Override
	public void glShaderSource(int shader, CharSequence source)
	{
		GL20.glShaderSource(shader, source);
	}
	
	@Override
	public void glShaderSourceSafe(int shader, CharSequence source)
	{
		// AMD driver workaround: pass null for string length to force null-terminator reliance.
		// Some AMD drivers don't receive or interpret the length correctly, resulting in an
		// access violation when the driver tries to read past the string memory.
		// In LWJGL2, we use the normal method which should be null-terminated aware.
		ByteBuffer sourceBuffer = MemoryUtilities.memUTF8(source, true);
		GL20.glShaderSource(shader, sourceBuffer);
	}
	
	@Override
	public void glCompileShader(int shader)
	{
		GL20.glCompileShader(shader);
	}
	
	@Override
	public String glGetShaderInfoLog(int shader, int maxLength)
	{
		return GL20.glGetShaderInfoLog(shader, maxLength);
	}
	
	@Override
	public int glGetShaderi(int shader, int pname)
	{
		return GL20.glGetShaderi(shader, pname);
	}
	
	@Override
	public void glDeleteShader(int shader)
	{
		GL20.glDeleteShader(shader);
	}
	
	@Override
	public int glCreateProgram()
	{
		return GL20.glCreateProgram();
	}
	
	@Override
	public void glAttachShader(int program, int shader)
	{
		GL20.glAttachShader(program, shader);
	}
	
	@Override
	public void glLinkProgram(int program)
	{
		GL20.glLinkProgram(program);
	}
	
	@Override
	public String glGetProgramInfoLog(int program, int maxLength)
	{
		return GL20.glGetProgramInfoLog(program, maxLength);
	}
	
	@Override
	public int glGetProgrami(int program, int pname)
	{
		return GL20.glGetProgrami(program, pname);
	}
	
	@Override
	public void glUseProgram(int program)
	{
		GL20.glUseProgram(program);
	}
	
	@Override
	public void glDeleteProgram(int program)
	{
		GL20.glDeleteProgram(program);
	}
	
	@Override
	public void glBindAttribLocation(int program, int index, CharSequence name)
	{
		GL20.glBindAttribLocation(program, index, name);
	}
	
	@Override
	public void glBindFragDataLocation(int program, int colorNumber, CharSequence name)
	{
		GL30.glBindFragDataLocation(program, colorNumber, name);
	}
	
	// ===================== UNIFORM OPERATIONS =====================
	
	@Override
	public int glGetUniformLocation(int program, CharSequence name)
	{
		return GL20.glGetUniformLocation(program, name);
	}
	
	@Override
	public int glGetUniformBlockIndex(int program, CharSequence name)
	{
		return GL31.glGetUniformBlockIndex(program, name);
	}
	
	@Override
	public void glUniformBlockBinding(int program, int blockIndex, int blockBinding)
	{
		GL31.glUniformBlockBinding(program, blockIndex, blockBinding);
	}
	
	@Override
	public void glUniform1f(int location, float v0)
	{
		GL20.glUniform1f(location, v0);
	}
	
	@Override
	public void glUniform1i(int location, int v0)
	{
		GL20.glUniform1i(location, v0);
	}
	
	@Override
	public void glUniform1fv(int location, FloatBuffer value)
	{
		GL20.glUniform1(location, value);
	}
	
	@Override
	public void glUniform2i(int location, int v0, int v1)
	{
		GL20.glUniform2i(location, v0, v1);
	}
	
	@Override
	public void glUniform2f(int location, int v0, int v1)
	{
		GL20.glUniform2f(location, v0, v1);
	}
	
	@Override
	public void glUniform3f(int location, float v0, float v1, float v2)
	{
		GL20.glUniform3f(location, v0, v1, v2);
	}
	
	@Override
	public void glUniform3i(int location, int v0, int v1, int v2)
	{
		GL20.glUniform3i(location, v0, v1, v2);
	}
	
	@Override
	public void glUniform3fv(int location, FloatBuffer value)
	{
		GL20.glUniform3(location, value);
	}
	
	@Override
	public void glUniform3fv(int location, float[] value)
	{
		if (value.length == 3)
		{
			// fast path: single vec3
			GL20.glUniform3f(location, value[0], value[1], value[2]);
			return;
		}
		
		if (value.length % 3 != 0)
			throw new IllegalArgumentException("Array length must be multiple of 3");
		
		// general path: multiple vec3s
		FloatBuffer buffer = BufferUtils.createFloatBuffer(value.length);
		buffer.put(value).flip();
		GL20.glUniform3(location, buffer);
	}
	
	@Override
	public void glUniform4f(int location, float v0, float v1, float v2, float v3)
	{
		GL20.glUniform4f(location, v0, v1, v2, v3);
	}
	
	@Override
	public void glUniform4fv(int location, FloatBuffer value)
	{
		GL20.glUniform4(location, value);
	}
	
	@Override
	public void glUniform4fv(int location, float[] value)
	{
		if (value.length == 4)
		{
			// fast path: single vec4
			GL20.glUniform4f(location, value[0], value[1], value[2], value[3]);
			return;
		}
		
		if (value.length % 4 != 0)
			throw new IllegalArgumentException("Array length must be multiple of 4");
		
		// general path: multiple vec4s
		FloatBuffer buffer = BufferUtils.createFloatBuffer(value.length);
		buffer.put(value).flip();
		GL20.glUniform4(location, buffer);
	}
	
	@Override
	public void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value)
	{
		GL20.glUniformMatrix3(location, transpose, value);
	}
	
	@Override
	public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value)
	{
		GL20.glUniformMatrix4(location, transpose, value);
	}
	
	@Override
	public void glDrawArrays(int mode, int first, int count)
	{
		GL11.glDrawArrays(mode, first, count);
	}
	
	@Override
	public void glDrawElements(int mode, int count, int type, int indices)
	{
		GL11.glDrawElements(mode, count, type, indices);
	}
	
	// ===================== DRAW OPERATIONS =====================
	
	@Override
	public void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex)
	{
		GL32.glDrawElementsBaseVertex(mode, count, type, indices, basevertex);
	}
	
	@Override
	public void glDrawElementsInstanced(int mode, int count, int type, int indices, int instancecount)
	{
		GL31.glDrawElementsInstanced(mode, count, type, indices, instancecount);
	}
	
	@Override
	public void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex)
	{
		// Must emulate using a loop on LWJGL2. Sad! But there is no better way until we start writing our own
		// native code, because LWJGL2 doesn't allow us to call arbitrary functions by name like LWJGL3 does.
		for (int i = 0; i < drawcount; i++)
		{
			int count = MemoryUtilities.memGetInt(pCount + (long) i * 4);
			if (count > 0)
			{
				long indices = MemoryUtilities.memGetAddress(pIndices + (long) i * Pointer.POINTER_SIZE);
				int baseVertex = MemoryUtilities.memGetInt(pBaseVertex + (long) i * 4);
				GL32.glDrawElementsBaseVertex(mode, count, type, indices, baseVertex);
			}
		}
	}
	
	@Override
	public void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride)
	{
		GL43.glMultiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
	}
	
	// ===================== SYNC OPERATIONS =====================
	
	@Override
	public long glFenceSync(int condition, int flags)
	{
		GLSync sync = GL32.glFenceSync(condition, flags);
		long pointer = sync.getPointer();
		syncObjects.put(pointer, sync);
		return pointer;
	}
	
	@Override
	public int glClientWaitSync(long sync, int flags, long timeout)
	{
		return GL32.glClientWaitSync(syncObjects.get(sync), flags, timeout);
	}
	
	@Override
	public int glGetSynci(long sync, int pname, IntBuffer length)
	{
		// LWJGL2 glGetSynci doesn't take length buffer - it returns single value directly
		int result = GL32.glGetSynci(syncObjects.get(sync), pname);
		if (length != null)
		{
			length.put(0, 1); // Always returns single value
		}
		return result;
	}
	
	@Override
	public void glWaitSync(long sync, int flags, long timeout)
	{
		GL32.glWaitSync(syncObjects.get(sync), flags, timeout);
	}
	
	@Override
	public void glDeleteSync(long sync)
	{
		GLSync obj = syncObjects.remove(sync);
		if (obj == null) return;
		GL32.glDeleteSync(obj);
	}
	
	@Override
	public void glFlush()
	{
		GL11.glFlush();
	}
	
	// ===================== QUERY OPERATIONS =====================
	
	@Override
	public int glGenQueries()
	{
		return GL15.glGenQueries();
	}
	
	@Override
	public void glDeleteQueries(int query)
	{
		GL15.glDeleteQueries(query);
	}
	
	@Override
	public void glQueryCounter(int id, int target)
	{
		timerQueryMode.queryCounter(id, target);
	}
	
	@Override
	public long glGetQueryObjectui64(int id, int pname)
	{
		return timerQueryMode.getQueryObjectui64(id, pname);
	}
	
	// ===================== DEBUG OPERATIONS =====================
	
	@Override
	public PrintStream getDebugStream() { return System.err; }
	
	@Override
	public int setupDebugCallback(IDebugMessageHandler handler)
	{
		return debugSupport.setupDebugCallback(handler);
	}
	
	@Override
	public void disableDebugCallback()
	{
		debugSupport.disableDebugCallback();
	}
	
	@Override
	public void glObjectLabel(int identifier, int name, CharSequence label)
	{
		debugMode.objectLabel(identifier, name, label);
	}
	
	@Override
	public void glPushDebugGroup(int source, int id, CharSequence message)
	{
		debugMode.pushDebugGroup(source, id, message);
	}
	
	@Override
	public void glPopDebugGroup()
	{
		debugMode.popDebugGroup();
	}
	
	// ===================== TEXTURE OPERATIONS =====================
	
	@Override
	public int glGenTextures()
	{
		return GL11.glGenTextures();
	}
	
	@Override
	public void glGenTextures(int[] textures)
	{
		IntBuffer buf = MemoryUtilities.memAllocInt(textures.length);
		GL11.glGenTextures(buf);
		buf.get(textures);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public void glDeleteTextures(int texture)
	{
		GL11.glDeleteTextures(texture);
	}
	
	@Override
	public void glDeleteTextures(int[] textures)
	{
		IntBuffer buf = (IntBuffer) MemoryUtilities.memAllocInt(textures.length).put(textures).flip();
		GL11.glDeleteTextures(buf);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public void glBindTexture(int target, int texture)
	{
		GL11.glBindTexture(target, texture);
	}
	
	@Override
	public void glActiveTexture(int texture)
	{
		GL13.glActiveTexture(texture);
	}
	
	@Override
	public int glGetTexLevelParameteri(int target, int level, int pname)
	{
		return GL11.glGetTexLevelParameteri(target, level, pname);
	}
	
	@Override
	public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height)
	{
		GL11.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
	}
	
	@Override
	public void glPixelStorei(int pname, int param)
	{
		GL11.glPixelStorei(pname, param);
	}
	
	@Override
	public void glTexParameteri(int target, int pname, int param)
	{
		GL11.glTexParameteri(target, pname, param);
	}
	
	@Override
	public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels)
	{
		GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
	}
	@Override
	public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels)
	{
		GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
	}
	
	@Override
	public void glGenerateMipmap(int target)
	{
		GL30.glGenerateMipmap(target);
	}
	
	@Override
	public boolean glIsTexture(int texture)
	{
		return GL11.glIsTexture(texture);
	}
	
	@Override
	public void glBindSampler(int unit, int sampler)
	{
		GL33.glBindSampler(unit, sampler);
	}
	
	// ===================== FRAMEBUFFER OPERATIONS =====================
	
	@Override
	public int glGenFramebuffers()
	{
		return GL30.glGenFramebuffers();
	}
	
	@Override
	public void glDeleteFramebuffers(int framebuffer)
	{
		GL30.glDeleteFramebuffers(framebuffer);
	}
	
	@Override
	public void glBindFramebuffer(int target, int framebuffer)
	{
		GL30.glBindFramebuffer(target, framebuffer);
	}
	
	@Override
	public int glCheckFramebufferStatus(int target)
	{
		return GL30.glCheckFramebufferStatus(target);
	}
	
	@Override
	public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level)
	{
		GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
	}
	
	@Override
	public void glFramebufferTexture(int target, int attachment, int texture, int level)
	{
		GL32.glFramebufferTexture(target, attachment, texture, level);
	}
	
	@Override
	public int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname)
	{
		return GL30.glGetFramebufferAttachmentParameteri(target, attachment, pname);
	}
	
	// ===================== STATE OPERATIONS =====================
	
	@Override
	public void glEnable(int cap)
	{
		GL11.glEnable(cap);
	}
	
	@Override
	public void glDisable(int cap)
	{
		GL11.glDisable(cap);
	}
	
	@Override
	public void glDisablei(int target, int index) { GL30.glDisablei(target, index); }
	
	@Override
	public void glEnablei(int target, int index) { GL30.glEnablei(target, index); }
	
	@Override
	public boolean glIsEnabled(int cap)
	{
		return GL11.glIsEnabled(cap);
	}
	
	@Override
	public void glBlendFunc(int sfactor, int dfactor)
	{
		GL11.glBlendFunc(sfactor, dfactor);
	}
	
	@Override
	public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha)
	{
		GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
	}
	
	@Override
	public void glBlendEquation(int mode)
	{
		GL14.glBlendEquation(mode);
	}
	
	@Override
	public void glBlendEquationSeparate(int modeRGB, int modeAlpha)
	{
		GL20.glBlendEquationSeparate(modeRGB, modeAlpha);
	}
	
	@Override
	public void glDepthFunc(int func)
	{
		GL11.glDepthFunc(func);
	}
	
	@Override
	public void glDepthMask(boolean flag)
	{
		GL11.glDepthMask(flag);
	}
	
	@Override
	public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha)
	{
		GL11.glColorMask(red, green, blue, alpha);
	}
	
	@Override
	public void glViewport(int x, int y, int width, int height)
	{
		GL11.glViewport(x, y, width, height);
	}
	
	@Override
	public void glClear(int mask)
	{
		GL11.glClear(mask);
	}
	
	@Override
	public void glClearColor(float red, float green, float blue, float alpha)
	{
		GL11.glClearColor(red, green, blue, alpha);
	}
	
	@Override
	public void glClearDepth(double depth)
	{
		GL11.glClearDepth(depth);
	}
	
	@Override
	public void glCullFace(int mode)
	{
		GL11.glCullFace(mode);
	}
	
	@Override
	public void glPolygonMode(int face, int mode)
	{
		GL11.glPolygonMode(face, mode);
	}
	
	@Override
	public void glStencilFunc(int func, int ref, int mask)
	{
		GL11.glStencilFunc(func, ref, mask);
	}
	
	@Override
	public int glGetError()
	{
		return GL11.glGetError();
	}
	
	@Override
	public void glColor4f(float v, float v1, float v2, float v3)
	{
		GL11.glColor4f(v, v1, v2, v3);
	}
	
	// ===================== COMPATIBILITY PROFILE (GL1.x) =====================
	
	@Override
	public void glMatrixMode(int mode)
	{
		GL11.glMatrixMode(mode);
	}
	
	@Override
	public void glLoadMatrixf(FloatBuffer m)
	{
		GL11.glLoadMatrix(m);
	}
	
	// ===================== Fog =========================
	
	@Override
	public void glFogf(int pname, float params) { GL11.glFogf(pname, params); }
	
	// ===================== MISC GL =====================
	
	@Override
	public int glGetInteger(int pname)
	{
		return GL11.glGetInteger(pname);
	}
	
	@Override
	public void glGetIntegerv(int pname, int[] params)
	{
		IntBuffer buf = MemoryUtilities.memAllocInt(Math.max(params.length, 16));
		GL11.glGetInteger(pname, buf);
		buf.get(params, 0, params.length);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public void glGetFloatv(int pname, float[] params)
	{
		FloatBuffer buf = MemoryUtilities.memAllocFloat(Math.max(params.length, 16));
		GL11.glGetFloat(pname, buf);
		buf.get(params, 0, params.length);
		MemoryUtilities.memFree(buf);
	}
	
	@Override
	public boolean glGetBoolean(int pname)
	{
		return GL11.glGetBoolean(pname);
	}
	
	@Override
	public String glGetString(int pname)
	{
		return GL11.glGetString(pname);
	}
	
	@Override
	public int glGetAttribLocation(int program, CharSequence name)
	{
		return GL20.glGetAttribLocation(program, name);
	}
	
	// ===================== MEMORY STACK OPERATIONS =====================
	
	@Override
	public AbstractMemoryStack stackPush()
	{
		return new LWJGL2MemoryStack(MemoryStack.stackPush());
	}
	
	// ===================== NATIVE MEMORY OPERATIONS =====================
	
	@Override
	public long nmemAlloc(long size)
	{
		return MemoryUtilities.nmemAlloc(size);
	}
	
	@Override
	public long nmemCalloc(long count, long size)
	{
		return MemoryUtilities.nmemCalloc(count, size);
	}
	
	@Override
	public long nmemAlignedAlloc(long alignment, long size)
	{
		// Ported from FalsePattern's lwjgl2-celeritas LegacyMemoryAdapter
		int required = 8;
		alignment = Math.max(alignment, 8);
		long prefixLength = Math.max(alignment, required) + required;
		long capacity = size + prefixLength;
		long addr = MemoryUtilities.nmemAlloc(capacity);
		if (addr == 0) return 0;
		long shiftBy = alignment - (addr % alignment);
		if (shiftBy < required)
		{
			shiftBy += alignment;
		}
		long finalAddr = addr + shiftBy;
		MemoryUtilities.memPutLong(finalAddr - 8, addr);
		return finalAddr;
	}
	
	@Override
	public long nmemRealloc(long ptr, long size)
	{
		return MemoryUtilities.nmemRealloc(ptr, size);
	}
	
	@Override
	public void nmemFree(long ptr)
	{
		MemoryUtilities.nmemFree(ptr);
	}
	
	@Override
	public void nmemAlignedFree(long ptr)
	{
		if (ptr == 0) return;
		long realAddr = MemoryUtilities.memGetLong(ptr - 8);
		MemoryUtilities.nmemFree(realAddr);
	}
	
	@Override
	public ByteBuffer memAlloc(int size)
	{
		return MemoryUtilities.memAlloc(size);
	}
	
	@Override
	public ByteBuffer memCalloc(int size)
	{
		return MemoryUtilities.memCalloc(size);
	}
	
	@Override
	public ByteBuffer memRealloc(ByteBuffer buffer, int size)
	{
		return MemoryUtilities.memRealloc(buffer, size);
	}
	
	@Override
	public void memFree(Buffer buffer)
	{
		MemoryUtilities.memFree(buffer);
	}
	
	@Override
	public ByteBuffer memByteBuffer(long address, int capacity)
	{
		return MemoryUtilities.memByteBuffer(address, capacity);
	}
	
	@Override
	public long memAddress(Buffer buffer)
	{
		return MemoryUtilities.memAddress(buffer);
	}
	
	@Override
	public long memAddress(Buffer buffer, int position)
	{
		if (buffer == null)
		{
			return position;
		}
		return MemoryUtilities.memAddress0(buffer) + position;
	}
	
	@Override
	public void memSet(long address, int value, long bytes)
	{
		MemoryUtilities.memSet(address, value, bytes);
	}
	
	@Override
	public void memCopy(long src, long dst, long bytes)
	{
		MemoryUtilities.memCopy(src, dst, bytes);
	}
	
	@Override
	public void memCopy(ByteBuffer src, ByteBuffer dst)
	{
		MemoryUtilities.memCopy(src, dst);
	}
	
	@Override
	public void memPutByte(long address, byte value)
	{
		MemoryUtilities.memPutByte(address, value);
	}
	
	@Override
	public void memPutShort(long address, short value)
	{
		MemoryUtilities.memPutShort(address, value);
	}
	
	@Override
	public void memPutInt(long address, int value)
	{
		MemoryUtilities.memPutInt(address, value);
	}
	
	@Override
	public void memPutFloat(long address, float value)
	{
		MemoryUtilities.memPutFloat(address, value);
	}
	
	@Override
	public void memPutLong(long address, long value)
	{
		MemoryUtilities.memPutLong(address, value);
	}
	
	@Override
	public void memPutAddress(long address, long value)
	{
		MemoryUtilities.memPutAddress(address, value);
	}
	
	@Override
	public byte memGetByte(long address)
	{
		return MemoryUtilities.memGetByte(address);
	}
	
	@Override
	public short memGetShort(long address)
	{
		return MemoryUtilities.memGetShort(address);
	}
	
	@Override
	public int memGetInt(long address)
	{
		return MemoryUtilities.memGetInt(address);
	}
	
	@Override
	public float memGetFloat(long address)
	{
		return MemoryUtilities.memGetFloat(address);
	}
	
	@Override
	public long memGetLong(long address)
	{
		return MemoryUtilities.memGetLong(address);
	}
	
	@Override
	public long memGetAddress(long address)
	{
		return MemoryUtilities.memGetAddress(address);
	}
	
	@Override
	public ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity)
	{
		long address = MemoryUtilities.memAddress(buffer) + offset;
		return MemoryUtilities.memByteBuffer(address, capacity);
	}
	
	@Override
	public boolean isRunningOnRenderThread()
	{
		try
		{
			return Display.getDrawable().isCurrent();
		}
		catch (LWJGLException e)
		{
			return false;
		}
	}
	
}
