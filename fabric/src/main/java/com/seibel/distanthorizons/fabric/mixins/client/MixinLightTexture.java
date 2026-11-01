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

package com.seibel.distanthorizons.fabric.mixins.client;

import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

#if MC_VER < MC_1_21_3
import com.mojang.blaze3d.platform.NativeImage;
#elif MC_VER < MC_1_21_5
import com.mojang.blaze3d.pipeline.TextureTarget;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
#else
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
#endif

#if MC_VER <= MC_1_21_11
import net.minecraft.client.renderer.LightTexture;
#else
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.Lightmap;
#endif


#if MC_VER <= MC_1_21_11
@Mixin(LightTexture.class)
#else
@Mixin(Lightmap.class)
#endif
public class MixinLightTexture
{
	 
	#if MC_VER < MC_1_21_3
	@Shadow 
	@Final
	private NativeImage lightPixels;
	#elif MC_VER < MC_1_21_5
	@Shadow
	@Final
	private TextureTarget target;
	#else
	@Shadow
	@Final
	private GpuTexture texture;
	#endif
	
	@Unique
	private MinecraftRenderWrapper renderWrapper = null;
	@Unique
	private AbstractDhRenderApiDefinition renderDef = null;
	
	
	
	#if MC_VER <= MC_1_21_11
	@Inject(method = "updateLightTexture(F)V", at = @At("RETURN"))
	public void updateLightTexture(float partialTicks, CallbackInfo ci)
	#else
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V",  at = @At("RETURN"))
	public void render(LightmapRenderState renderState, CallbackInfo ci)
	#endif
	{
		// lazy initialization to make sure we don't call this too early
		if (this.renderWrapper == null)
		{
			this.renderWrapper = (MinecraftRenderWrapper)SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
			this.renderDef = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
		}
		
		
		#if MC_VER < MC_1_21_3
		this.renderWrapper.updateLightmap(this.lightPixels);
		#elif MC_VER < MC_1_21_5
		this.renderWrapper.setLightmapId(this.target.getColorTextureId());
		#elif MC_VER <= MC_1_21_10
		GlTexture glTexture = (GlTexture) this.texture;
		this.renderWrapper.setLightmapId(glTexture.glId());
		#elif MC_VER <= MC_26_1_2
		// both options are available since the renderer can be changed to either Blaze3D or OpenGL
		GlTexture glTexture = (GlTexture) this.texture;
		this.renderWrapper.setLightmapId(glTexture.glId());
		
		this.renderWrapper.setLightmapGpuTexture(this.texture);
		#else
		
		if (this.renderDef.getRenderApi() == EDhApiRenderingApi.OPEN_GL)
		{
			GlTexture glTexture = (GlTexture) this.texture;
			this.renderWrapper.setLightmapId(glTexture.glId());
		}
		
		// this will be used for Blaze3D OpenGL and Vulkan
		this.renderWrapper.setLightmapGpuTexture(this.texture);
		#endif
	}
	
	
	
}
