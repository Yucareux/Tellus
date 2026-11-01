package com.seibel.distanthorizons.lwjgl.lwjgl3;

import com.seibel.distanthorizons.lwjgl.*;
import com.seibel.distanthorizons.lwjgl.GL33;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.*;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.io.PrintStream;
import java.nio.*;

/**
 * LWJGL3 implementation of {@link ILWJGLService}.
 */
// TODO why is this a record?
public class LWJGL3Service implements ILWJGLService
{
    private static final Logger LOGGER = LogManager.getLogger("DistantHorizons/LWJGL3Service");
    private static final LWJGL3DebugSupport debugSupport = new LWJGL3DebugSupport();
	private static final Thread RENDER_THREAD = Thread.currentThread();
	
	private final VAOMode vaoMode;
	private final TimerQueryMode timerQueryMode;
	private final DebugMode debugMode;
	private final VertexAttribIMode vertexAttribIMode;
	
	private LWJGL3Service(
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
	
	
	
	// ===================== CAPABILITIES =====================
	
	@Override
    public boolean isOpenGLVersionSupported(int major, int minor) 
	{
        GLCapabilities caps = GL.getCapabilities();
        switch (major * 10 + minor) 
        {
            case 11: return caps.OpenGL11;
            case 12: return caps.OpenGL12;
            case 13: return caps.OpenGL13;
            case 14: return caps.OpenGL14;
            case 15: return caps.OpenGL15;
            case 20: return caps.OpenGL20;
            case 21: return caps.OpenGL21;
            case 30: return caps.OpenGL30;
            case 31: return caps.OpenGL31;
            case 32: return caps.OpenGL32;
            case 33: return caps.OpenGL33;
            case 40: return caps.OpenGL40;
            case 41: return caps.OpenGL41;
            case 42: return caps.OpenGL42;
            case 43: return caps.OpenGL43;
            case 44: return caps.OpenGL44;
            case 45: return caps.OpenGL45;
            case 46: return caps.OpenGL46;
            default: return false;
        }
    }

    @Override
    public boolean isExtensionSupported(EGLExtension extension) 
    {
        GLCapabilities caps = GL.getCapabilities();
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
	        case ARB_instanced_arrays: return caps.GL_ARB_instanced_arrays;
	        case ARB_compatibility: return caps.GL_ARB_compatibility;
			
	        default: throw new UnsupportedOperationException("No logic defined for extension ["+extension+"].");
        }
    }

    @Override
    public boolean isFeatureSupported(EGLFeature feature)
    {
        GLCapabilities caps = GL.getCapabilities();
        switch (feature) 
        {
	        case glBufferStorage: return caps.glBufferStorage != 0L;
	        case glBindVertexBuffer: return caps.glBindVertexBuffer != 0L;
			
	        default: throw new UnsupportedOperationException("No logic defined for feature ["+feature+"].");
        }
    }

    @Override
    public int getPointerSize() {
        return Pointer.POINTER_SIZE;
    }

	
	
	// ===================== BUFFER OPERATIONS =====================

    @Override
    public int glGenBuffers() {
        return GL15C.glGenBuffers();
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GL15C.glDeleteBuffers(buffer);
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GL15C.glBindBuffer(target, buffer);
    }

    @Override
    public void glBufferData(int target, long size, int usage) {
        GL15C.glBufferData(target, size, usage);
    }

    @Override
    public void glBufferData(int target, ByteBuffer data, int usage) {
        GL15C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, long size, long data, int usage) {
        GL15C.nglBufferData(target, size, data, usage);
    }
	
	@Override
	public void glBufferData(int target, int[] data, int usage)
	{
		GL15C.glBufferData(target, data, usage);	
	}
	
	@Override
	public void glBufferData(int target, float[] data, int usage)
	{
		GL15C.glBufferData(target, data, usage);
	}
	
	@Override
    public void glBufferStorage(int target, long size, int flags) {
        GL44C.glBufferStorage(target, size, flags);
    }
	
	@Override
	public void glBufferStorage(int target, ByteBuffer bb, int flags) {
		GL44C.glBufferStorage(target, bb, flags);
	}
	
