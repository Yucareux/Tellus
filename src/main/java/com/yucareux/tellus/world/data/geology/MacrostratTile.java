package com.yucareux.tellus.world.data.geology;

import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Feature;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.GeomType;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Layer;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * One Macrostrat {@code carto} vector tile read into a square grid of bedrock units. Polygons are filled at cell
 * centres, so a lookup costs one array read however many units the tile holds.
 */
final class MacrostratTile {
   static final MacrostratTile EMPTY = new MacrostratTile(0, new short[0], new MapUnit[]{null});
   private static final String UNITS_LAYER = "units";
   private static final int DEFAULT_EXTENT = 4096;

   private final int size;
   private final short[] cells;
   private final MapUnit[] units;

   private MacrostratTile(int size, short[] cells, MapUnit[] units) {
      this.size = size;
      this.cells = cells;
      this.units = units;
   }

   /**
    * @param u position from the tile's west edge, 0 to 1
    * @param v position from the tile's north edge, 0 to 1
    * @return the bedrock there, or null where the tile has none
    */
   MapUnit unitAt(double u, double v) {
      if (this.size == 0) {
         return null;
      }
      int column = Math.min(this.size - 1, Math.max(0, (int)(u * this.size)));
      int row = Math.min(this.size - 1, Math.max(0, (int)(v * this.size)));
      return this.units[this.cells[row * this.size + column]];
   }

   /**
    * @param payload an uncompressed Mapbox vector tile; an empty payload is a tile with no maps
    * @param skippedSource map sources whose units are left out
    */
   static MacrostratTile parse(byte[] payload, int size, IntPredicate skippedSource) throws IOException {
      if (payload.length == 0) {
         return EMPTY;
      }
      for (Layer layer : Tile.parseFrom(payload).getLayersList()) {
         if (UNITS_LAYER.equals(layer.getName())) {
            return rasterize(layer, size, skippedSource);
         }
      }
      return EMPTY;
   }

   private static MacrostratTile rasterize(Layer layer, int size, IntPredicate skippedSource) {
      int extent = layer.hasExtent() && layer.getExtent() > 0 ? layer.getExtent() : DEFAULT_EXTENT;
      short[] cells = new short[size * size];
      List<MapUnit> units = new ArrayList<>();
      units.add(null);
      Map<MapUnit, Integer> unitIndex = new HashMap<>();
      // Every polygon of a legend entry shares its text, so each entry is read once.
      Map<Object, Integer> legendIndex = new HashMap<>();
      Scanline scanline = new Scanline(size);
      for (Feature feature : layer.getFeaturesList()) {
         if (feature.getType() != GeomType.POLYGON) {
            continue;
         }
         Map<String, Object> tags = tags(feature, layer);
         Object legend = tags.get("legend_id");
         Integer index = legend == null ? null : legendIndex.get(legend);
         if (index == null) {
            index = 0;
            if (!skippedSource.test(intValue(tags.get("source_id")))) {
               MapUnit unit = MacrostratUnitClassifier.classify(
                  string(tags.get("name")), string(tags.get("lith")), string(tags.get("descrip")), doubleValue(tags.get("best_age_bottom"))
               );
               if (unit != null && units.size() <= Short.MAX_VALUE) {
                  index = unitIndex.computeIfAbsent(unit, key -> {
                     units.add(key);
                     return units.size() - 1;
                  });
               }
            }
            if (legend != null) {
               legendIndex.put(legend, index);
            }
         }
         if (index != 0) {
            scanline.fill(feature.getGeometryList(), extent, cells, (short)(int)index);
         }
      }
      return units.size() == 1 ? EMPTY : new MacrostratTile(size, cells, units.toArray(new MapUnit[0]));
   }

   private static Map<String, Object> tags(Feature feature, Layer layer) {
      Map<String, Object> tags = new HashMap<>();
      for (int i = 0; i + 1 < feature.getTagsCount(); i += 2) {
         int key = feature.getTags(i);
         int value = feature.getTags(i + 1);
         if (key >= 0 && key < layer.getKeysCount() && value >= 0 && value < layer.getValuesCount()) {
            Object decoded = decodeValue(layer.getValues(value));
            if (decoded != null) {
               tags.put(layer.getKeys(key), decoded);
            }
         }
      }
      return tags;
   }

