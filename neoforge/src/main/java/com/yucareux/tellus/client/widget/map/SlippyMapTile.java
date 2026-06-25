package com.yucareux.tellus.client.widget.map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.mojang.blaze3d.platform.NativeImage;
import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

@OnlyIn(Dist.CLIENT)
public class SlippyMapTile {
   private final SlippyMapTilePos pos;
   private final Object lock = new Object();
   private float transition;
   private volatile NativeImage image;
   private ResourceLocation location;
   private DynamicTexture texture;

   public SlippyMapTile(SlippyMapTilePos pos) {
      this.pos = pos;
   }

   public void update(float partialTicks) {
      if (this.transition < 1.0F) {
         this.transition = Mth.clamp(this.transition + partialTicks * 0.1F, 0.0F, 1.0F);
      }
   }

   public void supplyImage(NativeImage image) {
      synchronized (this.lock) {
         if (this.image != null) {
            this.image.close();
         }

         this.image = image;
      }
   }

   public ResourceLocation getLocation() {
      if (this.location == null && this.image != null) {
         this.location = this.uploadImage();
      }

      return this.location;
   }

   public float getTransition() {
      return this.transition;
   }

   public void delete() {
      if (this.location != null) {
         Minecraft.getInstance().getTextureManager().release(Objects.requireNonNull(this.location, "tileLocation"));
         this.location = null;
      }

      if (this.texture != null) {
         this.texture.close();
         this.texture = null;
      }

      synchronized (this.lock) {
         if (this.image != null) {
            this.image.close();
            this.image = null;
         }
      }
   }

   private ResourceLocation uploadImage() {
      synchronized (this.lock) {
         NativeImage image = Objects.requireNonNull(this.image, "tileImage");
         this.image = null;
         DynamicTexture texture = Objects.requireNonNull(new DynamicTexture(image), "tileTexture");
         this.texture = texture;
         texture.upload();
         ResourceLocation id = Objects.requireNonNull(Tellus.id("map_" + this.pos), "tileId");
         Minecraft.getInstance().getTextureManager().register(id, texture);
         return id;
      }
   }

   public boolean isReady() {
      return this.getLocation() != null;
   }
}
