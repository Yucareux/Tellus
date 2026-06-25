package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.LoadingTerrainScreenTiming;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin({Minecraft.class})
public abstract class MinecraftScreenMixin {
   @Shadow
   public Screen screen;

   @Inject(
      method = {"setScreen"},
      at = {@At("HEAD")}
   )
   private void tellus$trackLoadingTerrainScreen(Screen newScreen, CallbackInfo ci) {
      LoadingTerrainScreenTiming.onScreenChange(this.screen, newScreen);
   }
}
