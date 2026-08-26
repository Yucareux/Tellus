package com.yucareux.tellus.world.data.canopy;

import com.yucareux.tellus.worldgen.WorldProjection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TellusCanopyHeightSourceTest {
   @Test
   void keepsNativeTilesForFullDetailAtThirtyMeterScale() {
      assertEquals(13, TellusCanopyHeightSource.levelForResolution(1.0, 1.0));
      assertEquals(13, TellusCanopyHeightSource.levelForResolution(30.0, 30.0));
      assertEquals(12, TellusCanopyHeightSource.levelForResolution(60.0, 60.0));
      assertEquals(11, TellusCanopyHeightSource.levelForResolution(200.0, 200.0));
   }

   @Test
   void mapsGeographicCoordinatesToTheArcGisTileGrid() {
      assertEquals(new TellusCanopyHeightSource.TileKey(13, 3937, 8437), TellusCanopyHeightSource.tileKey(0.0, 0.0, 13));
      assertNull(TellusCanopyHeightSource.tileKey(0.0, 84.0, 13));
      assertNull(TellusCanopyHeightSource.tileKey(0.0, -60.1, 13));
   }

   @Test
   void coarsensLargePreviewAreasToTheirTileBudget() {
      double requestedResolution = 60.0;
      WorldProjection projection = WorldProjection.global(30.0);
      int initialTiles = TellusCanopyHeightSource.areaTileCount(-512, -512, 512, 512, projection, requestedResolution);

      double adjustedResolution = TellusCanopyHeightSource.resolutionForAreaTileBudget(
         -512, -512, 512, 512, projection, requestedResolution, 32
      );
      int adjustedTiles = TellusCanopyHeightSource.areaTileCount(-512, -512, 512, 512, projection, adjustedResolution);

      assertTrue(initialTiles > 32);
      assertTrue(adjustedTiles <= 32);
      assertTrue(adjustedResolution > requestedResolution);
   }
}
