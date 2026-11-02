package com.yucareux.tellus.world.data.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class TellusLithologySourceTest {
   @Test
   void bundledLookupKnowsWellKnownRock() {
      // Loading the lookup logs through Tellus.LOGGER, which initializes TellusCommon's registry keys.
      assumeFalse(isMinecraftForge(), "Forge's raw JUnit bootstrap cannot initialize vanilla registries");
      TellusLithologySource source = TellusLithologySource.shared();
      assumeTrue(source.available(), "bundled lithology lookup");

      assertEquals(Lithology.ACID_PLUTONIC, source.sampleAtLonLat(-119.533, 37.746), "Yosemite granite");
      assertEquals(Lithology.CARBONATE_SEDIMENTARY, source.sampleAtLonLat(11.85, 46.43), "Dolomites limestone");
      assertEquals(Lithology.BASIC_VOLCANIC, source.sampleAtLonLat(73.66, 17.92), "Deccan basalt");
      assertEquals(Lithology.BASIC_PLUTONIC, source.sampleAtLonLat(8.31, 61.64), "Jotunheimen gabbro");
      assertEquals(Lithology.UNCONSOLIDATED_SEDIMENT, source.sampleAtLonLat(85.1, 25.6), "Ganges alluvium");
      assertEquals(Lithology.UNKNOWN, source.sampleAtLonLat(-150.0, 0.0), "open Pacific");
      assertEquals(source.sampleAtLonLat(-119.533, 37.746), source.sampleAtLonLat(-119.533 + 360.0, 37.746), "longitude wraps");
   }

   private static boolean isMinecraftForge() {
      try {
         Class.forName("net.minecraftforge.fml.ModList", false, TellusLithologySourceTest.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }
}
