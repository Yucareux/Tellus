package com.yucareux.tellus.client.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/** Selection-list entry renderer for Minecraft 1.21.11's layout-based API. */
abstract class AbstractCustomizationListEntry extends ContainerObjectSelectionList.Entry<CustomizationList.Entry> {
   @Override
   public final void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
      this.renderTellusContent(
         graphics,
         this.getContentX(),
         this.getContentY(),
         this.getContentWidth(),
         this.getContentHeight(),
         mouseX,
         mouseY,
         hovered,
         delta
      );
   }

   protected abstract void renderTellusContent(
      GuiGraphics graphics, int left, int top, int width, int height, int mouseX, int mouseY, boolean hovered, float delta
   );
}
