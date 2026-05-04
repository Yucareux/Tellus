package com.yucareux.tellus.worldgen.vegetation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum ArnisTreeType {
   OAK,
   SPRUCE,
   BIRCH,
   DARK_OAK,
   JUNGLE,
   ACACIA;

   public static ArnisTreeType chooseDefault(long seed) {
      return switch ((int)Math.floorMod(mixSeed(seed), 10L)) {
         case 0, 1, 2 -> OAK;
         case 3, 4 -> SPRUCE;
         case 5, 6 -> BIRCH;
         case 7 -> DARK_OAK;
         case 8 -> JUNGLE;
         default -> ACACIA;
      };
   }

   public static ArnisTreeType chooseForPointTags(Map<String, String> tags, long seed) {
      List<ArnisTreeType> candidates = candidatesFromTags(tags);
      if (candidates.isEmpty()) {
         candidates.add(OAK);
         candidates.add(SPRUCE);
         candidates.add(BIRCH);
      }
      return choose(candidates, seed);
   }

   public static ArnisTreeType chooseForAreaTags(Map<String, String> tags, String areaType, long seed) {
      List<ArnisTreeType> candidates = candidatesFromTags(tags);
      if (candidates.isEmpty()) {
         switch (normalize(areaType)) {
            case "scrub", "heath" -> {
               candidates.add(OAK);
               candidates.add(BIRCH);
               candidates.add(ACACIA);
            }
            case "orchard" -> {
               candidates.add(OAK);
               candidates.add(BIRCH);
            }
            default -> {
               candidates.add(OAK);
               candidates.add(SPRUCE);
               candidates.add(BIRCH);
            }
         }
      }
      return choose(candidates, seed);
   }

   private static List<ArnisTreeType> candidatesFromTags(Map<String, String> tags) {
      List<ArnisTreeType> candidates = speciesCandidates(tags);
      if (!candidates.isEmpty()) {
         return candidates;
      }

      candidates = genusWikidataCandidates(tags);
      if (!candidates.isEmpty()) {
         return candidates;
      }

      candidates = genusCandidates(tags);
      if (!candidates.isEmpty()) {
         return candidates;
      }

      candidates = leafTypeCandidates(tags);
      if (!candidates.isEmpty()) {
         return candidates;
      }

      String leafCycle = normalize(tags.get("leaf_cycle"));
      if ("evergreen".equals(leafCycle)) {
         candidates.add(SPRUCE);
      }
      return candidates;
   }

   private static List<ArnisTreeType> speciesCandidates(Map<String, String> tags) {
      String species = normalize(join(tags, "species", "species:en", "taxon", "taxon:species"));
      List<ArnisTreeType> candidates = new ArrayList<>();
      addSpeciesMatch(candidates, species);
      return candidates;
   }

   private static List<ArnisTreeType> genusCandidates(Map<String, String> tags) {
      String genus = normalize(join(tags, "genus", "genus:en", "taxon:genus"));
      List<ArnisTreeType> candidates = new ArrayList<>();
      addSpeciesMatch(candidates, genus);
      return candidates;
   }

   private static List<ArnisTreeType> genusWikidataCandidates(Map<String, String> tags) {
      String value = normalize(tags.get("genus:wikidata"));
      List<ArnisTreeType> candidates = new ArrayList<>();
      if (value.contains("q12004")) {
         addUnique(candidates, BIRCH);
      }
      if (value.contains("q26782")) {
         addUnique(candidates, OAK);
      }
      if (value.contains("q25243")) {
         addUnique(candidates, SPRUCE);
      }
      return candidates;
   }

   private static List<ArnisTreeType> leafTypeCandidates(Map<String, String> tags) {
      String leafType = normalize(tags.get("leaf_type"));
      List<ArnisTreeType> candidates = new ArrayList<>();
      switch (leafType) {
         case "broadleaved", "broadleaf" -> {
            candidates.add(OAK);
            candidates.add(BIRCH);
         }
         case "needleleaved", "needleleaf" -> candidates.add(SPRUCE);
         default -> {
         }
      }
      return candidates;
   }

   private static void addSpeciesMatch(List<ArnisTreeType> candidates, String value) {
      if (value.contains("betula") || value.contains("birch")) {
         addUnique(candidates, BIRCH);
      }
      if (value.contains("quercus") || value.contains("oak")) {
         addUnique(candidates, OAK);
      }
      if (value.contains("picea") || value.contains("spruce") || value.contains("pinus") || value.contains("pine") || value.contains("abies") || value.contains("fir")) {
         addUnique(candidates, SPRUCE);
      }
      if (value.contains("acacia")) {
         addUnique(candidates, ACACIA);
      }
      if (value.contains("jungle") || value.contains("palm")) {
         addUnique(candidates, JUNGLE);
      }
   }

   private static ArnisTreeType choose(List<ArnisTreeType> candidates, long seed) {
      if (candidates.isEmpty()) {
         return chooseDefault(seed);
      }
      return candidates.get((int)Math.floorMod(mixSeed(seed), (long)candidates.size()));
   }

   private static void addUnique(List<ArnisTreeType> candidates, ArnisTreeType type) {
      if (!candidates.contains(type)) {
         candidates.add(type);
      }
   }

   private static String join(Map<String, String> tags, String... keys) {
      StringBuilder result = new StringBuilder();
      for (String key : keys) {
         String value = tags.get(key);
         if (value != null && !value.isBlank()) {
            if (!result.isEmpty()) {
               result.append(' ');
            }
            result.append(value);
         }
      }
      return result.toString();
   }

   private static String normalize(String value) {
      return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
   }

   private static long mixSeed(long seed) {
      long mixed = seed + 0x9E3779B97F4A7C15L;
      mixed = (mixed ^ mixed >>> 30) * 0xBF58476D1CE4E5B9L;
      mixed = (mixed ^ mixed >>> 27) * 0x94D049BB133111EBL;
      return mixed ^ mixed >>> 31;
   }
}
