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

import java.nio.ByteBuffer;

import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.IndexBufferBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import org.lwjgl.opengl.GL15;

/**
 * This is a container for a OpenGL
 * VBO (Vertex Buffer Object).
 *
 * @author James Seibel
 * @version 11-20-2021
 */
public class GLVertexBuffer extends GLBuffer implements IVertexBufferWrapper
{
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	/**
	 * When uploading to a buffer that is too small, recreate it this many times
	 * bigger than the upload payload
	 */
	protected int vertexCount = 0;
	@Override
	public int getVertexCount() { return this.vertexCount; }
	
	
	private GlQuadIndexBuffer quadIBO = null;
	private static GlQuadIndexBuffer GLOBAL_QUAD_IBO = null;
	public GlQuadIndexBuffer getQuadIBO()
	{
		if (RENDER_DEF.useSingleIbo())
		{
			return GLOBAL_QUAD_IBO;
		}
		else
		{
			return this.quadIBO;
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
					GLOBAL_QUAD_IBO = new GlQuadIndexBuffer();
					
					int maxSize = LodQuadBuilder.getMaxBufferByteSize();
					int maxVertexCount = maxSize / LodQuadBuilder.BYTES_PER_VERTEX;
					int maxQuadCount = (maxVertexCount / 4);
					
					ByteBuffer buffer = IndexBufferBuilder.populateBuffer(checkout, 0, maxQuadCount);
					GLOBAL_QUAD_IBO.upload(buffer, maxQuadCount);
				}
			});
		}
	}
	
	public GLVertexBuffer() { this(GLProxy.getInstance().getGpuUploadMethod() == EDhApiGpuUploadMethod.BUFFER_STORAGE); }
	public GLVertexBuffer(boolean isBufferStorage) { super(isBufferStorage); }
	
	//endregion
	
	
	
	//======================//
	// uploading/destroying //
	//======================//
	//region
	
	@Override
	public int getBufferBindingTarget() { return GL15.GL_ARRAY_BUFFER; }
	
	@Override
	public void uploadVertexBuffer(ByteBuffer buffer, int vertexCount)
	{
		EDhApiGpuUploadMethod uploadMethod = GLProxy.getInstance().getGpuUploadMethod();
		int maxBufferSize = LodQuadBuilder.getMaxBufferByteSize();
		this.uploadBuffer(buffer, vertexCount, uploadMethod, maxBufferSize);
	}
	
	/**
	 * bufferSize is the number of shared verticies. <br>
	 * This number will be higher when actually rendered since each box's face needs 2 triangles 
	 * with 2 shared verticies. 
	 */
	public void uploadBuffer(ByteBuffer byteBuffer, int vertexCount, EDhApiGpuUploadMethod uploadMethod, int maxExpansionSize)
	{
		if (vertexCount < 0)
		{
			throw new IllegalArgumentException("vertexCount is negative!");
		}
		
		// If size is zero, just ignore it.
		if (byteBuffer.limit() - byteBuffer.position() != 0)
		{
			super.uploadBuffer(byteBuffer, uploadMethod, maxExpansionSize, uploadMethod.useBufferStorage ? 0 : GL15.GL_STATIC_DRAW);
		}
		
		this.vertexCount = vertexCount;
	}
	
	
	@Override
	public void uploadIndexBuffer(ByteBuffer buffer, int vertexCount)
	{
		if (RENDER_DEF.useSingleIbo())
		{
			// ignore index uploading when running a single IBO
			return;
		}
		
		// If size is zero, just ignore it.
		if (vertexCount == 0)
		{
			return;
		}
		
		
		
		if (this.quadIBO != null)
		{
			this.quadIBO.close();
		}
		
		this.quadIBO = new GlQuadIndexBuffer();
		
		int quadCount = (vertexCount / 4);
		this.quadIBO.upload(buffer, quadCount);
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close() { this.destroyAsync(); }
	@Override
	public void destroyAsync()
	{
		super.destroyAsync();
		if (this.quadIBO != null)
		{
			this.quadIBO.destroyAsync();
		}
		this.vertexCount = 0;
	}
	
	//endregion
	
	
	
}