package com.seibel.distanthorizons.lwjgl;

import java.io.PrintStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL2/LWJGL3 abstraction.
 */
public interface ILWJGLService 
{
	// ===================== CAPABILITIES =====================
	//region
	
	boolean isOpenGLVersionSupported(int major, int minor);
	boolean isExtensionSupported(EGLExtension extension);
	boolean isFeatureSupported(EGLFeature feature);
	int getPointerSize();
	
	//endregion
	
	
	
	// ===================== BUFFER OPERATIONS =====================
	//region
	
	int glGenBuffers();
	void glDeleteBuffers(int buffer);
	void glBindBuffer(int target, int buffer);
	void glBufferData(int target, long size, int usage);
	void glBufferData(int target, ByteBuffer data, int usage);
	void glBufferData(int target, long size, long data, int usage);
	void glBufferData(int target, int[] data, int usage);
	void glBufferData(int target, float[] data, int usage);
	void glBufferStorage(int target, ByteBuffer bb, int flags);
	void glBufferStorage(int target, long size, int flags);
	void glBufferSubData(int target, long size, ByteBuffer data);
	ByteBuffer glMapBufferRange(int target, long offset, long length, int flags);
	long nglMapBuffer(int target, int access);
	ByteBuffer glMapBuffer(int target, int access);
	void glUnmapBuffer(int target);
	void glFlushMappedBufferRange(int target, long offset, long length);
	void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size);
	void glBindBufferBase(int target, int index, int buffer);
	void glBindVertexBuffer(int bindingindex, int buffer, int offset, int stride);
	
	//endregion
	
	
	
	// ===================== VAO OPERATIONS =====================
	//region
	
	int glGenVertexArrays();
	void glDeleteVertexArrays(int array);
	void glBindVertexArray(int array);
	void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
	void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer);
	void glEnableVertexAttribArray(int index);
	void glDisableVertexAttribArray(int index);
	void glVertexAttribDivisor(int index, int divisor);
	void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset);
	void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset);
	void glVertexAttribBinding(int attribindex, int bindingindex);
	
	//endregion
	
	
	
	// ===================== SHADER OPERATIONS =====================
	//region
	
	int glCreateShader(int type);
	void glShaderSource(int shader, CharSequence source);
	
	/**
	 * Identical in function to {@link #glShaderSource(int, CharSequence)} but
	 * passes a null pointer for string length to force the driver to rely on the null
	 * terminator for string length. This is a workaround for an apparent flaw with some
	 * AMD drivers that don't receive or interpret the length correctly, resulting in
	 * an access violation when the driver tries to read past the string memory.
	 *
	 * <p>Hat tip to fewizz for the find and the fix.
	 *
	 * @see <a href="https://github.com/grondag/canvas/commit/820bf754092ccaf8d0c169620c2ff575722d7d96">Original Canvas commit</a>
	 */
	void glShaderSourceSafe(int shader, CharSequence source);
	void glCompileShader(int shader);
	String glGetShaderInfoLog(int shader, int maxLength);
	int glGetShaderi(int shader, int pname);
	void glDeleteShader(int shader);
	
	int glCreateProgram();
	void glAttachShader(int program, int shader);
	void glLinkProgram(int program);
	String glGetProgramInfoLog(int program, int maxLength);
	int glGetProgrami(int program, int pname);
	void glUseProgram(int program);
	void glDeleteProgram(int program);
	void glBindAttribLocation(int program, int index, CharSequence name);
	void glBindFragDataLocation(int program, int colorNumber, CharSequence name);
	
	//endregion
	
	
	
	// ===================== UNIFORM OPERATIONS =====================
	//region
	
	int glGetUniformLocation(int program, CharSequence name);
	int glGetUniformBlockIndex(int program, CharSequence name);
	void glUniformBlockBinding(int program, int blockIndex, int blockBinding);
	void glUniform1f(int location, float v0);
	void glUniform1i(int location, int v0);
	void glUniform1fv(int location, FloatBuffer value);
	void glUniform2i(int location, int v0, int v1);
	void glUniform2f(int location, int v0, int v1);
	void glUniform3f(int location, float v0, float v1, float v2);
	void glUniform3i(int location, int v0, int v1, int v2);
	void glUniform3fv(int location, FloatBuffer value);
	void glUniform3fv(int location, float[] value);
	void glUniform4f(int location, float v0, float v1, float v2, float v3);
	void glUniform4fv(int location, FloatBuffer value);
	void glUniform4fv(int location, float[] value);
	void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value);
	void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value);
	
	//endregion
	
	
	
	// ===================== DRAW OPERATIONS =====================
	//region
	
	void glDrawArrays(int mode, int first, int count);
	void glDrawElements(int mode, int count, int type, int indices);
	void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex);
	void glDrawElementsInstanced(int mode, int count, int type, int indices, int instancecount);
	void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex);
	void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride);
	
	//endregion
	
	
	
	// ===================== SYNC OPERATIONS =====================
	//region
	
	long glFenceSync(int condition, int flags);
	int glClientWaitSync(long sync, int flags, long timeout);
	int glGetSynci(long sync, int pname, IntBuffer length);
	void glWaitSync(long sync, int flags, long timeout);
	void glDeleteSync(long sync);
	void glFlush();
	
	//endregion
	
	
	
	// ===================== QUERY OPERATIONS =====================
	//region
	
	int glGenQueries();
	void glDeleteQueries(int query);
	void glQueryCounter(int id, int target);
	long glGetQueryObjectui64(int id, int pname);
	
	//endregion
	
	
	
	// ===================== DEBUG OPERATIONS =====================
	//region
	
	default PrintStream getDebugStream() { return System.err; }
	/** @return 0=unsupported, 1=success, 2=restart needed */
	int setupDebugCallback(IDebugMessageHandler handler); // TODO replace return type with an enum, 0 as an error code is not standard behavior
	void disableDebugCallback();
	void glObjectLabel(int identifier, int name, CharSequence label);
	void glPushDebugGroup(int source, int id, CharSequence message);
	void glPopDebugGroup();
	
	//endregion
	
	
	
	// ===================== TEXTURE OPERATIONS =====================
	//region
	
	int glGenTextures();
	void glGenTextures(int[] textures);
	void glDeleteTextures(int texture);
	void glDeleteTextures(int[] textures);
	void glBindTexture(int target, int texture);
	void glActiveTexture(int texture);
	int glGetTexLevelParameteri(int target, int level, int pname);
	void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);
	void glPixelStorei(int pname, int param);
	void glTexParameteri(int target, int pname, int param);
	void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels);
	void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);
	void glGenerateMipmap(int target);
	boolean glIsTexture(int texture);
	void glBindSampler(int unit, int sampler);
	
	//endregion
	
	
	
	// ===================== FRAMEBUFFER OPERATIONS =====================
	//region
	
	int glGenFramebuffers();
	void glDeleteFramebuffers(int framebuffer);
	void glBindFramebuffer(int target, int framebuffer);
	int glCheckFramebufferStatus(int target);
	void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level);
	void glFramebufferTexture(int target, int attachment, int texture, int level);
	int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname);
	
	//endregion
	
	
	
	// ===================== STATE OPERATIONS =====================
	//region
	
	void glEnable(int cap);
	void glDisable(int cap);
	void glEnablei(int cap, int value);
	void glDisablei(int cap, int value);
	boolean glIsEnabled(int cap);
	void glBlendFunc(int sfactor, int dfactor);
	void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
	void glBlendEquation(int mode);
	void glBlendEquationSeparate(int modeRGB, int modeAlpha);
	void glDepthFunc(int func);
	void glDepthMask(boolean flag);
	void glColorMask(boolean red, boolean green, boolean blue, boolean alpha);
	void glViewport(int x, int y, int width, int height);
	void glClear(int mask);
	void glClearColor(float red, float green, float blue, float alpha);
	void glClearDepth(double depth);
	void glCullFace(int mode);
	void glPolygonMode(int face, int mode);
	void glStencilFunc(int func, int ref, int mask);
	int glGetError();
	void glColor4f(float v, float v1, float v2, float v3);
	
	//endregion
	
	
	
	// ===================== COMPATIBILITY PROFILE =====================
	//region
	
	void glMatrixMode(int mode);
	void glLoadMatrixf(FloatBuffer m);
	
	//endregion
	
	
	
	// ===================== Fog =========================
	//region
	
	void glFogf(int	pname, float params);
	
	//endregion
	
	
	
	// ===================== MISC GL =====================
	//region
	
	int glGetInteger(int pname);
	void glGetIntegerv(int pname, int[] params);
	void glGetFloatv(int pname, float[] params);
	boolean glGetBoolean(int pname);
	String glGetString(int pname);
	int glGetAttribLocation(int program, CharSequence name);
	
	//endregion
	
	
	
	// ===================== MEMORY STACK =====================
	//region
	
	AbstractMemoryStack stackPush();
	
	//endregion
	
	
	
	// ===================== NATIVE MEMORY =====================
	//region
	
	long nmemAlloc(long size);
	long nmemCalloc(long count, long size);
	long nmemAlignedAlloc(long alignment, long size);
	long nmemRealloc(long ptr, long size);
	void nmemFree(long ptr);
	void nmemAlignedFree(long ptr);
	
	ByteBuffer memAlloc(int size);
	ByteBuffer memCalloc(int size);
	ByteBuffer memRealloc(ByteBuffer buffer, int size);
	void memFree(Buffer buffer);
	ByteBuffer memByteBuffer(long address, int capacity);
	long memAddress(Buffer buffer);
	long memAddress(Buffer buffer, int position);
	
	void memSet(long address, int value, long bytes);
	void memCopy(long src, long dst, long bytes);
	
	default void memCopy(ByteBuffer src, ByteBuffer dst) {
		memCopy(memAddress(src), memAddress(dst), src.remaining());
	}
	
	void memPutByte(long address, byte value);
	void memPutShort(long address, short value);
	void memPutInt(long address, int value);
	void memPutFloat(long address, float value);
	void memPutLong(long address, long value);
	void memPutAddress(long address, long value);
	
	byte memGetByte(long address);
	short memGetShort(long address);
	int memGetInt(long address);
	float memGetFloat(long address);
	long memGetLong(long address);
	long memGetAddress(long address);
	ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity);
	
	//endregion
	
	
	
	// ===================== UTIL =====================
	//region
	
	boolean isRunningOnRenderThread();
	
	//endregion
	
	
}