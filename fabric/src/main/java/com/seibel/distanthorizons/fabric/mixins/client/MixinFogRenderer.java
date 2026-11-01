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

import com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.Camera;

#if MC_VER < MC_1_17_1
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_3
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_6
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Vector4f;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#else
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.FogData;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
#endif

@Mixin(FogRenderer.class)
public class MixinFogRenderer
{
	// Using this instead of Float.MAX_VALUE because Sodium don't like it.
	@Unique
	private static final float A_REALLY_REALLY_BIG_VALUE = 420694206942069.F;
	@Unique
	private static final float A_EVEN_LARGER_VALUE = 42069420694206942069.F;
	
	
	
	#if MC_VER < MC_1_19_2
	@Inject(at = @At("RETURN"), method = "setupFog")
	private static void disableSetupFog(Camera camera, FogMode fogMode, float f, boolean bl, CallbackInfo callback)
	#elif MC_VER < MC_1_21_3
	@Inject(at = @At("RETURN"), method = "setupFog")
	private static void disableSetupFog(Camera camera, FogMode fogMode, float f, boolean bl, float g, CallbackInfo callback)
	#elif MC_VER < MC_1_21_6
	@Inject(at = @At("RETURN"), method = "setupFog", cancellable = true)
	private static void disableSetupFog(Camera camera, FogMode fogMode, Vector4f vector4f, float f, boolean bl, float g, CallbackInfoReturnable<FogParameters> callback)
	#else
	@Unique
	private static void unused()
	#endif
	{
		#if MC_VER < MC_1_21_6
		boolean cancelFog = MixinVanillaFogCommon.cancelFog(camera, fogMode);
		#else
		boolean cancelFog = MixinVanillaFogCommon.cancelFog();
		#endif
		
		if (cancelFog)
		{
			#if MC_VER < MC_1_17_1
			RenderSystem.fogStart(A_REALLY_REALLY_BIG_VALUE);
			RenderSystem.fogEnd(A_EVEN_LARGER_VALUE);
			#elif MC_VER < MC_1_21_3
			RenderSystem.setShaderFogStart(A_REALLY_REALLY_BIG_VALUE);
			RenderSystem.setShaderFogEnd(A_EVEN_LARGER_VALUE);
			#elif MC_VER < MC_1_21_6
			callback.setReturnValue(FogParameters.NO_FOG);
			#else
			#endif
			
			ClientApi.RENDER_STATE.vanillaFogEnabled = false;
		}
		else
		{
			ClientApi.RENDER_STATE.vanillaFogEnabled = true;
		}
		
	}
	
	
	#if MC_VER < MC_1_21_6
	#else
	
	// In MC's FogRenderer they clamp the "renderDistanceEnd" fog field to the render distance,
	// which prevents us from disabling the vanilla fog.
	// This mixin fires after they set the "renderDistanceEnd" so we can change it.
	@WrapOperation(
		method = "setupFog",
		at = @At(
			value = "FIELD",
			target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD
		)
	)
	private void onSetRenderDistanceEnd(FogData instance, float value, Operation<Void> original) 
	{
		if (MixinVanillaFogCommon.cancelFog())
		{
			instance.environmentalStart = A_REALLY_REALLY_BIG_VALUE;
			instance.environmentalEnd = A_EVEN_LARGER_VALUE;
			
			instance.renderDistanceStart = A_REALLY_REALLY_BIG_VALUE;
			instance.renderDistanceEnd = A_EVEN_LARGER_VALUE;
			
			ClientApi.RENDER_STATE.vanillaFogEnabled = false;
		}
		else
		{
			ClientApi.RENDER_STATE.vanillaFogEnabled = true;
		}
		
		// Always call the original with the modified or original value
		original.call(instance, value);
	}
	
	#endif
	
	
	
}
