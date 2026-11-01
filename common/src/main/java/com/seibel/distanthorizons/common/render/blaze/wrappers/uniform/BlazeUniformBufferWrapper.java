package com.seibel.distanthorizons.common.render.blaze.wrappers.uniform;

#if MC_VER <= MC_1_21_10
public class BlazeUniformBufferWrapper {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
#endif

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeUniformUtil;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class BlazeUniformBufferWrapper implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("BlazeUniform"); 
	
	
	private final String name;
	
	/** measured in bytes */
	private int bufferSize = 0;
	
	private final PhantomArrayListCheckout byteBufferCheckout = ARRAY_LIST_POOL.checkoutByteBuffers(2);
	private ByteBuffer cpuBuffer = null;
	
	private GpuBuffer gpuBuffer = null;
	public GpuBuffer getGpuBuffer() { return this.gpuBuffer; }
	private GpuBufferSlice bufferSlice = null;
	
	/** the element count the current CPU Buffer is sized for */
	private int previousElementCount = 0;
	/** how many elements are currently in flight (ie being added to the buffer right now) */
	private int elementCount = 0;
	/** used to resize the buffers dur first-time setup */
	private final ArrayList<EUniformElement> uniformElementTypes = new ArrayList<>(0);
	private Std140Builder uniformBufferBuilder = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeUniformBufferWrapper(String name) { this.name = name; }
	
	//endregion
	
	
	
	//=================//
	// element builder //
	//=================//
	//region
	
	public BlazeUniformBufferWrapper putVec2f(float x, float y)
	{
		this.putElement(EUniformElement.VEC2f);
		this.uniformBufferBuilder.putVec2(x,y);
		return this;
	}
	public BlazeUniformBufferWrapper putVec3i(int x, int y, int z)
	{
		this.putElement(EUniformElement.VEC3i);
		this.uniformBufferBuilder.putIVec3(x,y,z);
		return this;
	}
	public BlazeUniformBufferWrapper putVec3f(float x, float y, float z)
	{
		this.putElement(EUniformElement.VEC3f);
		this.uniformBufferBuilder.putVec3(x,y,z);
		return this;
	}
	public BlazeUniformBufferWrapper putVec4f(float x, float y, float z, float w)
	{
		this.putElement(EUniformElement.VEC4f);
		this.uniformBufferBuilder.putVec4(x,y,z,w);
		return this;
	}
	public BlazeUniformBufferWrapper putMat4f(DhApiMat4f matrix)
	{
		this.putElement(EUniformElement.MAT4f);
		this.uniformBufferBuilder.putMat4f(DhMat4f.createJomlMatrix(matrix));
		return this;
	}
	public BlazeUniformBufferWrapper putFloat(float f)
	{
		this.putElement(EUniformElement.FLOAT);
		this.uniformBufferBuilder.putFloat(f);
		return this;
	}
	public BlazeUniformBufferWrapper putInt(int i)
	{
		this.putElement(EUniformElement.INT);
		this.uniformBufferBuilder.putInt(i);
		return this;
	}
	private void putElement(EUniformElement elementTypeEnum)
	{
		this.uniformElementTypes.add(elementTypeEnum);
		
		boolean createNewBuilder = (this.elementCount == 0);
		this.elementCount++;
		if (this.elementCount > this.previousElementCount)
		{
			this.recreateCpuBuffer();
			createNewBuilder = true;
		}
		
		if (createNewBuilder)
		{
			this.uniformBufferBuilder = Std140Builder.intoBuffer(this.cpuBuffer);
		}
	}
	private void recreateCpuBuffer()
	{
		int newSize = calcBufferSize(this.uniformElementTypes);
		int oldSize = this.bufferSize;
		
		this.bufferSize = newSize;
		this.previousElementCount = this.elementCount;
		
		if (this.cpuBuffer == null
			|| oldSize == 0)
		{
			// nothing written to the buffer yet, just make a new buffer with the requested size
			this.cpuBuffer = byteBufferCheckout.getByteBuffer(0, newSize);
			return;
		}
		
		
		// copy current data into temp
		ByteBuffer temp = byteBufferCheckout.getByteBuffer(1, oldSize);
		temp.put(this.cpuBuffer);
		temp.position(0);
		
		// resize stable buffer...
		this.cpuBuffer = byteBufferCheckout.getByteBuffer(0, newSize);
		// ...and copy old data back in
		this.cpuBuffer.put(temp);
	}
	private static int calcBufferSize(ArrayList<EUniformElement> uniformElements)
	{
		Std140SizeCalculator calculator = new Std140SizeCalculator();
		
		for (int i = 0; i < uniformElements.size(); i++)
		{
			EUniformElement element = uniformElements.get(i);
			switch (element)
			{
				case VEC2f -> calculator.putVec2();
				case VEC3i -> calculator.putIVec3();
				case VEC3f -> calculator.putVec3();
				case VEC4f -> calculator.putVec4();
				case MAT4f -> calculator.putMat4f();
				case INT -> calculator.putInt();
				case FLOAT -> calculator.putFloat();
				
				default -> throw new UnsupportedOperationException("No definition for element type ["+element.name()+"]");
			}
		}
		
		return calculator.get();
	}
	
	
	
	public void finishAndUpload()
	{
		// re-create the GPU buffer if needed
		GpuBuffer oldGpuBuffer = this.gpuBuffer;
		this.gpuBuffer = BlazeUniformUtil.createBuffer(this.name, this.bufferSize, this.gpuBuffer);
		
		boolean createNewBufferSlice = (this.bufferSlice == null || this.gpuBuffer != oldGpuBuffer);
		if (createNewBufferSlice)
		{
			this.bufferSlice = new GpuBufferSlice(this.gpuBuffer, 0, this.bufferSize);
		}
		
		// upload to GPU
		this.cpuBuffer.position(0);
		COMMAND_ENCODER.writeToBuffer(this.bufferSlice, this.cpuBuffer);
		
		
		// clear the element tracking for next time
		this.elementCount = 0;
		this.uniformElementTypes.clear();
		this.uniformBufferBuilder = null;
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		if (this.gpuBuffer != null)
		{
			this.gpuBuffer.close();
		}
		
		byteBufferCheckout.close();
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private enum EUniformElement
	{
		VEC2f,
		VEC3f,
		VEC3i,
		VEC4f,
		MAT4f,
		INT,
		FLOAT,
	}
	
	//endregion
	
	
}
#endif