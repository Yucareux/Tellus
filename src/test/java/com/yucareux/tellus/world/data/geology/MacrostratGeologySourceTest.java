package com.yucareux.tellus.world.data.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MacrostratGeologySourceTest {
   @Test
   void zoomFollowsTheWorldScaleAndStopsAtTheGlobalLayer() {
      assertEquals(14, MacrostratGeologySource.zoomFor(1.0, 1.0), "1:1 reads the most detailed polygons");
      assertEquals(12, MacrostratGeologySource.zoomFor(30.0, 30.0));
      assertEquals(10, MacrostratGeologySource.zoomFor(100.0, 100.0));
      assertEquals(7, MacrostratGeologySource.zoomFor(1000.0, 1000.0));
      assertEquals(-1, MacrostratGeologySource.zoomFor(5000.0, 5000.0), "coarser than any detailed map");
      assertEquals(11, MacrostratGeologySource.zoomFor(1.0, 64.0), "a LOD of 64 m cells reads coarser tiles");
   }

   @Test
   void tileFillsUnitsAndLeavesHolesAndSkippedMapsOpen() throws IOException {
      Layer.Builder layer = Layer.newBuilder().setVersion(2).setName("units").setExtent(4096).addKeys("source_id").addKeys("legend_id").addKeys("name");
      // A limestone square with a square hole, a granite strip from a skipped continental map, and alluvium.
      addUnit(layer, 34, 1, "Redwall Limestone", square(512, 512, 3584, 3584), square(1536, 1536, 2560, 2560));
      addUnit(layer, 147, 2, "Granite", square(0, 3700, 4096, 4096));
      addUnit(layer, 34, 3, "Alluvium", square(0, 0, 4096, 400));
      byte[] payload = Tile.newBuilder().addLayers(layer).build().toByteArray();

      MacrostratTile tile = MacrostratTile.parse(payload, 256, source -> source == 147);
      assertEquals(MapUnit.of(Rock.LIMESTONE), tile.unitAt(0.2, 0.2));
      assertEquals(MapUnit.of(Rock.LIMESTONE), tile.unitAt(0.8, 0.8));
      assertNull(tile.unitAt(0.5, 0.5), "hole");
      assertNull(tile.unitAt(0.05, 0.5), "outside every polygon");
      assertNull(tile.unitAt(0.5, 0.95), "skipped map");
      assertNull(tile.unitAt(0.5, 0.05), "loose cover");
      assertNull(MacrostratTile.parse(new byte[0], 256, source -> false).unitAt(0.5, 0.5), "Macrostrat's empty tile");
   }

   private static void addUnit(Layer.Builder layer, int sourceId, int legendId, String name, int[]... rings) {
      int values = layer.getValuesCount();
      layer.addValues(Value.newBuilder().setIntValue(sourceId));
      layer.addValues(Value.newBuilder().setIntValue(legendId));
      layer.addValues(Value.newBuilder().setStringValue(name));
      layer.addFeatures(
         Feature.newBuilder()
            .setType(GeomType.POLYGON)
            .addTags(0).addTags(values)
            .addTags(1).addTags(values + 1)
            .addTags(2).addTags(values + 2)
            .addAllGeometry(geometry(rings))
      );
   }

   /** Vector-tile commands for closed rings, each given as x0, y0, x1, y1, ... */
   private static List<Integer> geometry(int[]... rings) {
      List<Integer> commands = new ArrayList<>();
      int x = 0;
      int y = 0;
      for (int[] ring : rings) {
         for (int i = 0; i < ring.length; i += 2) {
            if (i == 0) {
               commands.add(command(1, 1));
            } else if (i == 2) {
               commands.add(command(2, ring.length / 2 - 1));
            }
            commands.add(zigZag(ring[i] - x));
            commands.add(zigZag(ring[i + 1] - y));
            x = ring[i];
            y = ring[i + 1];
         }
         commands.add(command(7, 1));
      }
      return commands;
   }

   private static int[] square(int minX, int minY, int maxX, int maxY) {
      return new int[]{minX, minY, maxX, minY, maxX, maxY, minX, maxY};
   }

   private static int command(int id, int count) {
      return id & 7 | count << 3;
   }

   private static int zigZag(int value) {
      return value << 1 ^ value >> 31;
   }
}
