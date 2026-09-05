package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Converts Minecraft 1.21.11 screen events to shared coordinate-based hooks. */
public abstract class AbstractTellusScreen extends Screen {
   protected AbstractTellusScreen(Component title) {
      super(title);
   }

   @Override
   public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      return this.tellusMouseClicked(event.x(), event.y(), event.button());
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(mouseEvent(mouseX, mouseY, button), false);
   }

   @Override
   public final boolean mouseReleased(MouseButtonEvent event) {
      return this.tellusMouseReleased(event.x(), event.y(), event.button());
   }

   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      return super.mouseReleased(mouseEvent(mouseX, mouseY, button));
   }

   private static MouseButtonEvent mouseEvent(double mouseX, double mouseY, int button) {
      return new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0));
   }
}
