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
public class MixinProjectionMatrixBuffer {}

#else

import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

#if MC_VER <= MC_26_2_0
#else
import com.seibel.distanthorizons.common.commonMixins.MixinProjectionMatrixBufferCommon;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Matrix4f;
#endif

@Mixin(ProjectionMatrixBuffer.class)
public class MixinProjectionMatrixBuffer
{
	#if MC_VER <= MC_26_2_0
	#else
	
	// needed for MC 26.3 and newer since MC doesn't
	// expose the modified projection matrix
	@Inject(
		method = "writeBuffer",
		at = @At("HEAD")
	)
	private void writeBuffer(Matrix4f matrix, CallbackInfoReturnable<GpuBufferSlice> ci)
	{ MixinProjectionMatrixBufferCommon.onMatrixWrite(matrix); }
	
	#endif
}
#endif
