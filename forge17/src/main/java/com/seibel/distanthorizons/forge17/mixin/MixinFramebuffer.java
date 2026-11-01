package com.seibel.distanthorizons.forge17.mixin;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.seibel.distanthorizons.forge17.MixinFlags;

@Mixin(Framebuffer.class)
public class MixinFramebuffer 
{
    @Shadow
    public int framebufferTextureWidth;
    @Shadow
    public int framebufferTextureHeight;
	
	
	
	//========================//
	// depth texture handling //
	//========================//
	//region
	
    @Redirect(
        method = "createFramebuffer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;func_153185_f()I"))
    private int createDepthTexture() 
    {
	    // if the mixin is disabled, just call the base method
        if (!MixinFlags.framebufferMixinEnabled) 
		{
			// despite the GL method name, this also apparently returns the depth texture ID
            return OpenGlHelper.func_153185_f(); // glGenRenderbuffers
        }
		
		
        int depthTextureId = TextureUtil.glGenTextures();
		
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTextureId);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL14.GL_DEPTH_COMPONENT24,
            this.framebufferTextureWidth,
	        this.framebufferTextureHeight,
            0,
            GL11.GL_DEPTH_COMPONENT,
            GL11.GL_FLOAT,
            (ByteBuffer) null);
		
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
		
        return depthTextureId;
    }
	
    @Redirect(
        method = "createFramebuffer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;func_153176_h(II)V"))
    private void bindDepthTexture(int frameBufferId, int depthTexture) 
    {
		// if the mixin is disabled, just call the base method
        if (!MixinFlags.framebufferMixinEnabled) 
		{
            OpenGlHelper.func_153176_h(frameBufferId, depthTexture); // glBindRenderbuffer
            return;
        }
		
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTexture, 0);
    }
	
    @Redirect(
        method = "deleteFramebuffer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/OpenGlHelper;func_153184_g(I)V"))
    private void deleteDepthTexture(int depthTexture) 
    {
	    // if the mixin is disabled, just call the base method
        if (!MixinFlags.framebufferMixinEnabled) 
		{
            OpenGlHelper.func_153184_g(depthTexture); // glDeleteRenderbuffers
            return;
        }
		
        GL11.glDeleteTextures(depthTexture);
    }
	
	//endregion
	
	
	
}
