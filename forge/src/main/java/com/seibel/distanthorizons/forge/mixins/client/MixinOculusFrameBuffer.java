package com.seibel.distanthorizons.forge.mixins.client;

#if MC_VER != MC_1_20_1

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public class MixinOculusFrameBuffer
{ /* Iris isn't supported before MC 1.20.4 */ }

#else // Oculus is only supported on MC 1.20.1

import net.irisshaders.iris.gl.IrisRenderSystem;

import org.lwjgl.opengl.GL30C;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = IIrisAccessor.FRAMEBUFFER_MIXIN_CLASS, remap = false)
public abstract class MixinOculusFrameBuffer
{
	@Redirect(
		method = "addDepthAttachment",
		at = @At(
			value = "INVOKE",
			target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;framebufferTexture2D(IIIIII)V"))
	private void releaseStaleDepthPoints(
		final int framebuffer, final int framebufferTarget,
		final int attachment, final int textureTarget, final int texture, final int levels) 
	{
		IrisRenderSystem.framebufferTexture2D(framebuffer, framebufferTarget, GL30C.GL_DEPTH_ATTACHMENT, textureTarget, 0, 0);
		IrisRenderSystem.framebufferTexture2D(framebuffer, framebufferTarget, GL30C.GL_STENCIL_ATTACHMENT, textureTarget, 0, 0);
		IrisRenderSystem.framebufferTexture2D(framebuffer, framebufferTarget, attachment, textureTarget, texture, levels);
	}
}

#endif