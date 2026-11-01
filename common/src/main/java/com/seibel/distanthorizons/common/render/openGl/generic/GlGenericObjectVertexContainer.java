package com.seibel.distanthorizons.common.render.openGl.generic;

import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import org.lwjgl.opengl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.awt.*;
import java.util.List;

/**
 * For use by {@link RenderableBoxGroup} 
 * 
 * @see RenderableBoxGroup
 */
public class GlGenericObjectVertexContainer implements IDhGenericObjectVertexBufferContainer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	
	public int chunkPos = 0;
	public int subChunkPos = 0;
	public int scale = 0;
	public int color = 0;
	public int material = 0;
	
	public int[] chunkPosData = new int[0];
	public float[] subChunkPosData = new float[0];
	public float[] scalingData = new float[0];
	public float[] colorData = new float[0];
	public int[] materialData = new int[0];
	
	public int uploadedBoxCount = 0;
	
	private EState state = EState.NEW;
	@Override 
	public EState getState() { return this.state; }
	@Override 
	public void setState(EState state) { this.state = state; }
	
	
	
	//===========================//
	// render building/uploading //
	//===========================//
	//region
	
	public void updateVertexData(List<DhApiRenderableBox> uploadBoxList)
	{
		int boxCount = uploadBoxList.size();
		
		
		// recreate the data arrays if their size is different
		if (this.uploadedBoxCount != boxCount)
		{
			this.uploadedBoxCount = boxCount;
			
			this.chunkPosData = new int[boxCount * 3]; // 3 elements XYZ
			this.subChunkPosData = new float[boxCount * 3]; // 3 elements XYZ
			this.scalingData = new float[boxCount * 3]; // 3 elements XYZ
			
			this.colorData = new float[boxCount * 4]; // 4 elements, RGBA
			this.materialData = new int[boxCount];
		}
		
		
		// transformation / scaling //
		for (int i = 0; i < boxCount; i++)
		{
			DhApiRenderableBox box = uploadBoxList.get(i);
			
			int dataIndex = i * 3;
			
			this.chunkPosData[dataIndex] = LodUtil.getChunkPosFromDouble(box.minPos.x);
			this.chunkPosData[dataIndex + 1] = LodUtil.getChunkPosFromDouble(box.minPos.y);
			this.chunkPosData[dataIndex + 2] = LodUtil.getChunkPosFromDouble(box.minPos.z);
			
			this.subChunkPosData[dataIndex] = LodUtil.getSubChunkPosFromDouble(box.minPos.x);
			this.subChunkPosData[dataIndex + 1] = LodUtil.getSubChunkPosFromDouble(box.minPos.y);
			this.subChunkPosData[dataIndex + 2] = LodUtil.getSubChunkPosFromDouble(box.minPos.z);
			
			this.scalingData[dataIndex] = (float) (box.maxPos.x - box.minPos.x);
			this.scalingData[dataIndex + 1] = (float) (box.maxPos.y - box.minPos.y);
			this.scalingData[dataIndex + 2] = (float) (box.maxPos.z - box.minPos.z);
		}
		
		
		// colors/materials //
		for (int i = 0; i < boxCount; i++)
		{
			DhApiRenderableBox box = uploadBoxList.get(i);
			Color color = box.color;
			int colorIndex = i * 4;
			this.colorData[colorIndex] = color.getRed() / 255.0f;
			this.colorData[colorIndex + 1] = color.getGreen() / 255.0f;
			this.colorData[colorIndex + 2] = color.getBlue() / 255.0f;
			this.colorData[colorIndex + 3] = color.getAlpha() / 255.0f;
			
			this.materialData[i] = box.material;
		}
	}
	
	@Override
	public void uploadDataToGpu()
	{
		this.tryCreateBuffers();
		
		// Upload transformation matrices
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.chunkPos);
		LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, this.chunkPosData, GL15.GL_DYNAMIC_DRAW);
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.subChunkPos);
		LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, this.subChunkPosData, GL15.GL_DYNAMIC_DRAW);
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.scale);
		LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, this.scalingData, GL15.GL_DYNAMIC_DRAW);
		
		// Upload colors
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.color);
		LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, this.colorData, GL15.GL_DYNAMIC_DRAW);
		
		// Upload materials
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.material);
		LWJGL.glBufferData(GL15.GL_ARRAY_BUFFER, this.materialData, GL15.GL_DYNAMIC_DRAW);
	}
	/** needs to be done on the render thread */
	private void tryCreateBuffers()
	{
		if (this.chunkPos == 0)
		{
			this.chunkPos = GLMC.glGenBuffers();
			this.subChunkPos = GLMC.glGenBuffers();
			this.scale = GLMC.glGenBuffers();
			this.color = GLMC.glGenBuffers();
			this.material = GLMC.glGenBuffers();
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		tryDeleteBuffer(this.chunkPos);
		tryDeleteBuffer(this.subChunkPos);
		tryDeleteBuffer(this.scale);
		tryDeleteBuffer(this.color);
		tryDeleteBuffer(this.material);
	}
	private static void tryDeleteBuffer(int bufferId)
	{
		// usually unnecessary, but just in case
		if (bufferId != 0)
		{
			GLMC.glDeleteBuffers(bufferId);
		}
	}
	
	//endregion
	
	
	
	
	
}
