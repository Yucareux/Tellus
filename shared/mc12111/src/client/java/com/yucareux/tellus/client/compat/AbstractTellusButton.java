package com.yucareux.tellus.client.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Render-method bridge for Minecraft 1.21.11's final button renderer. */
public abstract class AbstractTellusButton extends Button {
   protected AbstractTellusButton(int x, int y, int width, int height, Component message, OnPress onPress) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
   }

   @Override
   protected final void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.renderTellusContents(graphics, mouseX, mouseY, delta);
   }

   protected abstract void renderTellusContents(GuiGraphics graphics, int mouseX, int mouseY, float delta);
}
