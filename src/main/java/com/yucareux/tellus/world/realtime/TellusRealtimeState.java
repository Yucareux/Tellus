package com.yucareux.tellus.world.realtime;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;

public final class TellusRealtimeState {
   private static final float SNOW_THRESHOLD = 0.35F;
   private static final float SNOW_JITTER = 0.15F;
   private static volatile boolean weatherEnabled;
   private static volatile boolean historicalSnowEnabled;
   private static volatile TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.PrecipitationMode.CLEAR;
   private static volatile SnowGrid snowGrid = SnowGrid.empty();
   private static volatile TemperatureGrid temperatureGrid = TemperatureGrid.empty();
   // Server only: lake freezing reads it, clients never receive it.
   private static volatile TemperatureGrid recentTemperatureGrid = TemperatureGrid.empty();

   private TellusRealtimeState() {
   }

   public static void updateWeatherState(
      boolean weatherEnabled, TellusRealtimeState.PrecipitationMode precipitationMode, boolean historicalSnowEnabled, SnowGrid snowGrid
   ) {
      updateWeatherState(weatherEnabled, precipitationMode, historicalSnowEnabled, snowGrid, TellusRealtimeState.temperatureGrid);
   }

   public static void updateWeatherState(
      boolean weatherEnabled,
      TellusRealtimeState.PrecipitationMode precipitationMode,
      boolean historicalSnowEnabled,
      SnowGrid snowGrid,
      TemperatureGrid temperatureGrid
   ) {
      TellusRealtimeState.weatherEnabled = weatherEnabled;
      TellusRealtimeState.historicalSnowEnabled = historicalSnowEnabled;
      TellusRealtimeState.precipitationMode = Objects.requireNonNull(precipitationMode, "precipitationMode");
      TellusRealtimeState.snowGrid = snowGrid == null ? SnowGrid.empty() : snowGrid;
      TellusRealtimeState.temperatureGrid = temperatureGrid == null ? TemperatureGrid.empty() : temperatureGrid;
   }

   public static void clearRealtimeWeather() {
      TellusRealtimeState.weatherEnabled = false;
      TellusRealtimeState.historicalSnowEnabled = false;
      TellusRealtimeState.precipitationMode = TellusRealtimeState.PrecipitationMode.CLEAR;
      TellusRealtimeState.snowGrid = SnowGrid.empty();
   }

   public static void reset() {
      clearRealtimeWeather();
      clearTemperature();
   }

   public static boolean isWeatherEnabled() {
      return weatherEnabled;
   }

   public static boolean isHistoricalSnowEnabled() {
      return historicalSnowEnabled;
   }

   public static TellusRealtimeState.PrecipitationMode precipitationMode() {
      return precipitationMode;
   }

   public static SnowGrid snowGrid() {
      return snowGrid;
   }

   public static TemperatureGrid temperatureGrid() {
      return temperatureGrid;
   }

   public static void updateRecentTemperature(TemperatureGrid recentTemperatureGrid) {
      TellusRealtimeState.recentTemperatureGrid = recentTemperatureGrid == null ? TemperatureGrid.empty() : recentTemperatureGrid;
   }

   public static void clearTemperature() {
      TellusRealtimeState.temperatureGrid = TemperatureGrid.empty();
      TellusRealtimeState.recentTemperatureGrid = TemperatureGrid.empty();
   }

   public static float realWorldTemperatureC(BlockPos pos) {
      return pos == null ? Float.NaN : temperatureGrid.sample(pos.getX(), pos.getZ());
   }

   public static boolean hasRealWorldTemperature(BlockPos pos) {
      return WeatherTemperaturePolicy.hasTemperature(realWorldTemperatureC(pos));
   }

   public static boolean canLakeFreeze(BlockPos pos) {
      return WeatherTemperaturePolicy.canLakeFreeze(realWorldTemperatureC(pos), recentTemperatureGrid.sample(pos.getX(), pos.getZ()));
   }

   public static boolean canLakeIceMelt(BlockPos pos) {
      return WeatherTemperaturePolicy.canLakeIceMelt(realWorldTemperatureC(pos), recentTemperatureGrid.sample(pos.getX(), pos.getZ()));
   }

   public static Precipitation precipitationOverride(boolean biomeHasPrecipitation, BlockPos pos) {
      TellusRealtimeState.PrecipitationMode mode = precipitationMode;
      if (weatherEnabled && mode != TellusRealtimeState.PrecipitationMode.CLEAR) {
         return mode == TellusRealtimeState.PrecipitationMode.SNOW ? Precipitation.SNOW : Precipitation.RAIN;
      }

      float temperatureC = realWorldTemperatureC(pos);
      if (!biomeHasPrecipitation || !WeatherTemperaturePolicy.hasTemperature(temperatureC)) {
         return null;
      }

      return WeatherTemperaturePolicy.shouldSnow(temperatureC) ? Precipitation.SNOW : Precipitation.RAIN;
   }

   public static Precipitation precipitationOverride(Biome biome, BlockPos pos, int seaLevel) {
      Objects.requireNonNull(biome, "biome");
      return precipitationOverride(biome.hasPrecipitation(), Objects.requireNonNull(pos, "pos"));
   }

   public static Boolean snowTemperatureOverride(boolean biomeHasPrecipitation, BlockPos pos) {
      TellusRealtimeState.PrecipitationMode mode = precipitationMode;
      if (weatherEnabled && mode != TellusRealtimeState.PrecipitationMode.CLEAR) {
         return mode == TellusRealtimeState.PrecipitationMode.SNOW;
      }

      float temperatureC = realWorldTemperatureC(pos);
      return WeatherTemperaturePolicy.hasTemperature(temperatureC)
         ? biomeHasPrecipitation && WeatherTemperaturePolicy.shouldSnow(temperatureC)
         : null;
   }

   public static float sampleSnowCoverage(int blockX, int blockZ) {
      if (!historicalSnowEnabled) {
         return 0.0F;
      } else {
         SnowGrid grid = snowGrid;
         if (grid != null && !grid.isEmpty()) {
            float base = grid.sample(blockX, blockZ);
            if (base <= 0.0F) {
               return 0.0F;
            } else {
               float jitter = (hashToUnit(blockX, blockZ) - 0.5F) * SNOW_JITTER;
               return Mth.clamp(base + jitter, 0.0F, 1.0F);
            }
         } else {
            return 0.0F;
         }
      }
   }

   public static boolean shouldApplySnow(int blockX, int blockZ) {
      return weatherEnabled && precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW
         ? true
         : sampleSnowCoverage(blockX, blockZ) >= SNOW_THRESHOLD;
   }

   private static float hashToUnit(int x, int z) {
      long seed = x * 341873128712L + z * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return (float)(seed & 16777215L) / 1.6777215E7F;
   }

   public static enum PrecipitationMode {
      CLEAR,
      RAIN,
      SNOW,
      THUNDER;
   }
}