   private static Object decodeValue(Value value) {
      if (value.hasStringValue()) {
         return value.getStringValue();
      } else if (value.hasDoubleValue()) {
         return value.getDoubleValue();
      } else if (value.hasFloatValue()) {
         return (double)value.getFloatValue();
      } else if (value.hasIntValue()) {
         return value.getIntValue();
      } else if (value.hasSintValue()) {
         return value.getSintValue();
      } else if (value.hasUintValue()) {
         return value.getUintValue();
      }
      return value.hasBoolValue() ? value.getBoolValue() : null;
   }

   private static String string(Object value) {
      return value instanceof String text ? text : null;
   }

   private static double doubleValue(Object value) {
      return value instanceof Number number ? number.doubleValue() : Double.NaN;
   }

   private static int intValue(Object value) {
      return value instanceof Number number ? number.intValue() : -1;
   }

   /** Even-odd fill of one polygon feature, all its rings together, at cell centres. */
   private static final class Scanline {
      private final int size;
      private final float[][] crossings;
      private final int[] counts;
      private int minRow;
      private int maxRow;

      private Scanline(int size) {
         this.size = size;
         this.crossings = new float[size][];
         this.counts = new int[size];
      }

      private void fill(List<Integer> geometry, int extent, short[] cells, short value) {
         double scale = (double)this.size / extent;
         this.minRow = this.size;
         this.maxRow = -1;
         int cursor = 0;
         int x = 0;
         int y = 0;
         int startX = 0;
         int startY = 0;
         boolean open = false;
         while (cursor < geometry.size()) {
            int header = geometry.get(cursor++);
            int command = header & 7;
            int count = header >>> 3;
            if (command == 7) {
               if (open) {
                  this.edge(x, y, startX, startY, scale);
                  open = false;
               }
               continue;
            }
            if (command != 1 && command != 2) {
               break;
            }
            for (int i = 0; i < count && cursor + 1 < geometry.size(); i++) {
               int nextX = x + zigZag(geometry.get(cursor++));
               int nextY = y + zigZag(geometry.get(cursor++));
               if (command == 1) {
                  if (open) {
                     this.edge(x, y, startX, startY, scale);
                  }
                  startX = nextX;
                  startY = nextY;
                  open = true;
               } else if (open) {
                  this.edge(x, y, nextX, nextY, scale);
               }
               x = nextX;
               y = nextY;
            }
         }
         if (open) {
            this.edge(x, y, startX, startY, scale);
         }

         for (int row = this.minRow; row <= this.maxRow; row++) {
            int count = this.counts[row];
            this.counts[row] = 0;
            if (count < 2) {
               continue;
            }
            float[] xs = this.crossings[row];
            Arrays.sort(xs, 0, count);
            for (int i = 0; i + 1 < count; i += 2) {
               // Tiles carry a buffer past their edges, so crossings can fall outside the grid.
               int from = Math.min(this.size, Math.max(0, (int)Math.ceil(xs[i] - 0.5)));
               int to = Math.min(this.size, Math.max(from, (int)Math.ceil(xs[i + 1] - 0.5)));
               Arrays.fill(cells, row * this.size + from, row * this.size + to, value);
            }
         }
      }

      private void edge(int x0, int y0, int x1, int y1, double scale) {
         double rowY0 = y0 * scale;
         double rowY1 = y1 * scale;
         if (rowY0 == rowY1) {
            return;
         }
         // Rows whose centre lies in [low, high), so a vertex shared by two edges is crossed once.
         int first = Math.max(0, (int)Math.ceil(Math.min(rowY0, rowY1) - 0.5));
         int last = Math.min(this.size - 1, (int)Math.ceil(Math.max(rowY0, rowY1) - 0.5) - 1);
         for (int row = first; row <= last; row++) {
            double t = (row + 0.5 - rowY0) / (rowY1 - rowY0);
            this.add(row, (float)((x0 + t * (x1 - x0)) * scale));
         }
         if (first <= last) {
            this.minRow = Math.min(this.minRow, first);
            this.maxRow = Math.max(this.maxRow, last);
         }
      }

      private void add(int row, float x) {
         float[] xs = this.crossings[row];
         int count = this.counts[row];
         if (xs == null || count == xs.length) {
            xs = xs == null ? new float[8] : Arrays.copyOf(xs, count * 2);
            this.crossings[row] = xs;
         }
         xs[count] = x;
         this.counts[row] = count + 1;
      }

      private static int zigZag(int encoded) {
         return encoded >>> 1 ^ -(encoded & 1);
      }
   }
}
