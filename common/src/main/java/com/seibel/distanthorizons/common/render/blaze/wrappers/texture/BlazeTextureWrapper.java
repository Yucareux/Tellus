package com.seibel.distanthorizons.common.render.blaze.wrappers.texture;

#if MC_VER <= MC_1_21_10
public class BlazeTextureWrapper {}

#else

import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
#else
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.*;
#endif

#if MC_VER <= MC_26_1_2
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.platform.NativeImage;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.GpuFormat;
import org.joml.Vector4f;
#else
import com.mojang.renderpearl.api.GpuFormat;
import org.joml.Vector4f;
#endif

public class BlazeTextureWrapper implements IDhBlazeTexture, IDhApiBlazeTextureWrapper
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	
	private final String name;
	
	#if MC_VER <= MC_26_1_2
	public final TextureFormat textureFormat;
	#else
	public final GpuFormat textureFormat;
	#endif
	
	private final FilterMode samplerFilterMode;
	
	private GpuTexture texture = null;
	private GpuTextureView textureView = null;
	private GpuSampler textureSampler = null;
	private final Object[] unsafeReturnArray = new Object[3];
	
	private int width = -1;
	private int height = -1;
	
	/** should be 1 if only one texture level is needed */
	private final int mipLevelCount;
	/** 1 is the default for no anisotropy */
	private final int maxAnisotropy;
	
	/**
	 * Setting this to true can be helpful for debugging in renderdoc
	 * if we aren't planning on writing to the entire texture. <br><br>
	 *
	 * When initially created the texture may be filled with random garbage,
	 * so zeroing it when resized allows us to see only the data
	 * we want written.
	 */
	private final boolean clearColorTextureOnResize;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static BlazeTextureWrapper createDepth(String name)
	{
		return new BlazeTextureWrapper(name, 
			#if MC_VER <= MC_26_1_2 TextureFormat.DEPTH32,  
			#else GpuFormat.D32_FLOAT,
			#endif
			FilterMode.LINEAR,
			1, 1,
			false);
	}
	public static BlazeTextureWrapper createColor(String name)
	{
		return new BlazeTextureWrapper(name, 
			#if MC_VER <= MC_26_1_2 TextureFormat.RGBA8,  
			#else GpuFormat.RGBA8_UNORM,
			#endif
			FilterMode.LINEAR,
			1, 1,
			false);
	}
	public static BlazeTextureWrapper createTextureAtlas(String name)
	{
		int mipLevelCount = (int)Math.sqrt(TextureUtil.TEXTURE_WIDTH_AND_HEIGHT);
		mipLevelCount += 1;
		
		return new BlazeTextureWrapper(name, 
			#if MC_VER <= MC_26_1_2 TextureFormat.RGBA8,  
			#else GpuFormat.RGBA8_UNORM,
			#endif
			// nearest filtering keeps the blocky look and prevents
			// texels bleeding between adjacent tiles in the grid
			FilterMode.NEAREST,
			mipLevelCount,
			// as of James testing on 07-11-2026 with MC 26.1.2
			// using a higher Anisotropy than 1 caused the distant textures to look grainier
			// so we're leaving it at 1 for now
			1,
			true);
	}
	
	private BlazeTextureWrapper(
		String name, 
		#if MC_VER <= MC_26_1_2 TextureFormat #else GpuFormat #endif textureFormat,
		FilterMode samplerFilterMode,
		int mipLevelCount, int maxAnisotropy,
		boolean clearColorTextureOnResize
	)
	{
		this.name = name;
		this.textureFormat = textureFormat;
		this.samplerFilterMode = samplerFilterMode;
		this.mipLevelCount = mipLevelCount;
		this.maxAnisotropy = maxAnisotropy;
		this.clearColorTextureOnResize = clearColorTextureOnResize;
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	
	@Override public String getName() { return this.name; }
	
	public GpuTexture getTexture() { return this.texture; }
	@Override public GpuTextureView getTextureView() { return this.textureView; }
	@Override public GpuSampler getTextureSampler() { return this.textureSampler; }
	
	/** @return -1 if the texture is null */
	@Override public int getWidth() { return this.width; }
	/** @return -1 if the texture is null */
	@Override public int getHeight() { return this.height; }
	
	public boolean isEmpty() { return this.texture == null; }
	
	//endregion
	
	
	
	//========//
	// upload //
	//========//
	//region
	
	public void writeToTexture(
		ByteBuffer pixelBuffer,
		int destinationX, int destinationY,
		int mipLevel,
		int width, int height)
	{
		if (mipLevel < 0
			|| mipLevel > this.mipLevelCount)
		{
			throw new IllegalArgumentException("Invalid mipLevel ["+mipLevel+"], must be >= 0 and < ["+this.mipLevelCount+"].");
		}
		
		#if MC_VER <= MC_26_1_2
		COMMAND_ENCODER.writeToTexture(
			this.texture,
			pixelBuffer,
			NativeImage.Format.RGBA,
			mipLevel, /*depthOrLayer*/ 0,
			destinationX, destinationY,
			width, height
		); 
		#else
		COMMAND_ENCODER.writeToTexture(
			this.texture,
			pixelBuffer,
			mipLevel, /*depthOrLayer*/ 0,
			destinationX, destinationY,
			width, height
		);
		#endif
	}
	
	//endregion
	
	
	
	//=======//
	// setup //
	//=======//
	//region
	
	/**
	 * does nothing if the texture is already created and the correct size 
	 * @return true if the texture was (re)created
	 */
	public boolean tryCreateOrResizeToScreenSize()
	{
		int viewWidth = MC_RENDER.getTargetFramebufferViewportWidth();
		int viewHeight = MC_RENDER.getTargetFramebufferViewportHeight();
		return this.tryCreateOrResize(viewWidth, viewHeight);
	}
	public boolean tryCreateOrResize(int width, int height)
	{
		boolean textureChanged = this.tryCreateTexture(width, height);
		this.tryCreateSampler();
		return textureChanged;
	}
	private boolean tryCreateTexture(int width, int height)
	{
		if (this.texture != null
			&& this.width == width
			&& this.height == height)
		{
			// no changes needed
			return false;
		}
		
		
		if (this.texture != null)
		{
			this.texture.close();
			this.textureView.close();
		}
		
		this.width = width;
		this.height = height;
		
		int usage =
			GpuTexture.USAGE_COPY_DST
				| GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_COPY_SRC
				| GpuTexture.USAGE_RENDER_ATTACHMENT;
		
		this.texture = GPU_DEVICE.createTexture(
			this.name,
			usage,
			this.textureFormat,
			width, height,
			/*depthOrLayers*/ 1, this.mipLevelCount
		);
		
		if (this.clearColorTextureOnResize)
		{
			this.clearColor(ColorUtil.INVISIBLE);
		}
		
		this.textureView = GPU_DEVICE.createTextureView(this.texture);
		
		return true;
	}
	private void tryCreateSampler()
	{
		if (this.textureSampler == null)
		{
			this.textureSampler = GPU_DEVICE.createSampler(
				AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, // U,V
				this.samplerFilterMode, this.samplerFilterMode, // minFilter, magFilter
				this.maxAnisotropy,
				OptionalDouble.empty() // maxLod
			);
		}
	}
	
	//endregion
	
	
	
	//==========//
	// clearing //
	//==========//
	//region
	
	/**
	 * Will throw an exception if not a color texture.
	 * @see ColorUtil#argbToInt
	 */
	public void clearColor(int clearArgbColor)
	{
		if (this.texture != null)
		{
			#if MC_VER <= MC_26_1_2
			COMMAND_ENCODER.clearColorTexture(this.texture, clearArgbColor);
			#else
			Vector4f clearColor = new Vector4f(
				// color values should be between 0.0 and 1.0
				ColorUtil.getRed(clearArgbColor) / 255.0f,
				ColorUtil.getGreen(clearArgbColor) / 255.0f,
				ColorUtil.getBlue(clearArgbColor) / 255.0f,
				ColorUtil.getAlpha(clearArgbColor) / 255.0f
			);
			COMMAND_ENCODER.clearColorTexture(this.texture, clearColor);
			#endif
		}
	}
	
	/** Will throw an exception if not a depth texture. */
	public void clearDepth(float depth)
	{
		if (this.texture != null)
		{
			COMMAND_ENCODER.clearDepthTexture(this.texture, depth);
		}
	}
	
	//endregion
	
	
	
	//==========//
	// wrapping //
	//==========//
	//region
	@Override 
	public Object getWrappedMcObject()
	{
		// Blaze textures have a few different objects needed for
		// rendering, so put them all in a pooled array
		{
			this.unsafeReturnArray[0] = this.texture;
			this.unsafeReturnArray[1] = this.textureView;
			this.unsafeReturnArray[2] = this.textureSampler;
		}
		return this.unsafeReturnArray;
	}
	
	//endregion
	
	
	
}
#endif