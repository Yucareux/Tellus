package com.yucareux.tellus.worldgen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Vanilla Overworld biomes that can safely participate in Tellus random-biome placement. */
public final class RandomBiomeCatalog {
   private static final List<String> LEGACY_OVERWORLD_BIOMES = List.of(
      "badlands",
      "bamboo_jungle",
      "beach",
      "birch_forest",
      "cherry_grove",
      "cold_ocean",
      "dark_forest",
      "deep_cold_ocean",
      "deep_dark",
      "deep_frozen_ocean",
      "deep_lukewarm_ocean",
      "deep_ocean",
      "desert",
      "dripstone_caves",
      "eroded_badlands",
      "flower_forest",
      "forest",
      "frozen_ocean",
      "frozen_peaks",
      "frozen_river",
      "grove",
      "ice_spikes",
      "jagged_peaks",
      "jungle",
      "lukewarm_ocean",
      "lush_caves",
      "mangrove_swamp",
      "meadow",
      "mushroom_fields",
      "ocean",
      "old_growth_birch_forest",
      "old_growth_pine_taiga",
      "old_growth_spruce_taiga",
      "plains",
      "river",
      "savanna",
      "savanna_plateau",
      "snowy_beach",
      "snowy_plains",
      "snowy_slopes",
      "snowy_taiga",
      "sparse_jungle",
      "stony_peaks",
      "stony_shore",
      "sunflower_plains",
      "swamp",
      "taiga",
      "warm_ocean",
      "windswept_forest",
      "windswept_gravelly_hills",
      "windswept_hills",
      "windswept_savanna",
      "wooded_badlands"
   );
   private static final List<String> MINECRAFT_1_21_11_ADDITIONS = List.of("pale_garden");
   private static final List<String> MINECRAFT_1_21_11_OVERWORLD_BIOMES = append(LEGACY_OVERWORLD_BIOMES, MINECRAFT_1_21_11_ADDITIONS);
   private static final List<String> MINECRAFT_26_2_ADDITIONS = List.of("sulfur_caves");
   private static final List<String> ALL_KNOWN_OVERWORLD_BIOMES = append(MINECRAFT_1_21_11_OVERWORLD_BIOMES, MINECRAFT_26_2_ADDITIONS);
   private static final Set<String> NON_LAND_BIOMES = Set.of(
      "ocean",
      "warm_ocean",
      "lukewarm_ocean",
      "cold_ocean",
      "frozen_ocean",
      "deep_ocean",
      "deep_lukewarm_ocean",
      "deep_cold_ocean",
      "deep_frozen_ocean",
      "river",
      "frozen_river",
      "lush_caves",
      "dripstone_caves",
      "deep_dark",
      "sulfur_caves"
   );

   private RandomBiomeCatalog() {
   }

   public static List<String> legacyOverworldBiomeIds() {
      return LEGACY_OVERWORLD_BIOMES;
   }

   public static List<String> minecraft26_2OverworldBiomeIds() {
      return ALL_KNOWN_OVERWORLD_BIOMES;
   }

   public static List<String> minecraft1_21_11OverworldBiomeIds() {
      return MINECRAFT_1_21_11_OVERWORLD_BIOMES;
   }

   public static List<String> allKnownOverworldBiomeIds() {
      return ALL_KNOWN_OVERWORLD_BIOMES;
   }

   public static List<String> normalizeLegacySelection(List<String> biomeIds) {
      return normalizeSelection(biomeIds, LEGACY_OVERWORLD_BIOMES);
   }

   public static List<String> normalizeMinecraft26_2Selection(List<String> biomeIds) {
      return normalizeSelection(biomeIds, ALL_KNOWN_OVERWORLD_BIOMES);
   }

   public static List<String> normalizeMinecraft1_21_11Selection(List<String> biomeIds) {
      return normalizeSelection(biomeIds, MINECRAFT_1_21_11_OVERWORLD_BIOMES);
   }

   public static List<String> normalizeSelection(List<String> biomeIds) {
      return normalizeSelection(biomeIds, ALL_KNOWN_OVERWORLD_BIOMES);
   }

   private static List<String> normalizeSelection(List<String> biomeIds, List<String> supportedBiomes) {
      if (biomeIds == null) {
         return supportedBiomes;
      }

      Set<String> normalized = new LinkedHashSet<>();
      for (String biomeId : biomeIds) {
         if (biomeId == null) {
            continue;
         }

         String id = biomeId.trim().toLowerCase(Locale.ROOT);
         if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
         }
         if (supportedBiomes.contains(id)) {
            normalized.add(id);
         }
      }
      return List.copyOf(normalized);
   }

   public static boolean hasLandBiomeSelection(List<String> biomeIds) {
      return normalizeSelection(biomeIds).stream().anyMatch(id -> !NON_LAND_BIOMES.contains(id));
   }

   private static List<String> append(List<String> first, List<String> second) {
      List<String> result = new ArrayList<>(first.size() + second.size());
      result.addAll(first);
      result.addAll(second);
      return List.copyOf(result);
   }
}
