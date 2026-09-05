package com.yucareux.tellus.client.screen;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton.Builder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Minecraft 1.21.1 registry and widget API bridge for the shared customization screen. */
abstract class EarthCustomizeScreenVersionCompat extends Screen {
   protected EarthCustomizeScreenVersionCompat(Component title) {
      super(title);
   }

   protected final void renderVersionBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      // Screen.render renders the background in this release.
   }

   protected static <T> void registerCopy(
      Registry<T> source, MappedRegistry<T> copy, ResourceKey<T> key, T value
   ) {
      RegistrationInfo info = Objects.requireNonNull(source.registrationInfo(key).orElse(RegistrationInfo.BUILT_IN), "registrationInfo");
      copy.register(key, value, info);
   }

   protected static void registerUpdatedDimensionType(
      Registry<DimensionType> source,
      MappedRegistry<DimensionType> copy,
      ResourceKey<DimensionType> targetKey,
      DimensionType updatedType
   ) {
      Optional<KnownPack> emptyKnownPack = Optional.empty();
      RegistrationInfo targetInfo = Objects.requireNonNull(
         source.registrationInfo(targetKey)
            .map(info -> new RegistrationInfo(emptyKnownPack, Objects.requireNonNull(info.lifecycle(), "dimensionTypeLifecycle")))
            .orElseGet(() -> new RegistrationInfo(emptyKnownPack, Objects.requireNonNull(Lifecycle.experimental(), "experimentalLifecycle"))),
         "dimensionTypeRegistrationInfo"
      );
      copy.register(targetKey, Objects.requireNonNull(updatedType, "updatedType"), targetInfo);
   }

   protected static WorldDimensions updateDimensionsForVersion(
      WorldDimensions dimensions,
      Holder<DimensionType> overworldHolder,
      ChunkGenerator overworldGenerator,
      RegistryLookup<DimensionType> dimensionTypes,
      Function<Registry<LevelStem>, Registry<LevelStem>> registryUpdater
   ) {
      Map<ResourceKey<LevelStem>, LevelStem> updatedStems = new LinkedHashMap<>();
      dimensions.dimensions().forEach((key, stem) -> {
         Holder<DimensionType> typeHolder;
         if (key.equals(LevelStem.OVERWORLD)) {
            typeHolder = overworldHolder;
         } else {
            ResourceKey<DimensionType> typeKey = stem.type().unwrapKey().orElse(null);
            typeHolder = typeKey != null
               ? Objects.requireNonNull(dimensionTypes.getOrThrow(typeKey), "dimensionType")
               : Objects.requireNonNull(stem.type(), "stemDimensionType");
         }
         updatedStems.put(key, new LevelStem(typeHolder, stem.generator()));
      });
      return new WorldDimensions(WorldDimensions.withOverworld(updatedStems, overworldHolder, overworldGenerator));
   }

   protected static List<EarthGeneratorSettings.DistantHorizonsRenderMode> distantHorizonsModes() {
      List<EarthGeneratorSettings.DistantHorizonsRenderMode> modes = new ArrayList<>(3);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.FAST);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED);
      return modes;
   }

   protected static <T> Builder<T> configureCycleButton(
      Function<T, Component> formatter, T initialValue, List<T> values
   ) {
      return ClientMinecraftCompat.cycleButtonBuilder(formatter, initialValue, values);
   }

   protected static Builder<Boolean> configureBooleanCycleButton(Component yes, Component no, boolean initialValue) {
      return ClientMinecraftCompat.booleanCycleButtonBuilder(yes, no, initialValue);
   }
}
