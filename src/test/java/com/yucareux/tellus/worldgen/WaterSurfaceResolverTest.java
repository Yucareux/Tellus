package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.OsmWaterKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSurfaceResolverTest {
   @Test
   void waterDemSamplesUseTheSameAutomaticLatitudeTransformAsTerrain() {
      EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
      double blockZ = settings.projection().latToBlockZ(20.72);
      double elevationMeters = 1_500.0;
      int expected = TerrainHeightTransform.blockOffset(
         elevationMeters,
         blockZ,
         settings.projection(),
         settings.effectiveTerrestrialHeightScale(),
         settings.effectiveOceanicHeightScale(),
         settings.experimentalIncreaseHeight(),
         settings.automaticHeightScaling()
      ) + settings.effectiveHeightOffset();
      int uncorrected = (int)Math.ceil(
         elevationMeters * settings.effectiveTerrestrialHeightScale() / settings.effectiveVerticalWorldScale()
      ) + settings.effectiveHeightOffset();

      assertEquals(expected, WaterSurfaceResolver.scaleElevationToHeight(elevationMeters, blockZ, settings));
      assertNotEquals(uncorrected, expected);
   }

   @Test
   void transitionsConnectedWaterFromTheResolvedLocalOceanSurface() {
      assertEquals(36, WaterSurfaceResolver.transitionedWaterSurface(50, 36, 0.0));
      assertEquals(43, WaterSurfaceResolver.transitionedWaterSurface(50, 36, 0.5));
      assertEquals(50, WaterSurfaceResolver.transitionedWaterSurface(50, 36, 1.0));
   }

   @Test
   void raisesDeepInlandShoreFloorNearBank() {
      assertEquals(79, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 10));
      assertEquals(77, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 20));
      assertEquals(75, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, 24));
      assertEquals(75, WaterSurfaceResolver.rampedInlandShoreFloorForSteps(42, 80, 3));
   }

   @Test
   void keepsExistingShallowFloorAndUnknownDistance() {
      assertEquals(79, WaterSurfaceResolver.rampedInlandShoreFloor(79, 80, 10));
      assertEquals(42, WaterSurfaceResolver.rampedInlandShoreFloor(42, 80, Integer.MAX_VALUE));
   }

   @Test
   void rejectsLakeCellsThatWouldRequireExcessiveCut() {
      assertTrue(WaterSurfaceResolver.shouldRejectLakeWaterCell(100, 80, 8));
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(88, 80, 8));
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(79, 80, 8));
   }

   @Test
   void capsLakeSurfaceToLowestAdjacentBank() {
      assertEquals(80, WaterSurfaceResolver.capInlandLakeWaterSurface(84, 80));
      assertEquals(84, WaterSurfaceResolver.capInlandLakeWaterSurface(84, Integer.MAX_VALUE));
      assertEquals(78, WaterSurfaceResolver.capInlandLakeWaterSurface(78, 80));
   }

   @Test
   void fullChunkLakeSurfaceCanLowerAnEarlierFastLodEstimate() {
      assertEquals(80, WaterSurfaceResolver.lowestStableLakeSurface(84, 80));
      assertEquals(80, WaterSurfaceResolver.lowestStableLakeSurface(80, 84));
   }

   @Test
   void usesLooserDefaultInlandCutBudgets() {
      assertEquals(12, WaterSurfaceResolver.lakeMaxTerrainCut());
      assertEquals(6, WaterSurfaceResolver.riverMaxTerrainCut());
      assertFalse(WaterSurfaceResolver.shouldRejectLakeWaterCell(92, 80, WaterSurfaceResolver.lakeMaxTerrainCut()));
      assertTrue(WaterSurfaceResolver.shouldRejectLakeWaterCell(93, 80, WaterSurfaceResolver.lakeMaxTerrainCut()));
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(120, 80, WaterSurfaceResolver.riverMaxTerrainCut()));
      assertTrue(WaterSurfaceResolver.shouldRejectRiverWaterCell(79, 80, WaterSurfaceResolver.riverMaxTerrainCut()));
   }

   @Test
   void groupsNearbyEsaLakeSamplesByStableBasinAndHeightBand() {
      long bodyKey = WaterSurfaceResolver.stableEsaLakeBodyKey(100, 120, 80);

      assertEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(510, 120, 83));
      assertNotEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(512, 120, 80));
      assertNotEquals(bodyKey, WaterSurfaceResolver.stableEsaLakeBodyKey(100, 120, 84));
   }

   @Test
   void keepsBroadLineConnectedWaterOutOfRiverMode() {
      assertFalse(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(32, 20, 1.6, 24, 12, 420, 12, true, false, false));
      assertFalse(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(64, 20, 3.2, 24, 12, 512, 0, false, true, false));
   }

   @Test
   void keepsNarrowLineDominatedWaterInRiverMode() {
      assertTrue(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(32, 2, 16.0, 24, 12, 48, 40, false, false, false));
      assertTrue(WaterSurfaceResolver.shouldClassifyInlandComponentAsRiver(4, 1, 4.0, 24, 12, 4, 4, false, false, false));
   }

   @Test
   void polygonRiversDoNotUseDirectLineWaterMask() {
      OsmWaterFeature polygonRiver = new OsmWaterFeature(
         1L,
         false,
         false,
         OsmWaterKind.RIVER,
         new double[][]{{0.0, 0.001, 0.001, 0.0, 0.0}},
         new double[][]{{0.0, 0.0, 0.001, 0.001, 0.0}}
      );
      OsmWaterFeature lineRiver = new OsmWaterFeature(
         2L,
         true,
         false,
         OsmWaterKind.RIVER,
         new double[][]{{0.0, 0.001}},
         new double[][]{{0.0, 0.001}}
      );

      assertTrue(polygonRiver.flowingWater());
      assertFalse(WaterSurfaceResolver.isLineWaterGeometry(polygonRiver));
      assertTrue(WaterSurfaceResolver.isLineWaterGeometry(lineRiver));
   }

   @Test
   void rejectsRiverCellsOnlyWhenWaterWouldSitAboveTerrain() {
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(90, 85, 4));
      assertFalse(WaterSurfaceResolver.shouldRejectRiverWaterCell(85, 85, 4));
      assertTrue(WaterSurfaceResolver.shouldRejectRiverWaterCell(83, 85, 4));
   }

   @Test
   void rejectedRiverCellsCapSurfaceToTerrain() {
      assertEquals(90, WaterSurfaceResolver.repairRejectedRiverWaterSurface(92, 90, 4));
      assertEquals(85, WaterSurfaceResolver.repairRejectedRiverWaterSurface(85, 89, 4));
   }

   @Test
   void fallbackOceanDepthIsDeeperThanMinimumWaterClamp() {
      assertTrue(WaterSurfaceResolver.fallbackOceanDepthBlocks(0, 0, 30.0, 1.0) > 1);
      assertEquals(
         WaterSurfaceResolver.fallbackOceanDepthBlocks(128, -64, 30.0, 1.0),
         WaterSurfaceResolver.fallbackOceanDepthBlocks(128, -64, 30.0, 1.0)
      );
   }

   @Test
   void experimentalOceanClampUsesReservedWorldFloorInsteadOfLegacyShellDepth() {
      assertEquals(-2_040, WaterSurfaceResolver.clampOceanTerrainHeight(-3_000, 0, -2_040, true));
      assertEquals(-900, WaterSurfaceResolver.clampOceanTerrainHeight(-900, 0, -2_040, true));
      assertEquals(-3_000, WaterSurfaceResolver.clampOceanTerrainHeight(-3_000, 0, -2_040, false));
   }

   @Test
   void landCoverWaterCandidatesIncludeEsaWaterAndNoDataOcean() {
      assertTrue(
         WaterSurfaceResolver.landCoverMayContainWater(80, TellusLandMaskSource.LandMaskSample.unknown(), Integer.MAX_VALUE, 64)
      );
      assertTrue(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.unknown(), 64, 64));
      assertTrue(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.known(false), 90, 64));
      assertFalse(WaterSurfaceResolver.landCoverMayContainWater(0, TellusLandMaskSource.LandMaskSample.known(true), 90, 64));
      assertFalse(WaterSurfaceResolver.landCoverMayContainWater(10, TellusLandMaskSource.LandMaskSample.known(false), 64, 64));
   }

   @Test
   void coarseOsmWaterDoesNotFallBackToEsaWater() {
      assertFalse(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(true, 80, false));
      assertTrue(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(false, 80, false));
      assertTrue(WaterSurfaceResolver.shouldEmitCoarseFallbackWater(true, 10, true));
   }

   @Test
   void capsRiverWaterSurfaceToTerrainAndBank() {
      assertEquals(90, WaterSurfaceResolver.capInlandRiverWaterSurface(96, 90, Integer.MAX_VALUE));
      assertEquals(86, WaterSurfaceResolver.capInlandRiverWaterSurface(96, 90, 86));
      assertEquals(84, WaterSurfaceResolver.capInlandRiverWaterSurface(84, 90, 86));
   }

   @Test
   void lowestRiverSurfaceUsesLowestRiverOrBankAltitude() {
      assertEquals(84, WaterSurfaceResolver.lowestInlandRiverWaterSurface(84, 90, 100));
      assertEquals(80, WaterSurfaceResolver.lowestInlandRiverWaterSurface(84, 80, 100));
      assertEquals(100, WaterSurfaceResolver.lowestInlandRiverWaterSurface(Integer.MAX_VALUE, Integer.MAX_VALUE, 100));
   }

   @Test
   void treatsExplicitFlowingPolygonWaterAsRiverWithoutRequiringAThinShape() {
      assertTrue(WaterSurfaceResolver.isFlowDominatedWaterComponent(100, 100));
      assertTrue(WaterSurfaceResolver.isFlowDominatedWaterComponent(100, 50));
      assertFalse(WaterSurfaceResolver.isFlowDominatedWaterComponent(100, 49));
   }

   @Test
   void appliesTerrainFollowingModeToExclusiveLineCellsInsideMixedComponents() {
      assertTrue(WaterSurfaceResolver.isLineOnlyWaterComponent(20, 20, 20));
      assertFalse(WaterSurfaceResolver.isLineOnlyWaterComponent(20, 19, 20));
      assertFalse(WaterSurfaceResolver.isLineOnlyWaterComponent(20, 20, 19));
      assertTrue(WaterSurfaceResolver.shouldUseDirectLineWaterCell(true, false));
      assertFalse(WaterSurfaceResolver.shouldUseDirectLineWaterCell(true, true));
      assertFalse(WaterSurfaceResolver.shouldUseDirectLineWaterCell(false, true));
      assertEquals(90, WaterSurfaceResolver.directLineRiverWaterSurface(90));
      assertEquals(89, WaterSurfaceResolver.directLineRiverTerrainSurface(90));
   }

   @Test
   void repairsShortFlowingWaterGapsWithoutJoiningDistantWater() {
      int side = 9;
      boolean[] water = new boolean[side * side];
      boolean[] ocean = new boolean[side * side];
      boolean[] line = new boolean[side * side];
      boolean[] flowing = new boolean[side * side];
      int left = 4 * side + 1;
      int right = 4 * side + 5;
      water[left] = water[right] = true;
      line[left] = line[right] = true;
      flowing[left] = flowing[right] = true;

      assertEquals(3, WaterSurfaceResolver.repairFlowingWaterGaps(water, ocean, line, flowing, side, 3));
      for (int x = 1; x <= 5; x++) {
         int index = 4 * side + x;
         assertTrue(water[index]);
         assertTrue(flowing[index]);
         assertTrue(line[index]);
      }

      int distant = 4 * side + 8;
      water[distant] = true;
      flowing[distant] = true;
      assertEquals(0, WaterSurfaceResolver.repairFlowingWaterGaps(water, ocean, line, flowing, side, 1));
      assertFalse(water[4 * side + 7]);
   }

   @Test
   void limitsNaturalizedInlandBanksToOneBlockOfRisePerBlock() {
      assertEquals(81, WaterSurfaceResolver.naturalizedInlandBankSurface(90, 80, 10, 5, true));
      assertEquals(82, WaterSurfaceResolver.naturalizedInlandBankSurface(90, 80, 20, 5, true));
      assertEquals(85, WaterSurfaceResolver.naturalizedInlandBankSurface(90, 80, 50, 5, true));
      assertEquals(84, WaterSurfaceResolver.naturalizedInlandBankSurface(90, 80, 20, 5, false));
      assertEquals(79, WaterSurfaceResolver.naturalizedInlandBankSurface(79, 80, 10, 5, true));
   }

   @Test
   void keepsRiverBedsShallowWhileAllowingWiderChannelsToDeepen() {
      assertEquals(1, WaterSurfaceResolver.computeRiverDepth(1.0));
      assertEquals(2, WaterSurfaceResolver.computeRiverDepth(5.0));
      assertEquals(6, WaterSurfaceResolver.computeRiverDepth(200.0));
   }

   @Test
   void waterfallDropColumnPreservesVisualTopWithoutPregeneratedWater() {
      int[] terrain = new int[256];
      int[] water = new int[256];
      byte[] flags = new byte[256];
      terrain[0] = 80;
      water[0] = 96;
      flags[0] = 3;
      WaterSurfaceResolver.WaterChunkData data = WaterSurfaceResolver.WaterChunkData.fromArrays(
         terrain, water, flags, false
      );

      assertFalse(data.hasWater(0, 0));
      assertTrue(data.isWaterfallDrop(0, 0));
      assertEquals(80, data.terrainSurface(0, 0));
      assertEquals(96, data.waterSurface(0, 0));
   }

   @Test
   void schedulesOnlyTheUpstreamSourceForConfirmedWaterfalls() {
      WaterSurfaceResolver.WaterfallInfo waterfall = new WaterSurfaceResolver.WaterfallInfo(true, 80, 96);

      assertTrue(WaterSurfaceResolver.shouldScheduleFlowSource(96, waterfall));
      assertFalse(WaterSurfaceResolver.shouldScheduleFlowSource(95, waterfall));
      assertFalse(WaterSurfaceResolver.shouldScheduleFlowSource(96, new WaterSurfaceResolver.WaterfallInfo(false, 80, 96)));
   }

   @Test
   void protectsOnlyMappedInlandWaterInsideWaterfallZonesFromSourceConversion() {
      assertTrue(WaterSurfaceResolver.isSourceConversionProtectedCell(true, true, false, true));
      assertTrue(WaterSurfaceResolver.isSourceConversionProtectedCell(true, false, true, true));
      assertFalse(WaterSurfaceResolver.isSourceConversionProtectedCell(false, true, true, true));
      assertFalse(WaterSurfaceResolver.isSourceConversionProtectedCell(true, false, false, true));
      assertFalse(WaterSurfaceResolver.isSourceConversionProtectedCell(true, true, true, false));
   }

}
