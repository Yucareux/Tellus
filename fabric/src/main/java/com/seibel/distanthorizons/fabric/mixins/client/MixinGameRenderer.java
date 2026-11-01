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

#if MC_VER <= MC_1_21_11
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public class MixinGameRenderer {}

#else
import com.mojang.blaze3d.vertex.PoseStack;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

#if MC_VER <= MC_26_2_0
#else
import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.common.commonMixins.MixinProjectionMatrixBufferCommon;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
#endif

@Mixin(GameRenderer.class)
public class MixinGameRenderer
{
	#if MC_VER <= MC_26_2_0
	// get the modified projection matrix right before it's uploaded to the GPU
	@Inject(
		method = "renderLevel",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V",
			shift = At.Shift.BEFORE
		),
		locals = LocalCapture.CAPTURE_FAILHARD
	)
	private void renderLevel(
		final DeltaTracker deltaTracker,
		final CallbackInfo callback,
		final float partialTickTime,
		final float cameraEntityPartialTicks,
		final LocalPlayer player,
		final ProfilerFiller profiler,
		final boolean renderBlockOutline,
		final OptionsRenderState options,
		final CameraRenderState camera,
		final Matrix4fc modelViewMatrix,
		final Matrix4f projectionMatrix,
		final PoseStack poseStack)
	{
		#if MC_VER <= MC_26_1_2
		#else
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, (ClientLevel)player.level());
		
		ClientApi.RENDER_STATE.mcModelViewMatrix = McObjectConverter.convert(modelViewMatrix);
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		
		#endif
		
		ClientApi.RENDER_STATE.mcProjectionMatrix = McObjectConverter.convert(projectionMatrix);
	}
	
	#else
	
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void onRenderLevelStart(CallbackInfo ci)
	{
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(
			ClientApi.RENDER_STATE.clientLevelWrapper,
			(ClientLevel)MinecraftClientWrapper.INSTANCE.getPlayer().level());
		
		ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		
		MixinProjectionMatrixBufferCommon.getNewMvmMatrix = true;
	}
	
	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void onRenderLevelEnd(CallbackInfo ci)
	{ MixinProjectionMatrixBufferCommon.getNewMvmMatrix = false; }
	
	#endif
	
	
	
}
#endif
