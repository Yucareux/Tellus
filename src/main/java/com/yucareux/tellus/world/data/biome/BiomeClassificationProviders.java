package com.yucareux.tellus.world.data.biome;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/** Loads optional classification providers once and keeps the hot path small. */
public final class BiomeClassificationProviders {
   private static final List<BiomeClassificationProvider> PROVIDERS = loadProviders();
   private static final Set<ResourceKey<Biome>> ALL_BIOME_KEYS = collectBiomeKeys();

   private BiomeClassificationProviders() {
   }

   public static ResourceKey<Biome> findBiomeKey(
      int esaCode,
      String koppenCode,
      int blockX,
      int blockZ,
      WorldProjection projection
   ) {
      for (BiomeClassificationProvider provider : PROVIDERS) {
         ResourceKey<Biome> key = provider.findBiomeKey(esaCode, koppenCode, blockX, blockZ, projection);
         if (key != null) {
            return key;
         }
      }
      return null;
   }

   public static Set<ResourceKey<Biome>> allBiomeKeys() {
      return ALL_BIOME_KEYS;
   }

   private static List<BiomeClassificationProvider> loadProviders() {
      List<BiomeClassificationProvider> providers = new ArrayList<>();
      try {
         ServiceLoader.load(BiomeClassificationProvider.class).forEach(providers::add);
      } catch (ServiceConfigurationError error) {
         Tellus.LOGGER.warn("Failed to load an optional biome classification provider", error);
      }
      providers.sort((left, right) -> left.getClass().getName().compareTo(right.getClass().getName()));
      return List.copyOf(providers);
   }

   private static Set<ResourceKey<Biome>> collectBiomeKeys() {
      Set<ResourceKey<Biome>> keys = new HashSet<>();
      for (BiomeClassificationProvider provider : PROVIDERS) {
         keys.addAll(provider.allBiomeKeys());
      }
      return Set.copyOf(keys);
   }
}
