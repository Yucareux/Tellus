package com.yucareux.tellus.client.widget.map;

import com.yucareux.tellus.client.widget.map.component.MapComponent;
import com.yucareux.tellus.client.compat.AbstractTellusWidget;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
public class SlippyMapWidget extends AbstractTellusWidget {
   private static final String ATTRIBUTION = "(c) OpenStreetMap Contributors";
   private final SlippyMap map;
   private final List<MapComponent> components = new ArrayList<>();
   private MapComponent dragComponent;
   private boolean mouseDown;
   private boolean mouseDragged;
   private int attributionBottomPadding;

   public SlippyMapWidget(int x, int y, int width, int height) {
      super(x, y, width, height, Component.empty());
      this.map = new SlippyMap(width, height);
   }

   public SlippyMap getMap() {
      return this.map;
   }

   public <T extends MapComponent> T addComponent(T component) {
      this.components.add(component);
      return component;
   }

   public void setAttributionBottomPadding(int padding) {
      this.attributionBottomPadding = Math.max(0, padding);
   }

   protected void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.drawBackground(graphics);
      graphics.enableScissor(this.getX() + 4, this.getY() + 4, this.getX() + this.width - 4, this.getY() + this.height - 4);
      int cameraX = this.map.getCameraX();
      int cameraY = this.map.getCameraY();
      int cameraZoom = this.map.getCameraZoom();
      List<SlippyMapTilePos> tiles = this.map.getVisibleTiles();
      List<SlippyMapTilePos> cascadedTiles = this.map.cascadeTiles(tiles);
      cascadedTiles.sort(Comparator.comparingInt(SlippyMapTilePos::getZoom));

      for (SlippyMapTilePos pos : cascadedTiles) {
         SlippyMapTile tile = this.map.getTile(pos);
         this.renderTile(graphics, cameraX, cameraY, cameraZoom, pos, tile, delta);
      }

      SlippyMapPoint mouse = this.getPointUnderMouse(mouseX, mouseY);
      ClientMinecraftCompat.pushPose(graphics);
      ClientMinecraftCompat.translatePose(graphics, this.getX(), this.getY());

      for (MapComponent component : this.components) {
         component.onDrawMap(this.map, graphics, mouseX, mouseY, mouse);
      }

      ClientMinecraftCompat.popPose(graphics);
      graphics.disableScissor();
      int maxX = this.getX() + this.width - 4;
      int maxY = this.getY() + this.height - 4 - this.attributionBottomPadding;
      int attributionWidth = Minecraft.getInstance().font.width(ATTRIBUTION) + 20;
      int attributionOriginX = maxX - attributionWidth;
      int attributionOriginY = maxY - 9 - 4;
      graphics.fill(attributionOriginX, attributionOriginY, maxX, maxY, -1072689136);
      graphics.drawString(Minecraft.getInstance().font, ATTRIBUTION, attributionOriginX + 10, attributionOriginY + 2, -1);
   }

   private void renderTile(GuiGraphics graphics, int cameraX, int cameraY, int cameraZoom, SlippyMapTilePos pos, SlippyMapTile image, float delta) {
      image.update(delta);
      Object location = image.getLocation();
      if (location != null) {
         int deltaZoom = cameraZoom - pos.getZoom();
         double zoomScale = Math.pow(2.0, deltaZoom);
         int size = Mth.floor(256.0 * zoomScale);
         int renderX = (pos.getX() << deltaZoom) * 256 - cameraX;
         int renderY = (pos.getY() << deltaZoom) * 256 - cameraY;
         int textureSize = Math.max(256, size);
         ClientMinecraftCompat.pushPose(graphics);
         ClientMinecraftCompat.translatePose(graphics, this.getX(), this.getY());
         int scaleFactor = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
         float scale = 1.0F / scaleFactor;
         ClientMinecraftCompat.scalePose(graphics, scale, scale);
         ClientMinecraftCompat.blit(
            graphics, Objects.requireNonNull(location, "tileLocation"), renderX, renderY, 0.0F, 0.0F, size, size, textureSize, textureSize
         );
         ClientMinecraftCompat.popPose(graphics);
      }
   }

   private void drawBackground(GuiGraphics graphics) {
      graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, -14671840);
      graphics.renderOutline(this.getX(), this.getY(), this.width, this.height, -16777216);
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      if (!this.isMouseOver(mouseX, mouseY)) {
         return false;
      } else {
         this.mouseDown = true;
         SlippyMapPoint mouse = this.getPointUnderMouse(mouseX, mouseY);

         for (MapComponent component : this.components) {
            if (component.onMouseClicked(this.map, mouse, button)) {
               this.dragComponent = component;
               return true;
            }
         }

         return true;
      }
   }

   protected boolean tellusMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.mouseDown) {
         if (this.dragComponent != null) {
            SlippyMapPoint mouse = this.getPointUnderMouse(mouseX, mouseY);
            if (this.dragComponent.onMouseDragged(this.map, mouse, button, dragX, dragY)) {
               this.mouseDragged = true;
               return true;
            }
         }

         this.map.drag((int)(-dragX), (int)(-dragY));
         this.mouseDragged = true;
         return true;
      } else {
         return false;
      }
   }

   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      boolean handled = false;
      if (button == 0 && this.mouseDown && this.isMouseOver(mouseX, mouseY)) {
         SlippyMapPoint mouse = this.getPointUnderMouse(mouseX, mouseY);

         if (this.dragComponent != null) {
            handled = this.dragComponent.onMouseReleased(this.map, mouse, button);
         } else if (!this.mouseDragged) {
            for (MapComponent component : this.components) {
               if (component.onMouseReleased(this.map, mouse, button)) {
                  handled = true;
                  break;
               }
            }
         }
      }

      this.dragComponent = null;
      this.mouseDown = false;
      this.mouseDragged = false;
      return handled;
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
      return this.handleMouseScrolled(mouseX, mouseY, amount);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      return this.handleMouseScrolled(mouseX, mouseY, scrollY);
   }

   private boolean handleMouseScrolled(double mouseX, double mouseY, double amount) {
      if (this.isMouseOver(mouseX, mouseY)) {
         int zoom = (int)Math.signum(amount);
         if (zoom != 0) {
            this.map.zoom(zoom, (int)(mouseX - this.getX()), (int)(mouseY - this.getY()));
         }

         return true;
      } else {
         return false;
      }
   }

   public void close() {
      this.map.shutdown();
   }

   public void cancelInteraction() {
      this.dragComponent = null;
      this.mouseDown = false;
      this.mouseDragged = false;
   }

   private SlippyMapPoint getPointUnderMouse(double mouseX, double mouseY) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      int mapX = (int)((mouseX - this.getX()) * scale) + this.map.getCameraX();
      int mapY = (int)((mouseY - this.getY()) * scale) + this.map.getCameraY();
      return new SlippyMapPoint(mapX, mapY, this.map.getCameraZoom());
   }

   protected void updateWidgetNarration( NarrationElementOutput narration) {
   }
}
