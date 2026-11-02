package com.yucareux.tellus.world.data.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Unit text as Macrostrat serves it for the Grand Canyon, Yosemite, the Swiss Alps, Britain, Spain, New Zealand and the US. */
class MacrostratUnitClassifierTest {
   @Test
   void grandCanyonFormationsReadAsTheirRockAndColour() {
      assertEquals(MapUnit.of(Rock.SANDSTONE), classify(
         "Coconino Sandstone", "", "Tan to white, cliff-forming, fine-grained, well-sorted, cross-bedded quartz sandstone.", 290.1
      ));
      assertEquals(new MapUnit(Rock.RED_MUDSTONE, Rock.RED_SANDSTONE, 30), classify(
         "Hermit Formation", "", "Red, slope-forming, fine-grained, thin-bedded siltstone and sandstone.", 298.9
      ));
      assertEquals(MapUnit.of(Rock.LIMESTONE), classify(
         "Redwall Limestone", "", "Horseshoe Mesa Member - Light-olive-gray, ledge- and cliff-forming, thin-bedded, fine-grained limestone.", 358.9
      ));
      assertEquals(MapUnit.of(Rock.DARK_LIMESTONE), classify(
         "Muav Limestone", "", "Dark-gray, light-gray, brown, and orange-red, cliff-forming limestone, dolomite, and calcareous mudstone.", 509.0
      ));
      assertEquals(MapUnit.of(Rock.RED_SANDSTONE), classify(
         "Shinumo Quartzite", "", "Red-brown, purple, and gray, cliff-forming sandstone.", 1200.0
      ));
      assertEquals(MapUnit.of(Rock.SCHIST), classify("Vishnu Schist", "", "Quartz-mica schist, pelitic schist, and meta-arenites.", 1750.0));
   }

   @Test
   void plutonsAndVolcanicsReadFromNamesAndQualifiers() {
      assertEquals(MapUnit.of(Rock.GREY_GRANITE), classify(
         "El Capitan Granite and similar Rocks", "", "Coarse-grained, white to light-gray biotite granite and biotite granodiorite.", 103.0
      ));
      assertEquals(MapUnit.of(Rock.DIORITE), classify("Half Dome Granodiorite", "", "Medium-grained biotite-hornblende granodiorite.", 87.0));
      assertEquals(MapUnit.of(Rock.GRANITE), classify("Granitic rocks, undivided", "", "", 100.5));
      assertEquals(MapUnit.of(Rock.GRANITE), classify("Unnamed Igneous Intrusion", "felsic-rock", "", 410.0));
      assertEquals(MapUnit.of(Rock.AMPHIBOLITE), classify("Metavolcanic rocks, undivided", "", "Hornfels and other metamorphic rocks.", 201.3));
      assertEquals(new MapUnit(Rock.BASALT, Rock.TUFF, 30), classify("Unnamed Extrusive Rocks", "mafic lava and mafic tuff", "", 419.2));
      assertEquals(MapUnit.of(Rock.ULTRAMAFIC), classify("", "serpentinite and peridotite. basic and ultrabasic rocks", "", 358.9));
      // A rock list of just "volcanic" says less than the unit's name.
      assertEquals(Rock.TUFF, classify("Ash-flow tuff of main volcanic sequence", "Major:{volcanic}", "", 33.9).main());
   }

   @Test
   void rockListsGiveTheMainRockAndASecond() {
      assertEquals(new MapUnit(Rock.LIMESTONE, Rock.EVAPORITE, 30), classify(
         "Biogene Sedimentgesteine und Evaporite", "biogenic carbonate rocks and evaporite", "", 163.5
      ));
      assertEquals(new MapUnit(Rock.SCHIST, Rock.AMPHIBOLITE, 15), classify(
         "Aspiring lithologic association", "Major:: {pelite},Minor:: {amphibolite, schist, gneiss, metachert, marble}", "", 247.2
      ));
      assertEquals(new MapUnit(Rock.RED_MUDSTONE, Rock.RED_SANDSTONE, 30), classify(
         "Chinle Fm", "Major:{siltstone,sandstone}, Minor:{conglomerate}", "Red siltstone, sandstone, and limestone-pellet conglomerate", 237.0
      ));
      assertEquals(new MapUnit(Rock.CONGLOMERATE, Rock.RED_SANDSTONE, 30), classify(
         "Old Red Sandstone Supergroup", "conglomerate, sandstone, siltstone and mudstone", "", 423.0
      ));
   }

   @Test
   void looseCoverIceAndWaterGiveNoBedrock() {
      assertNull(classify("Landslide deposits", "", "Unconsolidated to partly consolidated", 0.774));
      assertNull(classify("Quartre Lockergesteine", "Loamy gravel, sand, silt, blocks (moraine)", "", 0.126));
      assertNull(classify("Pre-Tahoe Till", "", "Moraines hummocky and dissected.", 0.774));
      assertNull(classify("Gletscher, Firn", "Ice, snow", "", 0.0117));
      assertNull(classify("water", "", "", Double.NaN));
      assertNull(classify("Wescogame Formation", "", "", 300.0), "no rock words");
      // Gravel older than the Quaternary is conglomerate.
      assertEquals(MapUnit.of(Rock.CONGLOMERATE), classify("Bouldery gravel on old erosion surfaces", "Major:{gravel}", "", 23.0));
   }

   private static MapUnit classify(String name, String lith, String descrip, double bottomAgeMa) {
      return MacrostratUnitClassifier.classify(name, lith, descrip, bottomAgeMa);
   }
}
