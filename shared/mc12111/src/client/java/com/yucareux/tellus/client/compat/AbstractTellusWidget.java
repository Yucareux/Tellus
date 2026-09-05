package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Converts Minecraft 1.21.11 input events to the shared coordinate-based hooks. */
public abstract class AbstractTellusWidget extends AbstractWidget {
   protected AbstractTellusWidget(int x, int y, int width, int height, Component message) {
      super(x, y, width, height, message);
   }

   @Override
   public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      return this.tellusMouseClicked(event.x(), event.y(), event.button());
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(mouseEvent(mouseX, mouseY, button), false);
   }

   @Override
   public final boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
      return this.tellusMouseDragged(event.x(), event.y(), event.button(), deltaX, deltaY);
   }

   protected boolean tellusMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
      return super.mouseDragged(mouseEvent(mouseX, mouseY, button), deltaX, deltaY);
   }

   @Override
   public final boolean mouseReleased(MouseButtonEvent event) {
      return this.tellusMouseReleased(event.x(), event.y(), event.button());
   }

   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      return super.mouseReleased(mouseEvent(mouseX, mouseY, button));
   }

   @Override
   public final void onClick(MouseButtonEvent event, boolean doubleClick) {
      this.tellusOnClick(event.x(), event.y());
   }

   protected void tellusOnClick(double mouseX, double mouseY) {
   }

   @Override
   protected final void onDrag(MouseButtonEvent event, double deltaX, double deltaY) {
      this.tellusOnDrag(event.x(), event.y(), deltaX, deltaY);
   }

   protected void tellusOnDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
   }

   @Override
   public final void onRelease(MouseButtonEvent event) {
      this.tellusOnRelease(event.x(), event.y());
   }

   protected void tellusOnRelease(double mouseX, double mouseY) {
   }

   private static MouseButtonEvent mouseEvent(double mouseX, double mouseY, int button) {
      return new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0));
   }
}
