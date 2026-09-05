package com.yucareux.tellus.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowSlopePolicyTest {
   @Test
   void usesConfiguredCoverageBands() {
      assertEquals(100, SnowSlopePolicy.coveragePercent(0.0));
      assertEquals(100, SnowSlopePolicy.coveragePercent(44.999));
      assertEquals(75, SnowSlopePolicy.coveragePercent(45.0));
      assertEquals(75, SnowSlopePolicy.coveragePercent(49.999));
      assertEquals(40, SnowSlopePolicy.coveragePercent(50.0));
      assertEquals(40, SnowSlopePolicy.coveragePercent(54.999));
      assertEquals(10, SnowSlopePolicy.coveragePercent(55.0));
      assertEquals(10, SnowSlopePolicy.coveragePercent(59.999));
      assertEquals(0, SnowSlopePolicy.coveragePercent(60.0));
      assertEquals(0, SnowSlopePolicy.coveragePercent(90.0));
   }

   @Test
   void missingSlopePreservesSnowAndVerySteepTerrainRejectsIt() {
      assertTrue(SnowSlopePolicy.shouldCover(12, 34, Double.NaN));
      assertFalse(SnowSlopePolicy.shouldCover(12, 34, 60.0));
      assertTrue(SnowSlopePolicy.hasFullCoverage(Double.NaN));
      assertTrue(SnowSlopePolicy.hasFullCoverage(44.999));
      assertFalse(SnowSlopePolicy.hasFullCoverage(45.0));
   }

   @Test
   void computesSlopeFromDemElevationsAndMetricSampleDistances() {
      assertEquals(45.0, SnowSlopePolicy.slopeDegrees(30.0, -30.0, 0.0, 0.0, 60.0, 60.0), 1.0E-9);
      assertEquals(0.0, SnowSlopePolicy.slopeDegrees(10.0, 10.0, 10.0, 10.0, 60.0, 60.0), 1.0E-9);
   }
}
