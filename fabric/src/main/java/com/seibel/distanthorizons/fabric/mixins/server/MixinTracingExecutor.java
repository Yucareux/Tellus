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

package com.seibel.distanthorizons.fabric.mixins.server;

import com.seibel.distanthorizons.api.DhApi;
import org.spongepowered.asm.mixin.Mixin;

#if MC_VER < MC_1_21_3
/**
 * {@link MixinUtilBackgroundThread} was used for versions before 1.21.3
 * This is just a dummy class/mixin to make the compiler happy.
 *
 * @see MixinUtilBackgroundThread
 */
@Mixin(net.minecraft.Util.class)
public class MixinTracingExecutor
{
	
}
#else

import com.seibel.distanthorizons.core.util.objects.RunOnThisThreadExecutorService;
import net.minecraft.TracingExecutor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Executor;

/**
 * This is needed for DH's world gen so we can run
 * world gen on our own threads instead of using MC thread pools.
 * 
 * @see MixinUtilBackgroundThread
 * @see RunOnThisThreadExecutorService
 */
@Mixin(TracingExecutor.class)
public class MixinTracingExecutor
{
	// replaced with TracingExecutor in MC 1.21.3+
	@Inject(method = "forName(Ljava/lang/String;)Ljava/util/concurrent/Executor;", at = @At("HEAD"), cancellable = true)
	private void forName(String executorName, CallbackInfoReturnable<Executor> ci)
	{
		if (DhApi.isDhThread())
		{
			// run this task on the current DH thread instead of a new MC thread
			ci.setReturnValue(new RunOnThisThreadExecutorService());
		}
	}	
	
}
#endif