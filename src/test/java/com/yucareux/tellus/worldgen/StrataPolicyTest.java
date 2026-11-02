package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.geology.Lithology;
import com.yucareux.tellus.world.data.geology.MapUnit;
import com.yucareux.tellus.world.data.geology.Rock;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrataPolicyTest {
   private static final long SEED = 8_675_309L;
   private static final int SEA_LEVEL = 63;

   @Test
   void layeredRockShowsBedsAtFineScales() {
      // 1200 real metres of limestone wall at 1:1, about a dozen formations.
      Set<Integer> materials = materialsUp(Lithology.CARBONATE_SEDIMENTARY, false, 1.0, 1200);
      assertTrue(materials.size() >= 3, "a 1200 m wall crosses several beds, found " + materials);
   }

   @Test
   void bedsThinnerThanABlockAreLeftOut() {
      // At 1:1000 a block is taller than any limestone bed, so the slope is one rock.
      StrataPolicy.Column column = column(Lithology.CARBONATE_SEDIMENTARY, false, 1000.0, 0, 0);
      for (int y = SEA_LEVEL; y < SEA_LEVEL + 10; y++) {
         assertEquals(column.mainMaterial(), column.material(y));
      }

      // At 1:10 formations stay but thin beds inside them do not, so no run of one rock is under three blocks.
      StrataPolicy.Column coarse = column(Lithology.CARBONATE_SEDIMENTARY, false, 10.0, 0, 0);
      int runStart = SEA_LEVEL;
      int previous = coarse.material(SEA_LEVEL);
      boolean first = true;
      for (int y = SEA_LEVEL + 1; y < SEA_LEVEL + 600; y++) {
         int material = coarse.material(y);
         if (material != previous) {
            if (!first) {
               assertTrue(y - runStart >= 3, "run of " + (y - runStart) + " blocks at y=" + runStart);
            }
            first = false;
            runStart = y;
            previous = material;
         }
      }
   }

   @Test
   void desertSedimentIsRedBeds() {
      Set<Integer> arid = materialsUp(Lithology.SILICICLASTIC_SEDIMENTARY, true, 1.0, 1200);
      Set<Integer> humid = materialsUp(Lithology.SILICICLASTIC_SEDIMENTARY, false, 1.0, 1200);
      assertTrue(arid.contains(StrataPolicy.RED_SANDSTONE), "arid " + arid);
      assertFalse(humid.contains(StrataPolicy.RED_SANDSTONE), "humid " + humid);
      assertEquals(StrataPolicy.RED_SANDSTONE, column(Lithology.SILICICLASTIC_SEDIMENTARY, true, 1000.0, 0, 0).mainMaterial());
   }

   @Test
   void graniteIsOneRockPerRegionWithSparseEnclaves() {
      Set<Integer> regions = new HashSet<>();
      for (int x = 0; x < 400_000; x += 4_000) {
         regions.add(column(Lithology.ACID_PLUTONIC, false, 1.0, x, 0).mainMaterial());
      }
      assertTrue(regions.contains(StrataPolicy.GRANITE) && regions.contains(StrataPolicy.DIORITE), "pink and grey massifs " + regions);

      int samples = 0;
      int regionRock = 0;
      int enclaves = 0;
      for (int x = 0; x < 600; x += 7) {
         StrataPolicy.Column column = column(Lithology.ACID_PLUTONIC, false, 1.0, x, 0);
         for (int y = SEA_LEVEL; y < SEA_LEVEL + 300; y += 5) {
            int material = column.material(y);
            samples++;
            if (material == column.mainMaterial()) {
               regionRock++;
            } else if (material == StrataPolicy.ANDESITE) {
               enclaves++;
            }
         }
      }
      assertTrue(regionRock > samples * 0.8, "a face is mostly one rock: " + regionRock + "/" + samples);
      assertTrue(enclaves > 0, "with a few enclaves");

      StrataPolicy.Column far = column(Lithology.ACID_PLUTONIC, false, 100.0, 0, 0);
      for (int y = SEA_LEVEL; y < SEA_LEVEL + 40; y++) {
         assertEquals(far.mainMaterial(), far.material(y));
      }
   }

   @Test
   void unknownRockKeepsTheGenericGreyMix() {
      Set<Integer> grey = Set.of(StrataPolicy.STONE, StrataPolicy.ANDESITE);
      for (int x = 0; x < 400_000; x += 20_000) {
         assertTrue(grey.contains(column(Lithology.UNKNOWN, false, 1.0, x, 0).mainMaterial()));
         assertTrue(grey.contains(column(Lithology.UNDEFINED, false, 1.0, x, 0).mainMaterial()));
      }
   }

   @Test
   void rockIsStableForAPositionAndChangesWithTheSeed() {
      StrataPolicy.Column first = column(Lithology.MIXED_SEDIMENTARY, false, 1.0, 1234, -987);
      StrataPolicy.Column again = column(Lithology.MIXED_SEDIMENTARY, false, 1.0, 1234, -987);
      StrataPolicy.Column otherSeed = StrataPolicy.column(Lithology.MIXED_SEDIMENTARY, false, 1234, -987, 1.0, 1.0, SEA_LEVEL, SEED + 1);
      boolean differs = false;
      for (int y = SEA_LEVEL; y < SEA_LEVEL + 500; y++) {
         assertEquals(first.material(y), again.material(y));
         differs |= first.material(y) != otherSeed.material(y);
      }
      assertTrue(differs, "another world seed gives other beds");
   }

   @Test
   void mapUnitsDrawTheirOwnRockAndInterbedTheSecond() {
      StrataPolicy.Column coarse = StrataPolicy.column(MapUnit.of(Rock.RED_SANDSTONE), 0, 0, 1000.0, 1000.0, SEA_LEVEL, SEED);
      assertEquals(StrataPolicy.RED_SANDSTONE, coarse.mainMaterial());

      StrataPolicy.Column mixed = StrataPolicy.column(new MapUnit(Rock.LIMESTONE, Rock.RED_MUDSTONE, 30), 0, 0, 1.0, 1.0, SEA_LEVEL, SEED);
      Set<Integer> materials = new HashSet<>();
      for (int y = SEA_LEVEL; y < SEA_LEVEL + 3000; y++) {
         materials.add(mixed.material(y));
      }
      Set<Integer> mudstone = Set.of(StrataPolicy.RED_TERRACOTTA, StrataPolicy.TERRACOTTA, StrataPolicy.ORANGE_TERRACOTTA);
      assertTrue(materials.contains(StrataPolicy.CALCITE), "limestone formations " + materials);
      assertTrue(materials.stream().anyMatch(mudstone::contains), "red mudstone formations " + materials);
   }

   @Test
   void everyMaterialIndexIsInRange() {
      for (Lithology lithology : Lithology.values()) {
         for (boolean arid : new boolean[]{false, true}) {
            for (int material : materialsUp(lithology, arid, 1.0, 800)) {
               assertTrue(material >= 0 && material < StrataPolicy.MATERIAL_COUNT, lithology + " gave " + material);
            }
         }
      }
      for (Rock rock : Rock.values()) {
         StrataPolicy.Column column = StrataPolicy.column(new MapUnit(rock, Rock.SHALE, 30), 0, 0, 1.0, 1.0, SEA_LEVEL, SEED);
         for (int y = SEA_LEVEL; y < SEA_LEVEL + 800; y++) {
            assertTrue(column.material(y) >= 0 && column.material(y) < StrataPolicy.MATERIAL_COUNT, rock + " gave " + column.material(y));
         }
      }
   }

   private static Set<Integer> materialsUp(Lithology lithology, boolean arid, double scale, int meters) {
      StrataPolicy.Column column = column(lithology, arid, scale, 0, 0);
      Set<Integer> materials = new HashSet<>();
      for (int y = SEA_LEVEL; y < SEA_LEVEL + meters / scale; y++) {
         materials.add(column.material(y));
      }
      return materials;
   }

   private static StrataPolicy.Column column(Lithology lithology, boolean arid, double scale, int x, int z) {
      return StrataPolicy.column(lithology, arid, x, z, scale, scale, SEA_LEVEL, SEED);
   }
}
