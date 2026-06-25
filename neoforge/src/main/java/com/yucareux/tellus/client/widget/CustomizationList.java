package com.yucareux.tellus.client.widget;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;

@OnlyIn(Dist.CLIENT)
public class CustomizationList extends ContainerObjectSelectionList<CustomizationList.Entry> {
   public CustomizationList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
      super(minecraft, width, height, y, itemHeight);
      this.centerListVertically = false;
   }

   public void clear() {
      this.clearEntries();
   }

   public void addWidget(AbstractWidget widget) {
      this.addEntry(new CustomizationList.Entry(widget));
   }

   public int getRowWidth() {
      return this.width - 20;
   }

   protected int scrollBarX() {
      return this.getX() + this.width - 6;
   }

   protected void renderListBackground( GuiGraphics graphics) {
   }

   protected void renderListSeparators( GuiGraphics graphics) {
   }

   @OnlyIn(Dist.CLIENT)
   public static class Entry extends net.minecraft.client.gui.components.ContainerObjectSelectionList.Entry<CustomizationList.Entry> {
      private final AbstractWidget widget;

      public Entry(AbstractWidget widget) {
         this.widget = Objects.requireNonNull(widget, "widget");
      }

      
      public List<? extends GuiEventListener> children() {
         return Objects.requireNonNull(List.of(this.widget), "children");
      }

      
      public List<? extends NarratableEntry> narratables() {
         return Objects.requireNonNull(List.of(this.widget), "narratables");
      }

      public void render(
         GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float delta
      ) {
         this.widget.setX(left);
         this.widget.setY(top);
         this.widget.setWidth(width);
         this.widget.setHeight(height);
         this.widget.render(graphics, mouseX, mouseY, delta);
      }
   }
}
