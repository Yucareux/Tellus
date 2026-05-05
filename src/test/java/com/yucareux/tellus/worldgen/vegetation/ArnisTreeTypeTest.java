package com.yucareux.tellus.worldgen.vegetation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ArnisTreeTypeTest {
   @Test
   void choosesTreeTypeFromOsmSpeciesTags() {
      assertEquals(ArnisTreeType.BIRCH, ArnisTreeType.chooseForPointTags(Map.of("species", "Betula pendula"), 1L));
      assertEquals(ArnisTreeType.OAK, ArnisTreeType.chooseForPointTags(Map.of("species", "Quercus robur"), 1L));
      assertEquals(ArnisTreeType.SPRUCE, ArnisTreeType.chooseForPointTags(Map.of("species", "Picea abies"), 1L));
   }

   @Test
   void choosesTreeTypeFromArnisCompatibleFallbackTags() {
      assertEquals(ArnisTreeType.BIRCH, ArnisTreeType.chooseForPointTags(Map.of("genus:wikidata", "Q12004"), 2L));
      assertEquals(ArnisTreeType.OAK, ArnisTreeType.chooseForPointTags(Map.of("genus", "Quercus"), 2L));
      assertEquals(ArnisTreeType.SPRUCE, ArnisTreeType.chooseForPointTags(Map.of("leaf_type", "needleleaved"), 2L));
      assertTrue(
         ArnisTreeType.chooseForPointTags(Map.of("leaf_type", "broadleaved"), 2L) == ArnisTreeType.OAK
            || ArnisTreeType.chooseForPointTags(Map.of("leaf_type", "broadleaved"), 2L) == ArnisTreeType.BIRCH
      );
   }
}