	@Override
	public void glBufferSubData(int target, long size, ByteBuffer data)
	{
		GL15.glBufferSubData(target, size, data);
	}
	
    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int flags) {
        return GL30C.glMapBufferRange(target, offset, length, flags);
    }

    @Override
    public long nglMapBuffer(int target, int access) {
        return GL15C.nglMapBuffer(target, access);
    }

    @Override
    public ByteBuffer glMapBuffer(int target, int access) {
        return GL15C.glMapBuffer(target, access, null);
    }

    @Override
    public void glUnmapBuffer(int target) {
        GL15C.glUnmapBuffer(target);
    }

    @Override
    public void glFlushMappedBufferRange(int target, long offset, long length) {
        GL30C.glFlushMappedBufferRange(target, offset, length);
    }

    @Override
    public void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        GL31C.glCopyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        GL30C.glBindBufferBase(target, index, buffer);
    }
	
	@Override
	public void glBindVertexBuffer(int bindingindex, int buffer, int offset, int stride)
	{
		GL43C.glBindVertexBuffer(bindingindex, buffer, offset, stride);
	}
	
	
	
	private enum VAOMode 
	{
        CORE 
        {
            @Override public int gen() { return GL30C.glGenVertexArrays(); }
            @Override public void delete(int array) { GL30C.glDeleteVertexArrays(array); }
            @Override public void bind(int array) { GL30C.glBindVertexArray(array); }
        },
        ARB 
	        {
            @Override public int gen() { return ARBVertexArrayObject.glGenVertexArrays(); }
            @Override public void delete(int array) { ARBVertexArrayObject.glDeleteVertexArrays(array); }
            @Override public void bind(int array) { ARBVertexArrayObject.glBindVertexArray(array); }
        },
        APPLE 
        {
            @Override 
            public int gen() 
            {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) 
                {
                    IntBuffer buf = stack.callocInt(1);
                    JNI.callPV(1, MemoryUtil.memAddress(buf), glGenVertexArraysAPPLE);
                    return buf.get(0);
                }
            }
            @Override 
            public void delete(int array) 
            {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) 
                {
                    IntBuffer buf = stack.ints(array);
                    JNI.callPV(1, MemoryUtil.memAddress(buf), glDeleteVertexArraysAPPLE);
                }
            }
            @Override 
            public void bind(int array) { JNI.callV(array, glBindVertexArrayAPPLE); }
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
    private enum TimerQueryMode { CORE, ARB, NONE }
    private enum DebugMode { KHR, NONE }
    private enum VertexAttribIMode { CORE, EXT, NONE }


    // Cached function addresses for APPLE VAO extensions
    private static final long glGenVertexArraysAPPLE = GL.getFunctionProvider().getFunctionAddress("glGenVertexArraysAPPLE");
    private static final long glDeleteVertexArraysAPPLE = GL.getFunctionProvider().getFunctionAddress("glDeleteVertexArraysAPPLE");
    private static final long glBindVertexArrayAPPLE = GL.getFunctionProvider().getFunctionAddress("glBindVertexArrayAPPLE");

	
	
    public static LWJGL3Service create() 
    {
        GLCapabilities caps = GL.getCapabilities();

        VAOMode vaoMode;
	    
	    if (caps.OpenGL30)
	    {
		    vaoMode = VAOMode.CORE;
	    }
	    else if (caps.GL_ARB_vertex_array_object)
	    {
		    vaoMode = VAOMode.ARB;
	    }
	    else if (glBindVertexArrayAPPLE != 0)
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

        if (caps.GL_KHR_debug 
	        || caps.OpenGL43)
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

        return new LWJGL3Service(vaoMode, timerQueryMode, debugMode, vertexAttribIMode);
    }

	
	
    @Override
    public int glGenVertexArrays() {
        return vaoMode.gen();
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        vaoMode.delete(array);
    }

    @Override
    public void glBindVertexArray(int array) {
        vaoMode.bind(array);
    }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        GL20C.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) 
    {
        switch (vertexAttribIMode)
        {
            case CORE: 
				GL30C.glVertexAttribIPointer(index, size, type, stride, pointer);
				break;
            case EXT: 
				EXTGPUShader4.glVertexAttribIPointerEXT(index, size, type, stride, pointer);
				break;
            case NONE: 
				throw new UnsupportedOperationException("glVertexAttribIPointer not supported");
        }
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        GL20C.glEnableVertexAttribArray(index);
    }
	
	@Override
	public void glDisableVertexAttribArray(int index)
	{
		GL20C.glDisableVertexAttribArray(index);
	}
	
	@Override
	public void glVertexAttribDivisor(int index, int divisor)
	{
		GL33C.glVertexAttribDivisor(index, divisor);
	}
	
	@Override
	public void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset)
	{
		GL43C.glVertexAttribFormat(attribindex, size, type, normalized, relativeoffset);
	}
	
	@Override
	public void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset)
	{
		GL43C.glVertexAttribIFormat(attribindex, size, type, relativeoffset);
	}
	
	@Override
	public void glVertexAttribBinding(int attribindex, int bindingindex)
	{
		GL43C.glVertexAttribBinding(attribindex, bindingindex);
	}
	
	// ===================== SHADER OPERATIONS =====================

    @Override
    public int glCreateShader(int type) {
        return GL20C.glCreateShader(type);
    }

    @Override
    public void glShaderSource(int shader, CharSequence source) {
        GL20C.glShaderSource(shader, source);
    }

    @Override
    public void glShaderSourceSafe(int shader, CharSequence source) {
        // AMD driver workaround: pass null for string length to force null-terminator reliance.
        // Some AMD drivers don't receive or interpret the length correctly, resulting in an
        // access violation when the driver tries to read past the string memory.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, true);
            PointerBuffer pointers = stack.mallocPointer(1);
            pointers.put(sourceBuffer);
            GL20C.nglShaderSource(shader, 1, pointers.address0(), 0);
            APIUtil.apiArrayFree(pointers.address0(), 1);
        }
    }

    @Override
    public void glCompileShader(int shader) {
        GL20C.glCompileShader(shader);
    }

    @Override
    public String glGetShaderInfoLog(int shader, int maxLength) {
        // LWJGL3 doesn't need maxLength, but we accept it for API compatibility
        return GL20C.glGetShaderInfoLog(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GL20C.glGetShaderi(shader, pname);
    }

    @Override
    public void glDeleteShader(int shader) {
        GL20C.glDeleteShader(shader);
    }

    @Override
    public int glCreateProgram() {
        return GL20C.glCreateProgram();
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GL20C.glAttachShader(program, shader);
    }

    @Override
    public void glLinkProgram(int program) {
        GL20C.glLinkProgram(program);
    }

    @Override
    public String glGetProgramInfoLog(int program, int maxLength) {
        return GL20C.glGetProgramInfoLog(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GL20C.glGetProgrami(program, pname);
    }

    @Override
    public void glUseProgram(int program) {
        GL20C.glUseProgram(program);
    }

    @Override
    public void glDeleteProgram(int program) {
        GL20C.glDeleteProgram(program);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        GL20C.glBindAttribLocation(program, index, name);
    }

    @Override
    public void glBindFragDataLocation(int program, int colorNumber, CharSequence name) {
        GL30C.glBindFragDataLocation(program, colorNumber, name);
    }

    // ===================== UNIFORM OPERATIONS =====================

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GL20C.glGetUniformLocation(program, name);
    }

    @Override
    public int glGetUniformBlockIndex(int program, CharSequence name) {
        return GL31C.glGetUniformBlockIndex(program, name);
    }

    @Override
    public void glUniformBlockBinding(int program, int blockIndex, int blockBinding) {
        GL31C.glUniformBlockBinding(program, blockIndex, blockBinding);
    }

    @Override
    public void glUniform1f(int location, float v0) {
        GL20C.glUniform1f(location, v0);
    }

    @Override
    public void glUniform1i(int location, int v0) {
        GL20C.glUniform1i(location, v0);
    }

    @Override
    public void glUniform1fv(int location, FloatBuffer value) {
        GL20C.glUniform1fv(location, value);
    }

    @Override
    public void glUniform2i(int location, int v0, int v1) {
        GL20C.glUniform2i(location, v0, v1);
    }
	
	@Override
	public void glUniform2f(int location, int v0, int v1)
	{
		GL20C.glUniform2f(location, v0, v1);
	}
	
	@Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        GL20C.glUniform3f(location, v0, v1, v2);
    }
	
	@Override
	public void glUniform3i(int location, int v0, int v1, int v2)
	{
		GL20C.glUniform3i(location, v0, v1, v2);
	}
	
	@Override
    public void glUniform3fv(int location, FloatBuffer value) {
        GL20C.glUniform3fv(location, value);
    }

    @Override
    public void glUniform3fv(int location, float[] value) {
        GL20C.glUniform3fv(location, value);
    }
	
	@Override
	public void glUniform4f(int location, float v0, float v1, float v2, float v3)
	{
		GL20C.glUniform4f(location, v0, v1, v2, v3);
	}
	
	@Override
    public void glUniform4fv(int location, FloatBuffer value) {
        GL20C.glUniform4fv(location, value);
    }

    @Override
    public void glUniform4fv(int location, float[] value) {
        GL20C.glUniform4fv(location, value);
    }

    @Override
    public void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value) {
        GL20C.glUniformMatrix3fv(location, transpose, value);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
        GL20C.glUniformMatrix4fv(location, transpose, value);
    }
	
	@Override
	public void glDrawArrays(int mode, int first, int count)
	{
		GL11C.glDrawArrays(mode, first, count);
	}
	
	@Override
	public void glDrawElements(int mode, int count, int type, int indices)
	{
		GL11C.glDrawElements(mode, count, type, indices);
	}
	
	// ===================== DRAW OPERATIONS =====================

    @Override
    public void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex) {
        GL32C.glDrawElementsBaseVertex(mode, count, type, indices, basevertex);
    }
	
	@Override
	public void glDrawElementsInstanced(int mode, int count, int type, int indices, int instancecount)
	{
		GL32C.glDrawElementsInstanced(mode, count, type, indices, instancecount);
	}
	
	@Override
    public void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        GL32C.nglMultiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex);
    }

    @Override
    public void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        GL43C.glMultiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
    }

    // ===================== SYNC OPERATIONS =====================

    @Override
    public long glFenceSync(int condition, int flags) {
        return GL32C.glFenceSync(condition, flags);
    }

    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        return GL32C.glClientWaitSync(sync, flags, timeout);
    }

    @Override
    public int glGetSynci(long sync, int pname, IntBuffer length) {
        return GL32C.glGetSynci(sync, pname, length);
    }

    @Override
    public void glWaitSync(long sync, int flags, long timeout) {
        GL32C.glWaitSync(sync, flags, timeout);
    }

    @Override
    public void glDeleteSync(long sync) {
        GL32C.glDeleteSync(sync);
    }
	
	@Override
	public void glFlush()
	{
		GL11C.glFlush();
	}
	
	// ===================== QUERY OPERATIONS =====================

    @Override
    public int glGenQueries() {
        return GL15C.glGenQueries();
    }

    @Override
    public void glDeleteQueries(int query) {
        GL15C.glDeleteQueries(query);
    }

    @Override
    public void glQueryCounter(int id, int target)
    {
	    switch (timerQueryMode)
	    {
		    case CORE:
			    GL33C.glQueryCounter(id, target);
				break;
		    case ARB:
			    ARBTimerQuery.glQueryCounter(id, target);
				break;
		    case NONE:
			    /* no-op */
		        break;
	    }
    }

    @Override
    public long glGetQueryObjectui64(int id, int pname)
    {
	    switch (timerQueryMode)
	    {
		    case CORE:
			    return GL33C.glGetQueryObjectui64(id, pname);
		    case ARB:
			    return ARBTimerQuery.glGetQueryObjectui64(id, pname);
				
			default:
		    case NONE:
			    return 0L;
	    }
    }

    // ===================== DEBUG OPERATIONS =====================

    @Override
    public PrintStream getDebugStream() { return APIUtil.DEBUG_STREAM; }

    @Override
    public int setupDebugCallback(IDebugMessageHandler handler) {
        return debugSupport.setupDebugCallback(handler);
    }

    @Override
    public void disableDebugCallback() {
        debugSupport.disableDebugCallback();
    }

    @Override
    public void glObjectLabel(int identifier, int name, CharSequence label) {
        if (debugMode == DebugMode.KHR) {
            KHRDebug.glObjectLabel(identifier, name, label);
        }
    }

    @Override
    public void glPushDebugGroup(int source, int id, CharSequence message) {
        if (debugMode == DebugMode.KHR) {
            KHRDebug.glPushDebugGroup(source, id, message);
        }
    }

    @Override
    public void glPopDebugGroup() {
        if (debugMode == DebugMode.KHR) {
            KHRDebug.glPopDebugGroup();
        }
    }

    // ===================== TEXTURE OPERATIONS =====================

    @Override
    public int glGenTextures() {
        return GL11C.glGenTextures();
    }

    @Override
    public void glGenTextures(int[] textures) {
        GL11C.glGenTextures(textures);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GL11C.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteTextures(int[] textures) {
        GL11C.glDeleteTextures(textures);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        GL11C.glBindTexture(target, texture);
    }

    @Override
    public void glActiveTexture(int texture) {
        GL13C.glActiveTexture(texture);
    }

    @Override
    public int glGetTexLevelParameteri(int target, int level, int pname) {
        return GL11C.glGetTexLevelParameteri(target, level, pname);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                    int x, int y, int width, int height) {
        GL11C.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GL11C.glPixelStorei(pname, param);
    }
	
	@Override
	public void glTexParameteri(int target, int pname, int param)
	{
		GL11C.glTexParameteri(target, pname, param);
	}
	
	@Override
	public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels)
	{
		GL11C.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
	}
	
	@Override
	public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels)
	{
		GL11C.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
	}
	
	@Override
	public void glGenerateMipmap(int target)
	{
		GL30C.glGenerateMipmap(target);
	}
	
	@Override
	public boolean glIsTexture(int texture)
	{
		return GL11C.glIsTexture(texture);
	}
	
	@Override
	public void glBindSampler(int unit, int sampler)
	{
		GL44C.glBindSampler(unit, sampler);
	}
	
	// ===================== FRAMEBUFFER OPERATIONS =====================

    @Override
    public int glGenFramebuffers() {
        return GL30C.glGenFramebuffers();
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        GL30C.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        GL30C.glBindFramebuffer(target, framebuffer);
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        return GL30C.glCheckFramebufferStatus(target);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GL30C.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }
	
	@Override
	public void glFramebufferTexture(int target, int attachment, int texture, int level)
	{
		GL32C.glFramebufferTexture(target, attachment, texture, level);
	}
	
	@Override
	public int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname)
	{
		return GL30C.glGetFramebufferAttachmentParameteri(target, attachment, pname);
	}
	
	// ===================== STATE OPERATIONS =====================

    @Override
    public void glEnable(int cap) {
        GL11C.glEnable(cap);
    }

    @Override
    public void glDisable(int cap) {
        GL11C.glDisable(cap);
    }
	
	@Override
	public void glDisablei(int target, int index) { GL33C.glDisablei(target, index); }
	
	@Override 
	public void glEnablei(int target, int index) { GL33C.glEnablei(target, index); }
	
	@Override
	public boolean glIsEnabled(int cap)
	{
		return GL11C.glIsEnabled(cap);
	}
	
	@Override
    public void glBlendFunc(int sfactor, int dfactor) {
        GL11C.glBlendFunc(sfactor, dfactor);
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        GL14C.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }
	
	@Override
	public void glBlendEquation(int mode)
	{
		GL14C.glBlendEquation(mode);
	}
	
	@Override
	public void glBlendEquationSeparate(int modeRGB, int modeAlpha)
	{
		GL20C.glBlendEquationSeparate(modeRGB, modeAlpha);
	}
	
	@Override
    public void glDepthFunc(int func) {
        GL11C.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GL11C.glDepthMask(flag);
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        GL11C.glColorMask(red, green, blue, alpha);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GL11C.glViewport(x, y, width, height);
    }

    @Override
    public void glClear(int mask) {
        GL11C.glClear(mask);
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        GL11C.glClearColor(red, green, blue, alpha);
    }
	
	@Override
	public void glClearDepth(double depth)
	{
		GL11C.glClearDepth(depth);	
	}
	
	@Override
	public void glCullFace(int mode)
	{
		GL11C.glCullFace(mode);
	}
	
	@Override
	public void glPolygonMode(int face, int mode)
	{
		GL11C.glPolygonMode(face, mode);
	}
	
	@Override
	public void glStencilFunc(int func, int ref, int mask)
	{
		GL11C.glStencilFunc(func, ref, mask);
	}
	
	@Override
    public int glGetError() {
        return GL11C.glGetError();
    }
	
	@Override
	public void glColor4f(float v, float v1, float v2, float v3)
	{
		GL11.glColor4f(v, v1, v2, v3);
	}
	
	// ===================== COMPATIBILITY PROFILE (GL1.x) =====================

    @Override
    public void glMatrixMode(int mode) {
        GL11.glMatrixMode(mode);
    }

    @Override
    public void glLoadMatrixf(FloatBuffer m) {
        GL11.glLoadMatrixf(m);
    }
	
	// ===================== Fog =========================
	
	@Override
	public void glFogf(int pname, float params) { GL32.glFogf(pname, params); }
	
	// ===================== MISC GL =====================

    @Override
    public int glGetInteger(int pname) {
        return GL11C.glGetInteger(pname);
    }

    @Override
    public void glGetIntegerv(int pname, int[] params) {
        GL11C.glGetIntegerv(pname, params);
    }
	
	@Override
	public void glGetFloatv(int pname, float[] params)
	{
		GL11C.glGetFloatv(pname, params);
	}
	
	@Override
    public boolean glGetBoolean(int pname) {
        return GL11C.glGetBoolean(pname);
    }

    @Override
    public String glGetString(int pname) {
        return GL11C.glGetString(pname);
    }

    @Override
    public int glGetAttribLocation(int program, CharSequence name) {
        return GL20C.glGetAttribLocation(program, name);
    }

    // ===================== MEMORY STACK OPERATIONS =====================

    @Override
    public AbstractMemoryStack stackPush() {
        return new LWJGL3MemoryStack(org.lwjgl.system.MemoryStack.stackPush());
    }

    // ===================== NATIVE MEMORY OPERATIONS =====================

    @Override
    public long nmemAlloc(long size) {
        return MemoryUtil.nmemAlloc(size);
    }

    @Override
    public long nmemCalloc(long count, long size) {
        return MemoryUtil.nmemCalloc(count, size);
    }

    @Override
    public long nmemAlignedAlloc(long alignment, long size) {
        return MemoryUtil.nmemAlignedAlloc(alignment, size);
    }

    @Override
    public long nmemRealloc(long ptr, long size) {
        return MemoryUtil.nmemRealloc(ptr, size);
    }

    @Override
    public void nmemFree(long ptr) {
        MemoryUtil.nmemFree(ptr);
    }

    @Override
    public void nmemAlignedFree(long ptr) {
        MemoryUtil.nmemAlignedFree(ptr);
    }

    @Override
    public ByteBuffer memAlloc(int size) {
        return MemoryUtil.memAlloc(size);
    }

    @Override
    public ByteBuffer memCalloc(int size) {
        return MemoryUtil.memCalloc(size);
    }

    @Override
    public ByteBuffer memRealloc(ByteBuffer buffer, int size) {
        return MemoryUtil.memRealloc(buffer, size);
    }

    @Override
    public void memFree(Buffer buffer) {
        MemoryUtil.memFree(buffer);
    }

    @Override
    public ByteBuffer memByteBuffer(long address, int capacity) {
        return MemoryUtil.memByteBuffer(address, capacity);
    }

    @Override
    public long memAddress(Buffer buffer) {
        return MemoryUtil.memAddress(buffer);
    }

    @Override
    public long memAddress(Buffer buffer, int position) {
        // Generic Buffer doesn't have a positioned memAddress in LWJGL3, compute manually
        // Get base address and add position offset based on element size
        long base = MemoryUtil.memAddress(buffer);
        int elementSize;
        if (buffer instanceof ByteBuffer) {
            elementSize = 1;
        } else if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) {
            elementSize = 2;
        } else if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) {
            elementSize = 4;
        } else if (buffer instanceof LongBuffer || buffer instanceof DoubleBuffer) {
            elementSize = 8;
        } else {
            throw new IllegalArgumentException("Unsupported buffer type: " + buffer.getClass());
        }
        return base + ((long) position * elementSize);
    }

    @Override
    public void memSet(long address, int value, long bytes) {
        MemoryUtil.memSet(address, value, bytes);
    }

    @Override
    public void memCopy(long src, long dst, long bytes) {
        MemoryUtil.memCopy(src, dst, bytes);
    }

    @Override
    public void memPutByte(long address, byte value) {
        MemoryUtil.memPutByte(address, value);
    }

    @Override
    public void memPutShort(long address, short value) {
        MemoryUtil.memPutShort(address, value);
    }

    @Override
    public void memPutInt(long address, int value) {
        MemoryUtil.memPutInt(address, value);
    }

    @Override
    public void memPutFloat(long address, float value) {
        MemoryUtil.memPutFloat(address, value);
    }

    @Override
    public void memPutLong(long address, long value) {
        MemoryUtil.memPutLong(address, value);
    }

    @Override
    public void memPutAddress(long address, long value) {
        MemoryUtil.memPutAddress(address, value);
    }

    @Override
    public byte memGetByte(long address) {
        return MemoryUtil.memGetByte(address);
    }

    @Override
    public short memGetShort(long address) {
        return MemoryUtil.memGetShort(address);
    }

    @Override
    public int memGetInt(long address) {
        return MemoryUtil.memGetInt(address);
    }

    @Override
    public float memGetFloat(long address) {
        return MemoryUtil.memGetFloat(address);
    }

    @Override
    public long memGetLong(long address) {
        return MemoryUtil.memGetLong(address);
    }

    @Override
    public long memGetAddress(long address) {
        return MemoryUtil.memGetAddress(address);
    }

    @Override
    public ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity) {
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }
	
	@Override
	public boolean isRunningOnRenderThread()
	{
		return Thread.currentThread() == RENDER_THREAD;
	}
}
