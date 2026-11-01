package com.seibel.distanthorizons.common.commonMixins;

import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;

public abstract class AbstractDhMixinPlugin
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private boolean firstRun = false;
	private boolean isNeoforgeMixinFile;
	
	
	
	/**
	 * @param targetClassName example: "net.minecraft.world.level.chunk.ChunkGenerator"
	 * @param mixinClassName example: "com.seibel.distanthorizons.neoforge.mixins.server.MixinChunkGenerator"
	 */
	public boolean shouldApplyDhMixin(String targetClassName, String mixinClassName)
	{
		if (!this.shouldApplyDhMixinInnerLogic(targetClassName, mixinClassName))
		{
			LOGGER.debug("Skip DH mixin ["+mixinClassName+"] -> ["+targetClassName+"] due to DH logic.");
			return false;
		}
		
		LOGGER.debug("Apply DH mixin ["+mixinClassName+"] -> ["+targetClassName+"]...");
		return true;
	}
	private boolean shouldApplyDhMixinInnerLogic(String targetClassName, String mixinClassName)
	{
		// immersive portals
		if (mixinClassName.endsWith("MixinImmersivePortalsRenderStates"))
		{
			boolean immersivePortalsPresent = isClassPresent(IImmersivePortalsAccessor.INJECTION_CLASS, Thread.currentThread().getContextClassLoader());
			if (!immersivePortalsPresent)
			{
				immersivePortalsPresent = isClassPresent(IImmersivePortalsAccessor.INJECTION_CLASS_1_16, Thread.currentThread().getContextClassLoader());
			}
			
			return immersivePortalsPresent;
		}
		
		// iris
		if (mixinClassName.endsWith("MixinIrisFrameBuffer")
			|| mixinClassName.endsWith("MixinOculusFrameBuffer"))
		{
			boolean irisPresent = isClassPresent(IIrisAccessor.FRAMEBUFFER_MIXIN_CLASS, Thread.currentThread().getContextClassLoader());
			return irisPresent;
		}
		
		return true;
	}
	/** 
	 * this is used instead of class loading 
	 * since class loading causes mixins to silently fail. 
	 */
	private static boolean isClassPresent(String className, ClassLoader loader) 
	{
		String path = className.replace('.', '/') + ".class";
		return loader.getResource(path) != null;
	}
	
	
	
	public boolean isNeoforge()
	{
		if (!this.firstRun)
		{
			// this check is necessary to prevent neoforge mixins
			// from running on forge and vice versa
			try
			{
				Class<?> cls = Class.forName("net.neoforged.fml.common.Mod"); // Check if a NeoForge exclusive class exists
				this.isNeoforgeMixinFile = true;
			}
			catch (ClassNotFoundException e)
			{
				this.isNeoforgeMixinFile = false;
			}
		}
		
		return this.isNeoforgeMixinFile;
	}
	
	
}
