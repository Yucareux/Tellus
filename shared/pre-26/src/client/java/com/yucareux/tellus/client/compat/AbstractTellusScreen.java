package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Coordinate-based input bridge for shared screens. */
public abstract class AbstractTellusScreen extends Screen {
   protected AbstractTellusScreen(Component title) {
      super(title);
   }

   @Override
   public final boolean mouseClicked(double mouseX, double mouseY, int button) {
      return this.tellusMouseClicked(mouseX, mouseY, button);
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public final boolean mouseReleased(double mouseX, double mouseY, int button) {
      return this.tellusMouseReleased(mouseX, mouseY, button);
   }

   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      return super.mouseReleased(mouseX, mouseY, button);
   }
}
