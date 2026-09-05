package com.yucareux.tellus.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/** Selection-list entry renderer used through Minecraft 1.21.1. */
abstract class AbstractCustomizationListEntry extends ContainerObjectSelectionList.Entry<CustomizationList.Entry> {
   @Override
   public final void render(
      GuiGraphics graphics,
      int index,
      int top,
      int left,
      int width,
      int height,
      int mouseX,
      int mouseY,
      boolean hovered,
      float delta
   ) {
      this.renderTellusContent(graphics, left, top, width, height, mouseX, mouseY, hovered, delta);
   }

   protected abstract void renderTellusContent(
      GuiGraphics graphics, int left, int top, int width, int height, int mouseX, int mouseY, boolean hovered, float delta
   );
}
