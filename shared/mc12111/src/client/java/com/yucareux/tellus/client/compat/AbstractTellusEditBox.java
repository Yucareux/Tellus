package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

/** Converts Minecraft 1.21.11 edit-box events to shared primitive hooks. */
public abstract class AbstractTellusEditBox extends EditBox {
   protected AbstractTellusEditBox(Font font, int x, int y, int width, int height, Component message) {
      super(font, x, y, width, height, message);
   }

   @Override
   public final boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      return this.tellusMouseClicked(event.x(), event.y(), event.button());
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0)), false);
   }

   @Override
   public final boolean keyPressed(KeyEvent event) {
      return this.tellusKeyPressed(event.key(), event.scancode(), event.modifiers());
   }

   protected boolean tellusKeyPressed(int keyCode, int scanCode, int modifiers) {
      return super.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
   }
}
