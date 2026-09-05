package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Coordinate/key-code input bridge for shared edit boxes. */
public abstract class AbstractTellusEditBox extends EditBox {
   protected AbstractTellusEditBox(Font font, int x, int y, int width, int height, Component message) {
      super(font, x, y, width, height, message);
   }

   @Override
   public final boolean mouseClicked(double mouseX, double mouseY, int button) {
      return this.tellusMouseClicked(mouseX, mouseY, button);
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public final boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      return this.tellusKeyPressed(keyCode, scanCode, modifiers);
   }

   protected boolean tellusKeyPressed(int keyCode, int scanCode, int modifiers) {
      return super.keyPressed(keyCode, scanCode, modifiers);
   }
}
