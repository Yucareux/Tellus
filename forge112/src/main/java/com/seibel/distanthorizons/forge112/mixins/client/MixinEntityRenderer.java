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

package com.seibel.distanthorizons.forge112.mixins.client;

import com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer
{
	@Shadow
	@Final
	private DynamicTexture lightmapTexture;
	
	@Shadow @Final private Minecraft mc;
	@Shadow @Final private static Logger LOGGER;
	private static final float A_REALLY_REALLY_BIG_VALUE = 420694206942069.F;
	private static final float A_EVEN_LARGER_VALUE = 42069420694206942069.F;
	
	@Inject(at = @At("TAIL"), method = "updateLightmap")
	public void onUpdateLightmap(float patrialTicks, CallbackInfo ci)
	{
		IMinecraftClientWrapper mc = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
		if (mc == null || mc.getWrappedClientLevel() == null)
		{
			return;
		}
		
		MinecraftRenderWrapper renderWrapper = (MinecraftRenderWrapper)SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
		renderWrapper.setLightmapId(lightmapTexture.getGlTextureId());
	}
	
	@Inject(at = @At("RETURN"), method = "setupFog")
	private void disableSetupFog(int startCoords, float partialTicks, CallbackInfo ci)
	{
		boolean cancelFog = MixinVanillaFogCommon.cancelFog(startCoords, mc);
		if (cancelFog)
		{
			GlStateManager.setFogStart(A_REALLY_REALLY_BIG_VALUE);
			GlStateManager.setFogEnd(A_EVEN_LARGER_VALUE);
			ClientApi.RENDER_STATE.vanillaFogEnabled = false;
		}
		else
		{
			ClientApi.RENDER_STATE.vanillaFogEnabled = true;
		}
	}
}
