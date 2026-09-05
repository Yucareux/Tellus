package com.yucareux.tellus.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Locale;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess.Frozen;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Client API bridge for Minecraft 1.21.11's transitional GUI and input APIs. */
public final class ClientMinecraftCompat {
   private ClientMinecraftCompat() {
   }

   public static Identifier resourceLocation(String namespace, String path) {
      return Identifier.fromNamespaceAndPath(namespace, path);
   }

   public static void setWidgetHeight(AbstractWidget widget, int height) {
      widget.setHeight(height);
   }

   public static void pushPose(GuiGraphics graphics) {
      graphics.pose().pushMatrix();
   }

   public static void popPose(GuiGraphics graphics) {
      graphics.pose().popMatrix();
   }

   public static void translatePose(GuiGraphics graphics, float x, float y) {
      graphics.pose().translate(x, y);
   }

   public static void scalePose(GuiGraphics graphics, float x, float y) {
      graphics.pose().scale(x, y);
   }

   public static void blit(
      GuiGraphics graphics, Object texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight
   ) {
      graphics.blit(
         RenderPipelines.GUI_TEXTURED, (Identifier)texture, x, y, u, v, width, height, textureWidth, textureHeight
      );
   }

   public static DynamicTexture createDynamicTexture(NativeImage image, String debugName) {
      return new DynamicTexture(() -> String.format(Locale.ROOT, "%s", debugName), image);
   }

   public static void registerTexture(Object id, DynamicTexture texture) {
      Minecraft.getInstance().getTextureManager().register((Identifier)id, texture);
   }

   public static void releaseTexture(Object id) {
      Minecraft.getInstance().getTextureManager().release((Identifier)id);
   }

   public static boolean mouseClicked(AbstractWidget widget, double mouseX, double mouseY, int button) {
      return widget.mouseClicked(mouseEvent(mouseX, mouseY, button), false);
   }

   public static boolean mouseDragged(
      AbstractWidget widget, double mouseX, double mouseY, int button, double deltaX, double deltaY
   ) {
      return widget.mouseDragged(mouseEvent(mouseX, mouseY, button), deltaX, deltaY);
   }

   public static boolean mouseReleased(AbstractWidget widget, double mouseX, double mouseY, int button) {
      return widget.mouseReleased(mouseEvent(mouseX, mouseY, button));
   }

   public static boolean keyPressed(AbstractWidget widget, int keyCode, int scanCode, int modifiers) {
      return widget.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
   }

   public static void openUri(String url) {
      Util.getPlatform().openUri(url);
   }

   public static boolean isShiftDown() {
      return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), InputConstants.KEY_LSHIFT)
         || InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), InputConstants.KEY_RSHIFT);
   }

   public static String resourceKeyName(ResourceKey<?> key) {
      return key.identifier().toString();
   }

   public static <T> Registry<T> registryOrThrow(Frozen access, ResourceKey<? extends Registry<? extends T>> key) {
      return access.lookupOrThrow(key);
   }

   public static KeyMapping.Category keyCategory(String translationKey) {
      return KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tellus", "controls"));
   }

   public static <T> CycleButton.Builder<T> cycleButtonBuilder(
      Function<T, Component> formatter, T initialValue, List<T> values
   ) {
      return CycleButton.builder(formatter, initialValue).withValues(values);
   }

   public static CycleButton.Builder<Boolean> booleanCycleButtonBuilder(
      Component yes, Component no, boolean initialValue
   ) {
      return CycleButton.booleanBuilder(yes, no, initialValue);
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
         current.dataConfiguration(),
         current.initialWorldCreationOptions()
      );
   }

   public static int loadingWidgetTop(int centerY, int chunkDisplayRadius, int lineHeight) {
      return centerY - chunkDisplayRadius - lineHeight - 2;
   }

   public static int loadingWidgetBottom(int centerY, int chunkDisplayRadius) {
      return centerY + chunkDisplayRadius;
   }

   private static MouseButtonEvent mouseEvent(double mouseX, double mouseY, int button) {
      return new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0));
   }
}
