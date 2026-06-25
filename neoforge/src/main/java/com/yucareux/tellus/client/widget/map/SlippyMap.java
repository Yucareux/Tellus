package com.yucareux.tellus.client.widget.map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

@OnlyIn(Dist.CLIENT)
public class SlippyMap {
   public static final int TILE_SIZE = 256;
   public static final int MIN_ZOOM = 3;
   public static final int MAX_ZOOM = 19;
   private final int width;
   private final int height;
   private final SlippyMap.Camera camera;
   private final SlippyMapTileCache cache = new SlippyMapTileCache();

   public SlippyMap(int width, int height) {
      this.width = width;
      this.height = height;
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera = new SlippyMap.Camera(new SlippyMapPoint(0.0, 0.0), width * scale, height * scale);
   }

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      return this.cache.getTile(pos);
   }

   public void focus(double latitude, double longitude, int zoom) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      SlippyMapPoint point = new SlippyMapPoint(latitude, longitude);
      this.camera.focus(point.getX(zoom), point.getY(zoom), zoom, this.width * scale, this.height * scale);
   }

   public void zoom(int step, int pivotX, int pivotY) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera.zoom(step, pivotX * scale, pivotY * scale);
   }

   public void drag(int deltaX, int deltaY) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera.pan(deltaX * scale, deltaY * scale);
   }

   public List<SlippyMapTilePos> getVisibleTiles() {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      int cameraX = this.camera.getX();
      int cameraY = this.camera.getY();
      int cameraZoom = this.camera.getZoom();
      int minX = Mth.floor(cameraX / 256.0);
      int minY = Mth.floor(cameraY / 256.0);
      int maxX = Mth.ceil((cameraX + this.width * scale) / 256.0);
      int maxY = Mth.ceil((cameraY + this.height * scale) / 256.0);
      List<SlippyMapTilePos> visibleTiles = new ArrayList<>();

      for (int tileY = minY; tileY < maxY; tileY++) {
         for (int tileX = minX; tileX < maxX; tileX++) {
            visibleTiles.add(new SlippyMapTilePos(tileX, tileY, cameraZoom));
         }
      }

      return visibleTiles;
   }

   public List<SlippyMapTilePos> cascadeTiles(List<SlippyMapTilePos> tiles) {
      List<SlippyMapTilePos> cascaded = new ArrayList<>(tiles.size());

      for (SlippyMapTilePos pos : tiles) {
         this.cascadeTile(cascaded, pos);
      }

      return cascaded;
   }

   private void cascadeTile(List<SlippyMapTilePos> tiles, SlippyMapTilePos pos) {
      int size = 1 << pos.getZoom();
      if (pos.getX() >= 0 && pos.getY() >= 0 && pos.getX() < size && pos.getY() < size) {
         SlippyMapTile image = this.cache.getTile(pos);
         if (image != null && image.isReady()) {
            tiles.add(pos);
         }

         if (pos.getZoom() >= 3 && (image == null || !image.isReady() || image.getTransition() < 1.0F)) {
            this.cascadeTile(tiles, new SlippyMapTilePos(pos.getX() >> 1, pos.getY() >> 1, pos.getZoom() - 1));
         }
      }
   }

   public int getCameraX() {
      return this.camera.getX();
   }

   public int getCameraY() {
      return this.camera.getY();
   }

   public int getCameraZoom() {
      return this.camera.getZoom();
   }

   public void shutdown() {
      this.cache.shutdown();
   }

   @OnlyIn(Dist.CLIENT)
   private static class Camera {
      private SlippyMapPoint origin;
      private int zoom = 3;

      private Camera(SlippyMapPoint origin, int width, int height) {
         this.origin = origin.translate(-width / 2, -height / 2, this.zoom);
      }

      public void focus(int x, int y, int zoom, int width, int height) {
         int clampedZoom = Mth.clamp(zoom, MIN_ZOOM, MAX_ZOOM);
         this.origin = new SlippyMapPoint(x, y, clampedZoom).translate(-width / 2, -height / 2, clampedZoom);
         this.zoom = clampedZoom;
      }

      public void pan(int deltaX, int deltaY) {
         this.origin = this.origin.translate(deltaX, deltaY, this.zoom);
      }

      public void zoom(int steps, int pivotX, int pivotY) {
         if (steps != 0) {
            int originX = this.origin.getX(this.zoom);
            int originY = this.origin.getY(this.zoom);
            int nextZoom = Mth.clamp(this.zoom + steps, MIN_ZOOM, MAX_ZOOM);
            if (nextZoom == this.zoom) {
               return;
            }

            this.zoom = nextZoom;
            if (steps > 0) {
               int newX = originX * 2 + pivotX;
               int newY = originY * 2 + pivotY;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            } else if (steps < 0) {
               int newX = (originX - pivotX) / 2;
               int newY = (originY - pivotY) / 2;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            }
         }
      }

      public int getX() {
         return this.origin.getX(this.zoom);
      }

      public int getY() {
         return this.origin.getY(this.zoom);
      }

      public int getZoom() {
         return this.zoom;
      }
   }
}
