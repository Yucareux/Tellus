package com.yucareux.tellus.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess.Frozen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.server.RegistryLayer;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Client rendering API differences specific to Minecraft 1.21.1. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static ResourceLocation resourceLocation(String namespace, String path) {
      return ResourceLocation.fromNamespaceAndPath(namespace, path);
   }

   public static BufferBuilder beginPositionColorQuads() {
      return Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
   }

   public static void upload(VertexBuffer vertexBuffer, BufferBuilder buffer) {
      vertexBuffer.upload(buffer.buildOrThrow());
   }

   public static void emitVertex(BufferBuilder buffer, float x, float y, float z, int color) {
      buffer.addVertex(x, y, z).setColor(color);
   }

   public static void setWidgetHeight(AbstractWidget widget, int height) {
      widget.setHeight(height);
   }

   public static void pushPose(GuiGraphics graphics) {
      graphics.pose().pushPose();
   }

   public static void popPose(GuiGraphics graphics) {
      graphics.pose().popPose();
   }

   public static void translatePose(GuiGraphics graphics, float x, float y) {
      graphics.pose().translate(x, y, 0.0F);
   }

   public static void scalePose(GuiGraphics graphics, float x, float y) {
      graphics.pose().scale(x, y, 1.0F);
   }

   public static void blit(
      GuiGraphics graphics, Object texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight
   ) {
      graphics.blit((ResourceLocation)texture, x, y, 0, u, v, width, height, textureWidth, textureHeight);
   }

   public static DynamicTexture createDynamicTexture(NativeImage image, String debugName) {
      return new DynamicTexture(image);
   }

   public static void registerTexture(Object id, DynamicTexture texture) {
      Minecraft.getInstance().getTextureManager().register((ResourceLocation)id, texture);
   }

   public static void releaseTexture(Object id) {
      Minecraft.getInstance().getTextureManager().release((ResourceLocation)id);
   }

   public static boolean mouseClicked(AbstractWidget widget, double mouseX, double mouseY, int button) {
      return widget.mouseClicked(mouseX, mouseY, button);
   }

   public static boolean mouseDragged(
      AbstractWidget widget, double mouseX, double mouseY, int button, double deltaX, double deltaY
   ) {
      return widget.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
   }

   public static boolean mouseReleased(AbstractWidget widget, double mouseX, double mouseY, int button) {
      return widget.mouseReleased(mouseX, mouseY, button);
   }

   public static boolean keyPressed(AbstractWidget widget, int keyCode, int scanCode, int modifiers) {
      return widget.keyPressed(keyCode, scanCode, modifiers);
   }

   public static void openUri(String url) {
      Util.getPlatform().openUri(url);
   }

   public static boolean isShiftDown() {
      long window = Minecraft.getInstance().getWindow().getWindow();
      return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
         || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
   }

   public static String resourceKeyName(ResourceKey<?> key) {
      return key.location().toString();
   }

   public static <T> Registry<T> registryOrThrow(Frozen access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.registryOrThrow(key);
   }

   public static String keyCategory(String translationKey) {
      return translationKey;
   }

   public static <T> CycleButton.Builder<T> cycleButtonBuilder(
      Function<T, Component> formatter, T initialValue, List<T> values
   ) {
      return CycleButton.builder(formatter).withValues(values).withInitialValue(initialValue);
   }

   public static CycleButton.Builder<Boolean> booleanCycleButtonBuilder(
      Component yes, Component no, boolean initialValue
   ) {
      return CycleButton.booleanBuilder(yes, no).withInitialValue(initialValue);
   }

   public static WorldCreationContext createWorldCreationContext(
      WorldCreationContext current,
      Registry<LevelStem> datapackDimensions,
      WorldDimensions selectedDimensions,
      LayeredRegistryAccess<RegistryLayer> worldgenRegistries
   ) {
      return new WorldCreationContext(
         current.options(),
         datapackDimensions,
         selectedDimensions,
         worldgenRegistries,
         current.dataPackResources(),
         current.dataConfiguration()
      );
   }

   public static int loadingWidgetTop(int centerY, int chunkDisplayRadius, int lineHeight) {
      return centerY - chunkDisplayRadius - lineHeight - 2;
   }

   public static int loadingWidgetBottom(int centerY, int chunkDisplayRadius) {
      return centerY + chunkDisplayRadius;
   }
}
