package com.yucareux.tellus.world.data.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import org.junit.jupiter.api.Test;

class TellusResolveSourceTest {
   private final TellusResolveSource source = new TellusResolveSource();

   @Test
   void loadsCompleteBundledLookup() {
      assertTrue(this.source.available());
      assertEquals(847, this.source.knownEcoregionCount());
      assertEquals(30, this.source.arcsecondsPerCell());
   }

   @Test
   void resolvesRepresentativeGlobalLocations() {
      assertEcoregion(
         this.source.sampleAtLonLat(-122.3321, 47.6062),
         364,
         "Puget lowland forests",
         ResolveBiome.TEMPERATE_CONIFER_FORESTS,
         ResolveRealm.NEARCTIC
      );
      assertEcoregion(
         this.source.sampleAtLonLat(-124.04, 41.32),
         359,
         "Northern California coastal forests",
         ResolveBiome.TEMPERATE_CONIFER_FORESTS,
         ResolveRealm.NEARCTIC
      );
      assertEcoregion(
         this.source.sampleAtLonLat(-60.0217, -3.1190),
         473,
         "Japurá-Solimões-Negro moist forests",
         ResolveBiome.TROPICAL_MOIST_BROADLEAF_FORESTS,
         ResolveRealm.NEOTROPIC
      );
      assertEcoregion(
         this.source.sampleAtLonLat(-99.1332, 19.4326),
         427,
         "Central Mexican matorral",
         ResolveBiome.DESERTS_XERIC_SHRUBLANDS,
         ResolveRealm.NEARCTIC
      );
      assertEcoregion(
         this.source.sampleAtLonLat(139.6917, 35.6895),
         682,
         "Taiheiyo evergreen forests",
         ResolveBiome.TEMPERATE_BROADLEAF_MIXED_FORESTS,
         ResolveRealm.PALEARCTIC
      );
   }

   @Test
   void distinguishesRockAndIceFromNoData() {
      ResolveEcoregion rockAndIce = this.source.sampleAtLonLat(0.0, -85.0);
      assertTrue(rockAndIce.available());
      assertEquals(0, rockAndIce.ecoId());
      assertEquals(ResolveBiome.ROCK_AND_ICE, rockAndIce.biome());

      ResolveEcoregion openPacific = this.source.sampleAtLonLat(-140.0, 0.0);
      assertFalse(openPacific.available());
      assertEquals(ResolveEcoregion.UNKNOWN, openPacific);
   }

   @Test
   void convertsTellusBlockCoordinatesUsingTheEarthProjection() {
      double worldScale = 1.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      double longitude = -122.3321;
      double latitude = 47.6062;
      double blockX = projection.lonToBlockX(longitude);
      double blockZ = projection.latToBlockZ(latitude);

      assertEquals(364, this.source.sampleEcoregion(blockX, blockZ, projection).ecoId());
   }

   @Test
   void rejectsCoordinatesOutsideTheEarthLookup() {
      assertFalse(this.source.sampleAtLonLat(181.0, 0.0).available());
      assertFalse(this.source.sampleAtLonLat(0.0, 91.0).available());
      assertFalse(this.source.sampleEcoregion(0.0, 0.0, WorldProjection.global(0.0)).available());
   }

   private static void assertEcoregion(
      ResolveEcoregion actual,
      int ecoId,
      String name,
      ResolveBiome biome,
      ResolveRealm realm
   ) {
      assertTrue(actual.available());
      assertEquals(ecoId, actual.ecoId());
      assertEquals(name, actual.name());
      assertEquals(biome, actual.biome());
      assertEquals(realm, actual.realm());
      assertEquals("CC-BY 4.0", actual.license());
   }
}
