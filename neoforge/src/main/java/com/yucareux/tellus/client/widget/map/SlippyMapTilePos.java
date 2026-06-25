package com.yucareux.tellus.client.widget.map;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;


@OnlyIn(Dist.CLIENT)
public class SlippyMapTilePos {
   private final int x;
   private final int y;
   private final int zoom;

   public SlippyMapTilePos(int x, int y, int zoom) {
      this.x = x;
      this.y = y;
      this.zoom = zoom;
   }

   public int getX() {
      return this.x;
   }

   public int getY() {
      return this.y;
   }

   public int getZoom() {
      return this.zoom;
   }

   public String getCacheName() {
      return this.zoom + "_" + this.x + "_" + this.y + ".png";
   }

   @Override
   public int hashCode() {
      int result = Integer.hashCode(this.x);
      result = 31 * result + Integer.hashCode(this.y);
      result = 31 * result + Integer.hashCode(this.zoom);
      return result;
   }

   @Override
   public boolean equals(Object obj) {
      return !(obj instanceof SlippyMapTilePos tilePos) ? false : tilePos.x == this.x && tilePos.y == this.y && tilePos.zoom == this.zoom;
   }

   @Override
   public String toString() {
      return this.x + "_" + this.y + "_" + this.zoom;
   }
}
