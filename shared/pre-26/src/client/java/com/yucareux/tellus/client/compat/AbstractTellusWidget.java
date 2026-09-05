package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** Coordinate-based input bridge used by the shared pre-26 widgets. */
public abstract class AbstractTellusWidget extends AbstractWidget {
   protected AbstractTellusWidget(int x, int y, int width, int height, Component message) {
      super(x, y, width, height, message);
   }

   @Override
   public final boolean mouseClicked(double mouseX, double mouseY, int button) {
      return this.tellusMouseClicked(mouseX, mouseY, button);
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public final boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
      return this.tellusMouseDragged(mouseX, mouseY, button, deltaX, deltaY);
   }

   protected boolean tellusMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
      return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
   }

   @Override
   public final boolean mouseReleased(double mouseX, double mouseY, int button) {
      return this.tellusMouseReleased(mouseX, mouseY, button);
   }

   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      return super.mouseReleased(mouseX, mouseY, button);
   }

   @Override
   public final void onClick(double mouseX, double mouseY) {
      this.tellusOnClick(mouseX, mouseY);
   }

   protected void tellusOnClick(double mouseX, double mouseY) {
      super.onClick(mouseX, mouseY);
   }

   @Override
   protected final void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
      this.tellusOnDrag(mouseX, mouseY, deltaX, deltaY);
   }

   protected void tellusOnDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
      super.onDrag(mouseX, mouseY, deltaX, deltaY);
   }

   @Override
   public final void onRelease(double mouseX, double mouseY) {
      this.tellusOnRelease(mouseX, mouseY);
   }

   protected void tellusOnRelease(double mouseX, double mouseY) {
      super.onRelease(mouseX, mouseY);
   }
}
