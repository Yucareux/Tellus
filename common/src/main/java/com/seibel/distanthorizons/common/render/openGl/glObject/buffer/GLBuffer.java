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

package com.seibel.distanthorizons.common.render.openGl.glObject.buffer;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomLoggingHelper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class GLBuffer implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logRendererGLEventToFile)
			.chatLevelConfig(Config.Common.Logging.logRendererGLEventToChat)
			.build();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	// TODO move to a shared location that can be handled by both GL and Blaze3D buffers
	/** if enabled the number of GC'ed buffers will be logged */
	private static final boolean LOG_PHANTOM_RECOVERY = ModInfo.IS_DEV_BUILD;
	/** If enabled buffers allocation/upload stacks will be logged when GC'ed. */
	private static final boolean LOG_PHANTOM_ALLOCATION_STACKS = false; // disabled by default due to the increased GC load
	
	public static final double BUFFER_EXPANSION_MULTIPLIER = 1.3;
	public static final double BUFFER_SHRINK_TRIGGER = BUFFER_EXPANSION_MULTIPLIER * BUFFER_EXPANSION_MULTIPLIER;

	/**
	 * On macOS the legacy OpenGL -> Metal bridge crashes with SIGBUS in
	 * {@code _platform_memmove} when {@code glBufferData} or {@code glBufferSubData}
	 * are called with a large ByteBuffer in one shot. To work around it, we split
	 * the upload into smaller sub-data calls that each fit comfortably inside the
	 * driver's internal staging path. <br>
	 * 256 KiB tuned empirically against macOS 26.5 — a 1 MiB chunk still
	 * tripped the same {@code _platform_memmove} crash inside
	 * {@code glBufferSubData_Exec}, but 256 KiB consistently survives.
	 */
	public static final int MAC_UPLOAD_CHUNK_BYTES = 256 * 1024;
	/** Threshold above which the chunked path kicks in on macOS. */
	public static final int MAC_UPLOAD_CHUNK_THRESHOLD = MAC_UPLOAD_CHUNK_BYTES;
	/** the number of active buffers, can be used for debugging */
	public static AtomicInteger bufferCount = new AtomicInteger(0);
	
	private static final int PHANTOM_REF_CHECK_TIME_IN_MS = 5 * 1000;
	private static final ConcurrentHashMap<PhantomReference<? extends GLBuffer>, Integer> PHANTOM_TO_BUFFER_ID = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, PhantomReference<? extends GLBuffer>> BUFFER_ID_TO_PHANTOM = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, String> BUFFER_ID_TO_ALLOCATION_STRING = new ConcurrentHashMap<>();
	private static final ReferenceQueue<GLBuffer> PHANTOM_REFERENCE_QUEUE = new ReferenceQueue<>();
	private static final ThreadPoolExecutor CLEANUP_THREAD = ThreadUtil.makeSingleDaemonThreadPool("GLBuffer Cleanup");
	
	
	protected volatile int id = 0;
	public final int getId() { return this.id; }
	protected int size = 0;
	public int getSize() { return this.size; }
	protected boolean bufferStorage;
	protected boolean isMapped = false;
	
	/**
	 * Locking on the render thread isn't great, but is needed due to an inconsistent
	 * race condition where VBOs can be marked as deleted outside the render thread. <br><br>
	 * 
	 * But, due to being a read-write lock the chance of freezing
	 * the render thread is very low
	 * and since this is a stamped lock, the optimistic read time is basically zero.
	 * (The optimistic lock time doesn't even appear in the profiler).
	 */
	public final StampedLock renderStampLock = new StampedLock();
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	static { CLEANUP_THREAD.execute(() -> runPhantomReferenceCleanupLoop()); }
	
	public GLBuffer(boolean isBufferStorage) { this.destroyOldAndCreate(isBufferStorage); }
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	// Should be override by subclasses
	public int getBufferBindingTarget() { return GL31.GL_COPY_READ_BUFFER; }
	
	public void bind() { LWJGL.glBindBuffer(this.getBufferBindingTarget(), this.id); }
	public void unbind() { LWJGL.glBindBuffer(this.getBufferBindingTarget(), 0); }
	
	//endregion
	
	
	
	//====================//
	// create and destroy //
	//====================//
	//region
	
	protected void destroyOldAndCreate(boolean asBufferStorage)
	{
		if (!GLProxy.runningOnRenderThread())
		{
			LodUtil.assertNotReach("Thread ["+Thread.currentThread()+"] tried to create a GLBuffer outside the MC render thread.");
		}
		
		
		// lock to prevent the render thread from accessing the buffer's ID
		// while we are removing it
		long writeStamp = this.renderStampLock.writeLock();
		try
		{
			final int oldId = this.id;
			this.id = GLMC.glGenBuffers();
			//LOGGER.info("created [" + newId + "].");
			
			// destroy the old buffer
			// after the new one has been created 
			// to hopefully prevent a rare race conditions where the old ID
			// is still used somewhere
			if (oldId != 0)
			{
				// this ID doesn't need to be tracked anymore
				tryRemoveBufferIdFromPhantom(oldId);
				destroyBufferIdNow(oldId, "destroyOldAndCreate");
			}
			
			
			this.bufferStorage = asBufferStorage;
			bufferCount.getAndIncrement();
			
			PhantomReference<GLBuffer> phantom = new PhantomReference<>(this, PHANTOM_REFERENCE_QUEUE);
			PHANTOM_TO_BUFFER_ID.put(phantom, this.id);
			BUFFER_ID_TO_PHANTOM.put(this.id, phantom);
			
			this.updateAllocationStackTrace();
		}
		finally
		{
			renderStampLock.unlock(writeStamp);
		}
	}
	
	protected void destroyAsync()
	{
		// lock to prevent the render thread from accessing the buffer's ID
		// while we are removing it
		long writeStamp = renderStampLock.writeLock();
		try
		{
			if (this.id == 0)
			{
				// the buffer has already been closed
				return;
			}
			
			final int idToDelete = this.id; // saving the ID to a separate variable is necessary so it can be captured by the lambda
			
			// remove the phantom tracking now so the phantom doesn't have the chance to
			// get garbage collected before the render thread task runs
			// (this can happen if MC is running at extremely low framerates like 1 fps via mods)
			tryRemoveBufferIdFromPhantom(idToDelete);
			
			// mark the old data is invalid before deleting to prevent a rare race condition
			// where the queued on render thread task runs before the ID is cleared
			this.id = 0;
			this.size = 0;
			
			//LOGGER.info("async destroy [" + idToDelete + "].");
			RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("GLBuffer destroyAsync", () -> { destroyBufferIdNow(idToDelete, "destroyAsync"); });
		}
		finally
		{
			renderStampLock.unlock(writeStamp);
		}
	}
	
	private static void destroyBufferIdNow(int id, String cause)
	{
		// only delete valid buffers
		if (id == 0)
		{
			LOGGER.warn("Attempted to destroy a buffer with ID 0, VRAM memory leaks may occur, cause: ["+cause+"].");
			return;
		}
		
		bufferCount.decrementAndGet();
		GLMC.glDeleteBuffers(id);
		
		if (Config.Client.Advanced.Debugging.logBufferGarbageCollection.get())
		{
			LOGGER.info("destroyed buffer [" + id + "], remaining: [" + BUFFER_ID_TO_PHANTOM.size() + "], cause: ["+cause+"].");
		}
	}
	
	/** should be called before {@link GLBuffer#destroyBufferIdNow} */
	private static void tryRemoveBufferIdFromPhantom(int id)
	{
		// will contain nothing if stack tracking isn't enabled
		BUFFER_ID_TO_ALLOCATION_STRING.remove(id);
		
		PhantomReference<? extends GLBuffer> phantom = BUFFER_ID_TO_PHANTOM.remove(id);
		if (phantom != null)
		{
			// if we are manually closing this buffer, we don't want the phantom reference to accidentally close it again
			// this can cause a race condition were we accidentally delete an in-use buffer and cause NVIDIA
			// to throw an EXCEPTION_ACCESS_VIOLATION when we attempt to render it
			phantom.clear();
			
			Integer phantomId = PHANTOM_TO_BUFFER_ID.remove(phantom);
			if (phantomId == null)
			{
				LOGGER.warn("No Phantom->ID binding stored for ID ["+id+"]");
			}
		}
		else
		{
			LOGGER.warn("Unable to remove phantom GLBuffer with ID ["+id+"], buffer may have already been deleted.");
		}
	}
	
	//endregion
	
	
	
	//==================//
	// buffer uploading //
	//==================//
	//region
	
	/** 
	 * Assumes the GL Context is already bound. <br> 
	 * Will create the VBO if one exist.
	 */
	public void uploadBuffer(ByteBuffer bb, EDhApiGpuUploadMethod uploadMethod, int maxExpansionSize, int bufferHint)
	{
		LodUtil.assertTrue(!uploadMethod.useEarlyMapping, "UploadMethod signal that this should use Mapping instead of uploadBuffer!");
		int bbSize = bb.limit() - bb.position();
		if (bbSize > maxExpansionSize) 
		{ 
			LodUtil.assertNotReach("maxExpansionSize is [" + maxExpansionSize + "] but buffer size is [" + bbSize + "]!"); 
		}
		
		// Don't upload an empty buffer
		if (bbSize == 0)
		{
			return;
		}
		
		
		
		// re-binding the old buffers is necessary for old MC versions for the following reasons:
		// for 16.5 and older the screen may be black when on the home menu
		// and for 1.19.2 - 1.21.4 the inventory/UI will render without a background
		int vao = LWJGL.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int vbo = LWJGL.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		int ebo = LWJGL.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
		
		try
		{
			// make sure the buffer is ready for uploading
			this.createOrChangeBufferTypeForUpload(uploadMethod);
			
			switch (uploadMethod)
			{
				case AUTO:
					LodUtil.assertNotReach("GpuUploadMethod AUTO must be resolved before call to uploadBuffer()!");
				case BUFFER_STORAGE:
					this.uploadBufferStorage(bb);
					break;
				case DATA:
					this.uploadBufferData(bb, bufferHint);
					break;
				case SUB_DATA:
					this.uploadSubData(bb, maxExpansionSize, bufferHint);
					break;
				default:
					LodUtil.assertNotReach("Unknown GpuUploadMethod!");
			}
		}
		finally
		{
			LWJGL.glBindVertexArray(GL30.glIsVertexArray(vao) ? vao : 0);
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, GL15.glIsBuffer(vbo) ? vbo : 0);
			LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, GL15.glIsBuffer(ebo) ? ebo: 0);
		}
	}
	/** Requires the buffer to be bound */
	protected void uploadBufferStorage(ByteBuffer bb)
	{
		LodUtil.assertTrue(this.bufferStorage, "Buffer is not bufferStorage but its trying to use bufferStorage upload method!");
		
		int bbSize = bb.limit() - bb.position();
		this.destroyOldAndCreate(true);
		this.bind();
		LWJGL.glBufferStorage(this.getBufferBindingTarget(), bb, 0);
		this.size = bbSize;
	}
	/** Requires the buffer to be bound */
	protected void uploadBufferData(ByteBuffer bb, int bufferDataHint)
	{
		LodUtil.assertTrue(!this.bufferStorage, "Buffer is bufferStorage but its trying to use bufferData upload method!");
		
		int bbSize = bb.limit() - bb.position();
		int target = this.getBufferBindingTarget();
		
		if (shouldUploadToGpuInChunks(bbSize))
		{
			// Two-step path used on macOS to dodge the Apple OpenGL -> Metal
			// memmove SIGBUS triggered by uploading a large ByteBuffer in one
			// glBufferData call:
			//   1) allocate-only with the size overload (no memcpy)
			//   2) fill the buffer through chunked glBufferSubData calls
			LWJGL.glBufferData(target, (long) bbSize, bufferDataHint);
			subDataUploadInChunks(target, 0, bb, MAC_UPLOAD_CHUNK_BYTES);
		}
		else
		{
			LWJGL.glBufferData(target, bb, bufferDataHint);
		}
		this.size = bbSize;
		
		this.updateAllocationStackTrace();
	}
	/** Requires the buffer to be bound */
	protected void uploadSubData(ByteBuffer bb, int maxExpansionSize, int bufferDataHint)
	{
		LodUtil.assertTrue(!this.bufferStorage, "Buffer is bufferStorage but its trying to use subData upload method!");
		
		int bbSize = bb.limit() - bb.position();
		int target = this.getBufferBindingTarget();
		if (this.size < bbSize || this.size > bbSize * BUFFER_SHRINK_TRIGGER)
		{
			int newSize = (int) (bbSize * BUFFER_EXPANSION_MULTIPLIER);
			if (newSize > maxExpansionSize)
			{
				newSize = maxExpansionSize;
			}
			
			// allocate-only — no memcpy, safe on macOS regardless of size
			LWJGL.glBufferData(target, (long) newSize, bufferDataHint);
			this.size = newSize;
		}
		
		if (shouldUploadToGpuInChunks(bbSize))
		{
			subDataUploadInChunks(target, 0, bb, MAC_UPLOAD_CHUNK_BYTES);
		}
		else
		{
			LWJGL.glBufferSubData(target, 0, bb);
		}
		
		this.updateAllocationStackTrace();
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close() { this.destroyAsync(); }
	
	@Override
	public String toString()
	{
		return (this.bufferStorage ? "" : "Static-") + this.getClass().getSimpleName() +
				"[id:" + this.id + ",size:" + this.size + (this.isMapped ? ",MAPPED" : "") + "]";
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	/** 
	 * Makes sure the buffer exists and is of the correct format
	 * before uploading.
	 */
	private void createOrChangeBufferTypeForUpload(EDhApiGpuUploadMethod uploadMethod)
	{
		// create/change the buffer type if necessary
		if (uploadMethod.useBufferStorage != this.bufferStorage)
		{
			// recreate if the buffer storage type changed
			this.bind();
			this.destroyOldAndCreate(uploadMethod.useBufferStorage);
			this.bind();
		}
		else
		{
			// Prevent uploading to the null buffer (ID 0).
			// This can happen if the buffer was deleted previously.
			if (this.id == 0)
			{
				this.destroyOldAndCreate(this.bufferStorage);
			}
			
			this.bind();
		}
	}
	
	/**
	 * macOS-only mitigation for the SIGBUS in
	 * {@code libsystem_platform.dylib _platform_memmove} that happens when the
	 * Apple OpenGL -> Metal translation layer copies a single large ByteBuffer
	 * out of LWJGL into driver memory. Splitting the copy into
	 * {@link #MAC_UPLOAD_CHUNK_BYTES} slices keeps every memmove inside a size
	 * the bridge handles reliably.
	 */
	private static boolean shouldUploadToGpuInChunks(int byteCount)
	{
		return EPlatform.get() == EPlatform.MACOS
			&& byteCount > MAC_UPLOAD_CHUNK_THRESHOLD;
	}
	
	/**
	 * Uploads {@code bb} into the currently bound buffer at {@code baseOffset}
	 * using a sequence of {@link GL15#glBufferSubData(int, long, ByteBuffer)}
	 * calls of at most {@code chunkBytes} each. The buffer's position/limit are
	 * restored before returning.
	 */
	private static void subDataUploadInChunks(int target, int baseOffset, ByteBuffer bb, int chunkBytes)
	{
		final int origPos = bb.position();
		final int origLimit = bb.limit();
		try
		{
			final int total = origLimit - origPos;
			int uploaded = 0;
			while (uploaded < total)
			{
				int chunk = Math.min(chunkBytes, total - uploaded);
				bb.position(origPos + uploaded);
				bb.limit(origPos + uploaded + chunk);
				LWJGL.glBufferSubData(target, (long) (baseOffset + uploaded), bb);
				uploaded += chunk;
				// Force the driver to drain its command queue between chunks
				// so the OpenGL -> Metal bridge processes (and frees) each
				// staging copy before the next sub-data call piles another
				// memmove on top of it.
				if (uploaded < total)
				{
					LWJGL.glFlush();
				}
			}
		}
		finally
		{
			bb.limit(origLimit);
			bb.position(origPos);
		}
	}
	
	/** 
	 * used to help track down leaks where the buffer isn't properly closed
	 * Note: this probably needs extending to accept a stack trace from outside where it's being called
	 * since it's often called on the render thread in an un-helpful location.
	 */
	public void updateAllocationStackTrace()
	{
		if (LOG_PHANTOM_ALLOCATION_STACKS)
		{
			String stack;
			
			RenderThreadTaskHandler.QueuedRunnable parentQueuedRunnable;
			// if this is running on the render thread, try getting the render task's stack trace instead
			// since it's a lot more helpful than wherever the render thread tasks themselves are being run from
			if (RenderThreadTaskHandler.INSTANCE.isCurrentThread()
				&& (parentQueuedRunnable = RenderThreadTaskHandler.INSTANCE.getCurrentlyRunningTask()) != null
				&& parentQueuedRunnable.stackTrace != null)
			{
				// trim off the getStacktrace() and queueRunningOnRenderThread() methods
				StackTraceElement[] trimmedElements = Arrays.copyOfRange(parentQueuedRunnable.stackTrace, 2, parentQueuedRunnable.stackTrace.length);
				stack = StringUtil.join("\n", trimmedElements).intern();
			}
			else
			{
				// not running on the render thread, use the normal stack trace
				StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
				stack = StringUtil.join("\n", stackTraceElements).intern();
			}
			
			BUFFER_ID_TO_ALLOCATION_STRING.put(this.id, stack);
		}
	}
	
	//endregion
	
	
	
	//================//
	// static cleanup //
	//================//
	//region
	
	private static void runPhantomReferenceCleanupLoop()
	{
		// these arrays are stored here so they don't have to be re-allocated each loop
		ArrayList<Pair<String, AtomicInteger>> allocationStackTraceCountPairList = new ArrayList<>();
		
		while (true)
		{
			allocationStackTraceCountPairList.clear();
			
			try
			{
				try
				{
					Thread.sleep(PHANTOM_REF_CHECK_TIME_IN_MS);
				}
				catch (InterruptedException ignore) { }
				
				int collectedCount = 0;
				
				Reference<? extends GLBuffer> phantomRef = PHANTOM_REFERENCE_QUEUE.poll();
				while (phantomRef != null)
				{
					// destroy the buffer if it hasn't been cleared yet
					Integer idRef = PHANTOM_TO_BUFFER_ID.remove((PhantomReference<? extends GLBuffer>)phantomRef); // cast to make IntelliJ happy
					if (idRef != null)
					{
						BUFFER_ID_TO_PHANTOM.remove(idRef);
						final int id = idRef;
						RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("GLBuffer phantom destroy", () -> { destroyBufferIdNow(id, "runPhantomReferenceCleanupLoop"); });
						//LOGGER.info("Buffer Phantom collected, ID: ["+id+"]");
						
						if (LOG_PHANTOM_ALLOCATION_STACKS) // stack trace shouldn't be null, but just in case
						{
							String stack = BUFFER_ID_TO_ALLOCATION_STRING.get(idRef);
							PhantomLoggingHelper.putAndIncrementTrackingString(stack, allocationStackTraceCountPairList);
						}
					}
					else
					{
						LOGGER.warn("Failed to find Buffer ID for phantom reference: ["+phantomRef+"]");
					}
					
					
					collectedCount++;
					phantomRef = PHANTOM_REFERENCE_QUEUE.poll();
				}
				
				
				
				if (LOG_PHANTOM_RECOVERY)
				{
					// we only want to log when something has been returned
					if (collectedCount != 0)
					{
						LOGGER.warn("GLBuffer phantom recovered: ["+ F3Screen.NUMBER_FORMAT.format(collectedCount)+"].");
						
						// log stack traces if present
						if (LOG_PHANTOM_ALLOCATION_STACKS)
						{
							PhantomLoggingHelper.LogAllocationStackTracePairCounts(LOGGER, "", allocationStackTraceCountPairList);
						}
					}
				}
				
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in buffer cleanup thread: [" + e.getMessage() + "].", e);
			}
		}
	}
	
	//endregion
	
	
	
}
