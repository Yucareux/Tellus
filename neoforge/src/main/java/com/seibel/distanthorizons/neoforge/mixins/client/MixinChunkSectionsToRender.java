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

package com.seibel.distanthorizons.neoforge.mixins.client;

#if MC_VER <= MC_1_21_11
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public class MixinChunkSectionsToRender
{ /* rendering before was handled via Fabric API events */ }


#elif MC_VER <= MC_26_2_0


import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

#if MC_VER <= MC_1_21_10
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.textures.GpuSampler;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
#else
#endif

@Mixin(ChunkSectionsToRender.class)
public class MixinChunkSectionsToRender
{
	
	//====================//
	// MC 26.1 or MC 26.2 //
	//====================//
	//region
	
	#if MC_VER <= MC_1_21_11
	// see code above
	#elif MC_VER <= MC_26_2_0
	// needs to fire at HEAD with a lower than normal order (less than 1000)
	// otherwise it will be canceled by Sodium
	@Inject(at = @At("HEAD"), method = "renderGroup", order = 800)
	private void renderDeferredLayerHead(ChunkSectionLayerGroup chunkSectionLayerGroup, GpuSampler gpuSampler, CallbackInfo ci)
	{
		#if MC_VER <= MC_26_1_2
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, Minecraft.getInstance().levelRenderer.level);
		#else
		#endif
		
		
		ClientApi.RENDER_STATE.canRenderOrThrow();
		
		if (chunkSectionLayerGroup == ChunkSectionLayerGroup.TRANSLUCENT)
		{
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
		}
		else if (chunkSectionLayerGroup == ChunkSectionLayerGroup.OPAQUE)
		{
			ClientApi.INSTANCE.renderLods();
		}
	}
	
	#endif
	
	//endregion
	
	
	
}

#else

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixing into sodium is needed specifically for shaders to work
 * otherwise DH will be called in a different spot then Iris expects,
 * cuasing GL state issues.
 */
@Pseudo
@Mixin(net.caffeinemc.mods.sodium.client.util.SodiumChunkSection.class)
public class MixinChunkSectionsToRender
{
	@Unique
	private static boolean firstTimeSetupComplete = false;
	@Unique
	private static IIrisAccessor irisAccessor;
	
	
	
	// needs to fire at HEAD with a lower than normal order (less than 1000)
	// otherwise it will be canceled by Sodium
	@Inject(at = @At("HEAD"), method = "renderGroup", order = 800)
	private void renderDeferredLayerHead(
		ChunkSectionLayerGroup group, RenderPass renderPass, GpuSampler sampler, GpuTextureView atlas, boolean renderWireframeTerrain, CallbackInfo ci)
	{
		if (!firstTimeSetupComplete)
		{
			irisAccessor = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
			firstTimeSetupComplete = true;
		}
		
		
		
		if (irisAccessor == null
			|| !irisAccessor.isShaderPackInUse())
		{
			return;
		}
		
		ClientApi.RENDER_STATE.canRenderOrThrow();
		
		if (group == ChunkSectionLayerGroup.TRANSLUCENT)
		{
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
		}
		else if (group == ChunkSectionLayerGroup.OPAQUE)
		{
			ClientApi.INSTANCE.renderLods();
		}
	}
	
}

#endif
