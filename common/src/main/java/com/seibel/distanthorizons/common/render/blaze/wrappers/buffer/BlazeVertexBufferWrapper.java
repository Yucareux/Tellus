package com.seibel.distanthorizons.common.render.blaze.wrappers.buffer;

#if MC_VER <= MC_1_21_10
public class BlazeVertexBufferWrapper {}

#else


#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#endif

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.IndexBufferBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class BlazeVertexBufferWrapper implements IVertexBufferWrapper
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private static final AtomicInteger BUFFER_COUNT_REF = new AtomicInteger(0);
	
	
	public final String name;
	public String getName() { return this.name; }
	
	public GpuBuffer vertexGpuBuffer = null;
	
	public int vertexCount = -1;
	@Override 
	public int getVertexCount() { return this.vertexCount; }
	
	public int indexCount = -1;
	public boolean uploaded = false;
	
	
	private GpuBuffer indexGpuBuffer = null;
	private static GpuBuffer GLOBAL_INDEX_GPU_BUFFER = null;
	public GpuBuffer getIndexGpuBuffer()
	{
		if (RENDER_DEF.useSingleIbo())
		{
			return GLOBAL_INDEX_GPU_BUFFER;
		}
		else
		{
			return this.indexGpuBuffer;
		}
	}
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	static
	{
		if (RENDER_DEF.useSingleIbo())
		{
			RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("Global IBO Creation", () ->
			{
				try (PhantomArrayListCheckout checkout = LodBufferContainer.ARRAY_LIST_POOL.checkoutByteBuffers(1))
				{
					int maxSize = LodQuadBuilder.getMaxBufferByteSize();
					int maxVertexCount = maxSize / LodQuadBuilder.BYTES_PER_VERTEX;
					int maxQuadCount = (maxVertexCount / 4);
					ByteBuffer indexBuffer = IndexBufferBuilder.populateBuffer(checkout, 0, maxQuadCount);
					
					int usage = GpuBuffer.USAGE_COPY_DST
						| GpuBuffer.USAGE_INDEX;
					GLOBAL_INDEX_GPU_BUFFER = GPU_DEVICE.createBuffer(BlazeVertexBufferWrapper::getIndexBufferName, usage, indexBuffer.capacity());
					
					GpuBufferSlice bufferSlice = new GpuBufferSlice(GLOBAL_INDEX_GPU_BUFFER, /*offset*/ 0, indexBuffer.capacity());
					COMMAND_ENCODER.writeToBuffer(bufferSlice, indexBuffer);
				}
			});
		}
	}
	
	public BlazeVertexBufferWrapper(String name) { this.name = name; }
	
	//endregion
	
	
	
	//========//
	// upload //
	//========//
	//region
	
	@Override
	public void uploadVertexBuffer(ByteBuffer vertexBuffer, int vertexCount)
	{
		int oldVertexCount = this.vertexCount;
		
		this.vertexCount = vertexCount;
		// 4 vertices per face, but 6 indices (IE 2 triangles) per face, aka need to multiply by 1.5
		this.indexCount = (int)(vertexCount * 1.5);
		this.uploaded = true;
		
		
		
		if (this.vertexGpuBuffer == null
			// recreating if the size changes is always necessary (even if we only need a smaller amount)
			// due to a bug on Mac where it will attempt to render anything allocated in the buffer
			|| oldVertexCount != vertexCount)
		{
			if (this.vertexGpuBuffer == null)
			{
				BUFFER_COUNT_REF.incrementAndGet();
				//LOGGER.info("Create, count: ["+BUFFER_COUNT_REF.get()+"]");
			}
			
			if (this.vertexGpuBuffer != null)
			{
				this.vertexGpuBuffer.close();
			}
			
			int usage = GpuBuffer.USAGE_COPY_DST
				| GpuBuffer.USAGE_VERTEX;
			int byteSize = (vertexBuffer.limit() - vertexBuffer.position());
			this.vertexGpuBuffer = GPU_DEVICE.createBuffer(this::getName, usage, byteSize);
			
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.vertexGpuBuffer, /*offset*/0, byteSize);
			COMMAND_ENCODER.writeToBuffer(bufferSlice, vertexBuffer);
		}
	}
	
	@Override 
	public void uploadIndexBuffer(ByteBuffer indexBuffer, int vertexCount)
	{
		int oldIndexCount = this.indexCount;
		// 4 vertices per face, but 6 indices (IE 2 triangles) per face, aka need to multiply by 1.5
		this.indexCount = (int)(vertexCount * 1.5);
		
		if (RENDER_DEF.useSingleIbo())
		{
			// ignore index uploading when running a single IBO
			return;
		}
		
		
		
		// recreating if the size changes is always necessary (even if we only need a smaller amount)
		// due to a bug on Mac where it will attempt to render anything allocated in the buffer
		if (this.indexGpuBuffer == null
			|| oldIndexCount != this.indexCount)
		{
			if (this.indexGpuBuffer == null)
			{
				BUFFER_COUNT_REF.incrementAndGet();
			}

			if (this.indexGpuBuffer != null)
			{
				this.indexGpuBuffer.close();
			}
			
			int usage = GpuBuffer.USAGE_COPY_DST
				| GpuBuffer.USAGE_INDEX;
			this.indexGpuBuffer = GPU_DEVICE.createBuffer(BlazeVertexBufferWrapper::getIndexBufferName, usage, indexBuffer.capacity());
			
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.indexGpuBuffer, /*offset*/ 0, indexBuffer.capacity());
			COMMAND_ENCODER.writeToBuffer(bufferSlice, indexBuffer);
		}
	}
	private static String getIndexBufferName() { return "distantHorizons:LodIndexBuffer"; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		if (this.vertexGpuBuffer != null)
		{
			BUFFER_COUNT_REF.decrementAndGet();
			this.vertexGpuBuffer.close();
		}
		
		if (this.indexGpuBuffer != null)
		{
			BUFFER_COUNT_REF.decrementAndGet();
			this.indexGpuBuffer.close();
		}
		
		//LOGGER.info("Close, count: ["+BUFFER_COUNT_REF.get()+"]");
	}
	
	//endregion
	
	
	
}
#endif