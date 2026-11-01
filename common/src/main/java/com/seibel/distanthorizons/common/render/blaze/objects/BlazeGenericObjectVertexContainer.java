package com.seibel.distanthorizons.common.render.blaze.objects;

#if MC_VER <= MC_1_21_10
public class BlazeGenericObjectVertexContainer {}

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

import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.common.render.openGl.glObject.enums.GLEnums;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * For use by {@link RenderableBoxGroup} 
 * 
 * @see RenderableBoxGroup
 */
public class BlazeGenericObjectVertexContainer implements IDhGenericObjectVertexBufferContainer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private static final int[] BOX_INDICES = {
		//region
		// min X, vertical face
		2, 1, 0,
		0, 3, 2,
		// max X, vertical face
		6, 5, 4,
		4, 7, 6,
		
		// min Z, vertical face
		10, 9, 8,
		8, 11, 10,
		// max Z, vertical face
		14, 13, 12,
		12, 15, 14,
		
		// min Y, horizontal face
		18, 17, 16,
		16, 19, 18,
		// max Y, horizontal face
		20, 21, 22,
		22, 23, 20,
		//endregion
	};
	
	
	
	public GpuBuffer vboGpuBuffer;
	public GpuBuffer indexGpuBuffer;
	
	private ByteBuffer vertexBuffer = ByteBuffer.allocateDirect(0);
	private ByteBuffer indexBuffer = ByteBuffer.allocateDirect(0);
	
	public int uploadedBoxCount = 0;
	
	private EState state = EState.NEW;
	@Override
	public IDhGenericObjectVertexBufferContainer.EState getState() { return this.state; }
	@Override
	public void setState(IDhGenericObjectVertexBufferContainer.EState state) { this.state = state; }
	
	
	
	//===========================//
	// render building/uploading //
	//===========================//
	//region
	
	@Override
	public void updateVertexData(List<DhApiRenderableBox> uploadBoxList)
	{
		int boxCount = uploadBoxList.size();
		
		// recreate the data arrays if their size is different
		if (this.uploadedBoxCount != boxCount)
		{
			this.uploadedBoxCount = boxCount;
			
			int vertexBufferSize = this.vertexBufferSize();
			this.vertexBuffer = ByteBuffer.allocateDirect(vertexBufferSize);
			this.vertexBuffer.order(ByteOrder.nativeOrder());
			
			int indexBufferSize = this.indexBufferSize();
			this.indexBuffer = ByteBuffer.allocateDirect(indexBufferSize);
			this.indexBuffer.order(ByteOrder.nativeOrder());
		}
		this.vertexBuffer.position(0);
		this.indexBuffer.position(0);
		
		
		
		for (int boxIndex = 0; boxIndex < boxCount; boxIndex++)
		{
			// index
			int indexOffset = (boxIndex * 24 /*24 is the number of vertices in a box*/);
			for (int i = 0; i < BOX_INDICES.length; i++)
			{
				this.indexBuffer.putInt(BOX_INDICES[i] + indexOffset);
			}
			
			
			
			
			// vertex
			DhApiRenderableBox box = uploadBoxList.get(boxIndex);
			
			final double[] boxVertices = 
			{
				//region
				// Pos x y z
				
				// min X, vertical face
				box.minPos.x, box.minPos.y, box.minPos.z,
				box.maxPos.x, box.minPos.y, box.minPos.z,
				box.maxPos.x, box.maxPos.y, box.minPos.z,
				box.minPos.x, box.maxPos.y, box.minPos.z,
				// max X, vertical face
				box.minPos.x, box.maxPos.y, box.maxPos.z,
				box.maxPos.x, box.maxPos.y, box.maxPos.z,
				box.maxPos.x, box.minPos.y, box.maxPos.z,
				box.minPos.x, box.minPos.y, box.maxPos.z,
				
				// min Z, vertical face
				box.minPos.x, box.minPos.y, box.maxPos.z,
				box.minPos.x, box.minPos.y, box.minPos.z,
				box.minPos.x, box.maxPos.y, box.minPos.z,
				box.minPos.x, box.maxPos.y, box.maxPos.z,
				// max Z, vertical face
				box.maxPos.x, box.minPos.y, box.maxPos.z,
				box.maxPos.x, box.maxPos.y, box.maxPos.z,
				box.maxPos.x, box.maxPos.y, box.minPos.z,
				box.maxPos.x, box.minPos.y, box.minPos.z,
				
				// min Y, horizontal face
				box.minPos.x, box.minPos.y, box.maxPos.z,
				box.maxPos.x, box.minPos.y, box.maxPos.z,
				box.maxPos.x, box.minPos.y, box.minPos.z,
				box.minPos.x, box.minPos.y, box.minPos.z,
				// max Y, horizontal face
				box.minPos.x, box.maxPos.y, box.maxPos.z,
				box.maxPos.x, box.maxPos.y, box.maxPos.z,
				box.maxPos.x, box.maxPos.y, box.minPos.z,
				box.minPos.x, box.maxPos.y, box.minPos.z,
				//endregion
			};
			
			for (int vertexIndex = 0; vertexIndex < boxVertices.length; vertexIndex+=3)
			{
				this.vertexBuffer.putFloat((float)boxVertices[vertexIndex]); // x
				this.vertexBuffer.putFloat((float)boxVertices[vertexIndex+1]); // y
				this.vertexBuffer.putFloat((float)boxVertices[vertexIndex+2]); // z
				
				int color = ColorUtil.toColorInt(box.color);
				byte r = (byte) ColorUtil.getRed(color);
				byte g = (byte) ColorUtil.getGreen(color);
				byte b = (byte) ColorUtil.getBlue(color);
				byte a = (byte) ColorUtil.getAlpha(color);
				this.vertexBuffer.put(r);
				this.vertexBuffer.put(g);
				this.vertexBuffer.put(b);
				this.vertexBuffer.put(a);
				
				this.vertexBuffer.put(box.material);
				
				// padding so the vertex format's byte count is a multiple of 4
				this.vertexBuffer.put((byte)0);
				this.vertexBuffer.put((byte)0);
				this.vertexBuffer.put((byte)0);
			}
		}
		this.vertexBuffer.flip();
		this.indexBuffer.flip();
	}
	
	private int vertexBufferSize()
	{
		// minimum of 1 box to prevent trying to create a buffer of size 0
		int boxCount = Math.max(this.uploadedBoxCount, 1);
		
		int faceCount = boxCount * 6; // 6 faces on a cube
		int vertexCount = faceCount * 6; // 6 vertices per cube
		
		int byteSize = vertexCount * 3 * Float.BYTES; // x,y,z
		byteSize += vertexCount * 4; // r,g,b,a
		byteSize += 1; // material
		return byteSize;
	}
	private int indexBufferSize()
	{
		// minimum of 1 box to prevent trying to create a buffer of size 0
		int boxCount = Math.max(this.uploadedBoxCount, 1);
		
		int quadCount = boxCount * 6 * 6; // 6 faces with 6 vertices each
		int byteSize = quadCount * GLEnums.getTypeSize(GL33.GL_UNSIGNED_INT);
		return byteSize;
	}
	
	@Override
	public void uploadDataToGpu()
	{
		// vertex
		{
			int totalVertexByteSize = this.vertexBufferSize();
			if (this.vboGpuBuffer == null
				// recreating if the size changes is always necessary (even if we only need a smaller amount)
				// due to a bug on Mac where it will attempt to render anything allocated in the buffer
				|| this.vboGpuBuffer.size() != totalVertexByteSize)
			{
				if (this.vboGpuBuffer != null)
				{
					this.vboGpuBuffer.close();
				}
				
				int usage = GpuBuffer.USAGE_COPY_DST 
					| GpuBuffer.USAGE_VERTEX;
				this.vboGpuBuffer = GPU_DEVICE.createBuffer(this::getVertexBufferName, usage, totalVertexByteSize);
			}
			
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.vboGpuBuffer, /*offset*/0, totalVertexByteSize);
			COMMAND_ENCODER.writeToBuffer(bufferSlice, this.vertexBuffer);
		}
		
		// index
		{
			int totalVertexByteSize = this.indexBufferSize();
			if (this.indexGpuBuffer == null
				// recreating if the size changes is always necessary (even if we only need a smaller amount)
				// due to a bug on Mac where it will attempt to render anything allocated in the buffer
				|| this.indexGpuBuffer.size() != totalVertexByteSize)
			{
				if (this.indexGpuBuffer != null)
				{
					this.indexGpuBuffer.close();
				}
				
				int usage = GpuBuffer.USAGE_COPY_DST 
					| GpuBuffer.USAGE_INDEX;
				this.indexGpuBuffer = GPU_DEVICE.createBuffer(this::getIndexBufferName, usage, totalVertexByteSize);
			}
			
			int offset = 0;
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.indexGpuBuffer, offset, totalVertexByteSize);
			
			COMMAND_ENCODER.writeToBuffer(bufferSlice, this.indexBuffer);
		}
	}
	private String getVertexBufferName() { return "distantHorizons:GenericVertexBuffer"; }
	private String getIndexBufferName() { return "distantHorizons:GenericIndexBuffer"; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("BlazeGenericObjectVertexContainer close", () -> 
		{
			if (this.vboGpuBuffer != null)
			{
				this.vboGpuBuffer.close();
			}
			
			if (this.indexGpuBuffer != null)
			{
				this.indexGpuBuffer.close();
			}
		});
	}
	
	//endregion
	
	
	
}
#endif