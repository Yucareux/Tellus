package com.yucareux.tellus.client.screen;

import com.mojang.serialization.Lifecycle;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton.Builder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Minecraft 1.20.1 registry and widget API bridge for the shared customization screen. */
abstract class EarthCustomizeScreenVersionCompat extends Screen {
   protected EarthCustomizeScreenVersionCompat(Component title) {
      super(title);
   }

   protected final void renderVersionBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.renderBackground(graphics);
   }

   protected static <T> void registerCopy(
      Registry<T> source, MappedRegistry<T> copy, ResourceKey<T> key, T value
   ) {
      T sourceValue = Objects.requireNonNull(source.get(key), "sourceRegistryValue");
      copy.register(key, value, Objects.requireNonNull(source.lifecycle(sourceValue), "registryLifecycle"));
   }

   protected static void registerUpdatedDimensionType(
      Registry<DimensionType> source,
      MappedRegistry<DimensionType> copy,
      ResourceKey<DimensionType> targetKey,
      DimensionType updatedType
   ) {
      DimensionType existingTarget = source.get(targetKey);
      Lifecycle targetLifecycle = existingTarget != null
         ? Objects.requireNonNull(source.lifecycle(existingTarget), "dimensionTypeLifecycle")
         : Objects.requireNonNull(Lifecycle.experimental(), "experimentalLifecycle");
      copy.register(targetKey, Objects.requireNonNull(updatedType, "updatedType"), targetLifecycle);
   }

   protected static WorldDimensions updateDimensionsForVersion(
      WorldDimensions dimensions,
      Holder<DimensionType> overworldHolder,
      ChunkGenerator overworldGenerator,
      RegistryLookup<DimensionType> dimensionTypes,
      Function<Registry<LevelStem>, Registry<LevelStem>> registryUpdater
   ) {
      return new WorldDimensions(registryUpdater.apply(dimensions.dimensions()));
   }

   protected static List<EarthGeneratorSettings.DistantHorizonsRenderMode> distantHorizonsModes() {
      List<EarthGeneratorSettings.DistantHorizonsRenderMode> modes = new ArrayList<>(3);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.DETAILED);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.FAST);
      modes.add(EarthGeneratorSettings.DistantHorizonsRenderMode.ULTRA_FAST);
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
