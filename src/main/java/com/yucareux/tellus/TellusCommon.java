package com.yucareux.tellus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.yucareux.tellus.integration.distant_horizons.DistantHorizonsIntegration;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainDownloadManager;
import com.yucareux.tellus.integration.voxy.TellusVoxyPregenManager;
import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.compat.TellusMinecraftCompat;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.platform.TellusRuntimePlatform;
import com.yucareux.tellus.world.realtime.TellusRealtimeManager;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.world.realtime.WeatherTemperaturePolicy;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.ExperimentalHeightSupport;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TellusCommon {

   public static final String MOD_ID = "tellus";
   private static final String DYNAMIC_DIMENSION_PACK_NAME = "tellus_dynamic_dimension";
   private static final String DYNAMIC_DIMENSION_PACK_ID = "file/tellus_dynamic_dimension";

   private static final ResourceKey<DimensionType> EARTH_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, MinecraftRelease.resourceLocation("tellus", "earth")), "earthDimensionKey"
   );

   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, MinecraftRelease.resourceLocation("tellus", "earth_dynamic")), "dynamicDimensionKey"
   );
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final TellusRealtimeManager REALTIME_MANAGER = new TellusRealtimeManager();
   private static final TellusVoxyPregenManager VOXY_PREGEN_MANAGER = new TellusVoxyPregenManager();
   private static final ManagedTerrainDownloadManager MANAGED_TERRAIN_DOWNLOAD_MANAGER = new ManagedTerrainDownloadManager();
   private static final Map<UUID, CompletableFuture<BlockPos>> GEO_TELEPORT_LOOKUPS = new ConcurrentHashMap<>();
   private static final Map<UUID, TellusCommon.TeleportProgress> GEO_TELEPORT_PROGRESS = new ConcurrentHashMap<>();
   private static volatile Boolean teleportProgressOverride;
   public static final Logger LOGGER = LoggerFactory.getLogger("tellus");


   public static void validateRuntime() {
      configureHttpKeepAlive();
      ExperimentalHeightSupport.validateActiveRuntimeProfileOrThrow();
   }

   /**
    * Terrain sources fetch thousands of small HTTP ranges from a handful of hosts. The JDK keeps only five
    * idle keep-alive sockets per host by default, so parallel downloads keep re-running TCP/TLS handshakes.
    * Raise the limit unless the user configured it; it applies if no HTTP connection has been pooled yet.
    */
   private static void configureHttpKeepAlive() {
      if (System.getProperty("http.maxConnections") == null) {
         System.setProperty("http.maxConnections", "32");
      }
   }

   public static void initializeRuntime(TellusRuntimePlatform runtime) {
      runtime.registerCommands(
            dispatcher -> dispatcher.register(
               ((Commands.literal("tellus")
                        .then(
                           (Commands.literal("map")
                                 .requires(TellusMinecraftCompat::hasGamemasterPermission))
                              .executes(context -> openGeoTpMap((CommandSourceStack)context.getSource()))
                        ))
                     .then(
                        (Commands.literal("weather")
                              .executes(context -> showTellusWeather((CommandSourceStack)context.getSource())))
                           .then(
                              Commands.literal("enable_realtime_time")
                                 .requires(TellusMinecraftCompat::hasGamemasterPermission)
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeTimeOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                           .then(
                              Commands.literal("enable_realtime_weather")
                                 .requires(TellusMinecraftCompat::hasGamemasterPermission)
                                 .then(
                                    Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                       .executes(
                                          context -> setRealtimeWeatherOverride(
                                             (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                          )
                                       )
                                 )
                           )
                     ))
                  .then(
                     ((Commands.literal("config")
                              .requires(TellusMinecraftCompat::hasGamemasterPermission))
                           .then(
                              (Commands.literal("weather")
                                    .then(
                                       Commands.literal("enable_realtime_time")
                                          .then(
                                             Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                .executes(
                                                   context -> setRealtimeTimeOverride(
                                                      (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("enable_realtime_weather")
                                       .then(
                                          Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                             .executes(
                                                context -> setRealtimeWeatherOverride(
                                                   (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                )
                                             )
                                       )
                                 )
                           ))
                        .then(
                           Commands.literal("teleport_progress")
                              .then(
                                 Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                    .executes(
                                       context -> setTeleportProgressOverride(
                                          (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                       )
                                    )
                              )
                        )
                        .then(
                           ((((Commands.literal("voxy")
                                          .then(Commands.literal("status").executes(context -> showVoxyPregenStatus((CommandSourceStack)context.getSource()))))
                                       .then(
                                          Commands.literal("enable_pregen")
                                             .then(
                                                Commands.argument("enabled", Objects.requireNonNull(BoolArgumentType.bool(), "enabledArgument"))
                                                   .executes(
                                                      context -> setVoxyPregenEnabledOverride(
                                                         (CommandSourceStack)context.getSource(), BoolArgumentType.getBool(context, "enabled")
                                                      )
                                                   )
                                             )
                                       ))
                                    .then(
                                       Commands.literal("max_radius")
                                          .then(
                                             Commands.argument("chunks", Objects.requireNonNull(IntegerArgumentType.integer(0, 1024), "maxRadiusArgument"))
                                                .executes(
                                                   context -> setVoxyPregenMaxRadiusOverride(
                                                      (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "chunks")
                                                   )
                                                )
                                          )
                                    ))
                                 .then(
                                    Commands.literal("chunks_per_tick")
                                       .then(
                                          Commands.argument("value", Objects.requireNonNull(IntegerArgumentType.integer(1, 200), "chunksPerTickArgument"))
                                             .executes(
                                                context -> setVoxyPregenChunksPerTickOverride(
                                                   (CommandSourceStack)context.getSource(), IntegerArgumentType.getInteger(context, "value")
                                                )
                                             )
                                       )
                                 ))
                              .then(Commands.literal("reset").executes(context -> resetVoxyPregenOverrides((CommandSourceStack)context.getSource())))
                        )
                  )
            )
         );
      runtime.onServerStarted(server -> server.execute(() -> {
         ServerLevel world = server.getLevel(Level.OVERWORLD);
         if (world != null) {
            ChunkGenerator generator = world.getChunkSource().getGenerator();
            logOverworldSettings(server, world, generator);
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               ExperimentalHeightSupport.configureWorldBorder(earthGenerator.settings(), world.getWorldBorder());
               // No spawn here: MinecraftServerInitialSpawnMixin sets it once when the world is created, so /setworldspawn survives restarts.
               ensureDynamicDimensionPack(server, world.dimensionTypeRegistration(), world.dimensionType(), earthGenerator);
            }
         }
      }));
      runtime.onServerStopping(server -> {
         GEO_TELEPORT_PROGRESS.clear();
         teleportProgressOverride = null;
         REALTIME_MANAGER.onServerStopping(server);
         VOXY_PREGEN_MANAGER.shutdown();
         MANAGED_TERRAIN_DOWNLOAD_MANAGER.reset();
      });
      runtime.onServerTick(REALTIME_MANAGER::onServerTick);
      runtime.onServerTick(VOXY_PREGEN_MANAGER::onServerTick);
      runtime.onServerTick(server -> GEO_TELEPORT_PROGRESS.values().forEach(TellusCommon.TeleportProgress::update));
      runtime.onServerTick(MANAGED_TERRAIN_DOWNLOAD_MANAGER::onServerTick);
      runtime.onServerTick(server -> {
         if (MANAGED_TERRAIN_DOWNLOAD_MANAGER.shouldBroadcastStatus()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               MANAGED_TERRAIN_DOWNLOAD_MANAGER.statusFor(player)
                  .ifPresent(status -> TellusPlatform.sendManagedTerrainStatusPayload(player, new ManagedTerrainStatusPayload(status)));
            }
         }
      });
      runtime.onServerTick(server -> {
         for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               earthGenerator.processDeferredChunkDetailTick(level);
            }
         }
      });
      runtime.onChunkUnload((level, chunkPos) -> {
         ChunkGenerator generator = level.getChunkSource().getGenerator();
         if (generator instanceof EarthChunkGenerator earthGenerator) {
            earthGenerator.discardPreparedChunkState(chunkPos);
         }
      });
      runtime.onPlayerJoin(REALTIME_MANAGER::onPlayerJoin);
      runtime.onPlayerDisconnect(MANAGED_TERRAIN_DOWNLOAD_MANAGER::onPlayerDisconnect);
      if (TellusPlatform.isModLoaded("distanthorizons")) {
         DistantHorizonsIntegration.bootstrap();
      }

      LOGGER.info(
         "Tellus worldgen initialized{}",
         ExperimentalHeightSupport.isRuntimeProfileActive() ? " with the dense global packed-coordinate profile" : ""
      );
   }

   private static int openGeoTpMap(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.translatable("tellus.command.geotp.player_only"));
         return 0;
      } else {
         ServerLevel level = MinecraftVersionCompat.serverLevel(player);
         if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
            double latitude = clampLatitude(earthGenerator.latitudeFromBlock(player.getZ()));
            double longitude = clampLongitude(earthGenerator.longitudeFromBlock(player.getX()));
            TellusPlatform.sendGeoTpOpenMapPayload(player, new GeoTpOpenMapPayload(latitude, longitude));
            return 1;
         } else {
            source.sendFailure(Component.translatable("tellus.command.geotp.tellus_world_only"));
            return 0;
         }
      }
   }

   private static int showTellusWeather(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.translatable("tellus.command.weather.player_only"));
         return 0;
      }

      ServerLevel level = MinecraftVersionCompat.serverLevel(player);
      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
         source.sendFailure(Component.translatable("tellus.command.weather.tellus_world_only"));
         return 0;
      }

      BlockPos pos = player.blockPosition();
      source.sendSuccess(() -> Component.translatable("tellus.command.weather.fetching").withStyle(ChatFormatting.GRAY), false);
      TellusRealtimeManager.WeatherReportRequestResult requestResult = REALTIME_MANAGER.requestWeatherReport(
         source.getServer(),
         player.getUUID(),
         earthGenerator,
         pos,
         report -> sendTellusWeatherReport(source, level, pos, earthGenerator.settings(), report)
      );
      if (requestResult == TellusRealtimeManager.WeatherReportRequestResult.RATE_LIMITED) {
         source.sendFailure(Component.translatable("tellus.command.weather.rate_limited"));
         return 0;
      }
      if (requestResult == TellusRealtimeManager.WeatherReportRequestResult.UNAVAILABLE) {
         source.sendFailure(Component.translatable("tellus.command.weather.unavailable"));
         return 0;
      }

      return 1;
   }

   private static void sendTellusWeatherReport(
      CommandSourceStack source,
      ServerLevel level,
      BlockPos pos,
      EarthGeneratorSettings settings,
      TellusRealtimeManager.WeatherReport report
   ) {
      boolean realtimeTime = REALTIME_MANAGER.isRealtimeTimeEnabled(settings);
      boolean realtimeWeather = REALTIME_MANAGER.isRealtimeWeatherEnabled(settings);
      boolean realtimeWeatherActive = realtimeWeather && TellusRealtimeState.isWeatherEnabled();
      TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
      TellusCommon.WeatherDisplay weather = realtimeWeatherActive
         ? weatherFromRealtime(mode)
         : weatherFromVanilla(level, pos, report.temperatureC());
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.title").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false
      );
      String coordinates = Objects.requireNonNull(
         String.format(Locale.ROOT, "%.4f, %.4f", report.latitude(), report.longitude()), "coordinates"
      );
      String locationText = report.locationName() == null || report.locationName().isBlank()
         ? coordinates
         : report.locationName() + " (" + coordinates + ")";
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.location")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(locationText).withStyle(ChatFormatting.AQUA)),
         false
      );
      String gameTime = Objects.requireNonNull(formatGameTime(level), "gameTime");
      source.sendSuccess(
         () -> Component.translatable("tellus.command.weather.game_time")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(gameTime).withStyle(ChatFormatting.YELLOW)),
         false
      );
      ZoneId zone = resolveZoneId(report.timeZoneId());
      boolean approximateTime = zone == null;
      if (zone == null && realtimeTime && REALTIME_MANAGER.hasTimeOffset()) {
         ZoneId managerZone = REALTIME_MANAGER.currentTimeZone();
         if (managerZone != null) {
            zone = managerZone;
            approximateTime = false;
         }
      }

      if (zone == null) {
         int offsetSeconds = approximateUtcOffsetSeconds(report.longitude());
         zone = ZoneOffset.ofTotalSeconds(offsetSeconds);
         approximateTime = true;
      }

      Instant now = REALTIME_MANAGER.currentInstant();
      int offsetSeconds = zone.getRules().getOffset(now).getTotalSeconds();
      String timeLabel = formatLocalTime(now, zone);
      String utcOffsetLabel = formatUtcOffset(offsetSeconds);
      MutableComponent timeLine = Component.translatable("tellus.command.weather.real_time")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.literal(timeLabel + " " + utcOffsetLabel).withStyle(ChatFormatting.YELLOW));
      if (approximateTime) {
         timeLine.append(Component.translatable("tellus.command.weather.approximate").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> timeLine, false);
      MutableComponent tempLine = Component.translatable("tellus.command.weather.temperature").withStyle(ChatFormatting.GRAY);
      if (Float.isFinite(report.temperatureC())) {
         String tempLabel = Objects.requireNonNull(String.format(Locale.ROOT, "%.1f C", report.temperatureC()), "tempLabel");
         tempLine.append(Component.literal(tempLabel).withStyle(ChatFormatting.YELLOW));
      } else {
         tempLine.append(Component.translatable("tellus.command.weather.not_available").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> tempLine, false);
      ChatFormatting weatherColor = Objects.requireNonNull(weather.color(), "weatherColor");
      MutableComponent weatherLine = Component.translatable("tellus.command.weather.weather")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.translatable(weather.translationKey()).withStyle(weatherColor));
      if (!realtimeWeather) {
         String weatherSourceKey = Float.isFinite(report.temperatureC())
            ? "tellus.command.weather.source.vanilla_temperature"
            : "tellus.command.weather.source.vanilla_fallback";
         weatherLine.append(Component.translatable(weatherSourceKey).withStyle(ChatFormatting.DARK_GRAY));
      } else if (!realtimeWeatherActive) {
         weatherLine.append(Component.translatable("tellus.command.weather.source.realtime_pending").withStyle(ChatFormatting.DARK_GRAY));
      }

      source.sendSuccess(() -> weatherLine, false);
   }

   private static int setRealtimeTimeOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.weather_world_only"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeTimeOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.time_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setRealtimeWeatherOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.weather_world_only"));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeWeatherOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.weather_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setTeleportProgressOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.geotp.tellus_world_only"));
         return 0;
      } else {
         teleportProgressOverride = enabled;
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.teleport_progress_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setVoxyPregenEnabledOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setEnabledOverride(enabled);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.config.voxy_enabled_set", booleanLabel(enabled)), false
         );
         return 1;
      }
   }

   private static int setVoxyPregenMaxRadiusOverride(CommandSourceStack source, int chunks) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setMaxRadiusOverride(chunks);
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_radius_set", chunks), false);
         return 1;
      }
   }

   private static int setVoxyPregenChunksPerTickOverride(CommandSourceStack source, int chunksPerTick) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setChunksPerTickOverride(chunksPerTick);
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_budget_set", chunksPerTick), false);
         return 1;
      }
   }

   private static int resetVoxyPregenOverrides(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.clearOverrides();
         source.sendSuccess(() -> Component.translatable("tellus.command.config.voxy_reset"), false);
         return 1;
      }
   }

   private static int showVoxyPregenStatus(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.translatable("tellus.command.config.voxy_world_only"));
         return 0;
      } else {
         EarthGeneratorSettings settings = earthGenerator.settings();
         boolean enabled = VOXY_PREGEN_MANAGER.effectiveEnabled(settings);
         int maxRadius = VOXY_PREGEN_MANAGER.effectiveMaxRadius(settings);
         int chunksPerTick = VOXY_PREGEN_MANAGER.effectiveChunksPerTick(settings);
         source.sendSuccess(
            () -> Component.translatable("tellus.command.voxy.title").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.enabled",
                  booleanLabel(enabled),
                  booleanLabel(settings.voxyChunkPregenEnabled()),
                  overrideLabel(VOXY_PREGEN_MANAGER.enabledOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.radius",
                  maxRadius,
                  settings.voxyChunkPregenMaxRadius(),
                  overrideLabel(VOXY_PREGEN_MANAGER.maxRadiusOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.budget",
                  chunksPerTick,
                  settings.voxyChunkPregenChunksPerTick(),
                  overrideLabel(VOXY_PREGEN_MANAGER.chunksPerTickOverride())
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.effective_radius",
                  VOXY_PREGEN_MANAGER.lastConfiguredVoxyRadiusChunks(),
                  VOXY_PREGEN_MANAGER.lastEffectiveRadiusChunks()
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         source.sendSuccess(
            () -> Component.translatable(
                  "tellus.command.voxy.queue", VOXY_PREGEN_MANAGER.queuedChunkCount(), VOXY_PREGEN_MANAGER.inFlightChunkCount()
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         return 1;
      }
   }

   private static TellusCommon.WeatherDisplay weatherFromRealtime(TellusRealtimeState.PrecipitationMode mode) {
      return switch (mode) {
         case THUNDER -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.thunder", ChatFormatting.DARK_PURPLE);
         case SNOW -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.snow", ChatFormatting.AQUA);
         case RAIN -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.rain", ChatFormatting.BLUE);
         case CLEAR -> new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      };
   }

   private static TellusCommon.WeatherDisplay weatherFromVanilla(ServerLevel level, BlockPos pos, float temperatureC) {
      if (!level.isRaining()) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      }

      Biome biome = (Biome)level.getBiome(pos).value();
      if (!biome.hasPrecipitation()) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.clear", ChatFormatting.GREEN);
      }

      boolean snow = Float.isFinite(temperatureC)
         ? WeatherTemperaturePolicy.shouldSnow(temperatureC)
         : TellusMinecraftCompat.vanillaPrecipitationIsSnow(biome, pos, level);
      if (snow) {
         return new TellusCommon.WeatherDisplay("tellus.command.weather.value.snow", ChatFormatting.AQUA);
      }

      return level.isThundering()
         ? new TellusCommon.WeatherDisplay("tellus.command.weather.value.thunder", ChatFormatting.DARK_PURPLE)
         : new TellusCommon.WeatherDisplay("tellus.command.weather.value.rain", ChatFormatting.BLUE);
   }

   private static Component booleanLabel(boolean value) {
      return Component.translatable(value ? "options.on" : "options.off");
   }

   private static Component overrideLabel(Object value) {
      if (value == null) {
         return Component.translatable("tellus.value.not_set");
      }
      return value instanceof Boolean booleanValue ? booleanLabel(booleanValue) : Component.literal(value.toString());
   }

   private static ZoneId resolveZoneId(String zoneId) {
      if (zoneId != null && !zoneId.isBlank()) {
         try {
            return ZoneId.of(zoneId);
         } catch (Exception var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static int approximateUtcOffsetSeconds(double longitude) {
      double hours = longitude / 15.0;
      return (int)Math.round(hours * 3600.0);
   }

   private static String formatLocalTime(Instant instant, ZoneId zone) {
      int daySeconds = instant.atZone(zone).toLocalTime().toSecondOfDay();
      int hour = daySeconds / 3600;
      int minute = daySeconds % 3600 / 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static String formatGameTime(ServerLevel level) {
      long timeOfDay = TellusMinecraftCompat.dayTime(level);
      int totalMinutes = (int)Math.floor(timeOfDay * 60.0 / 1000.0);
      int hour = (totalMinutes / 60 + 6) % 24;
      int minute = totalMinutes % 60;
      return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
   }

   private static String formatUtcOffset(int offsetSeconds) {
      int totalMinutes = offsetSeconds / 60;
      int hours = totalMinutes / 60;
      int minutes = Math.abs(totalMinutes % 60);
      return String.format(Locale.ROOT, "UTC%+03d:%02d", hours, minutes);
   }

   public static void handleGeoTeleport(GeoTpTeleportPayload payload, ServerPlayer player) {
      if (Double.isFinite(payload.latitude()) && Double.isFinite(payload.longitude())) {
         MinecraftServer server = MinecraftVersionCompat.serverLevel(player).getServer();
         if (server == null) {
            return;
         }

         server.execute(() -> {
            if (!TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack())) {
               player.sendSystemMessage(Component.translatable("tellus.command.geotp.no_permission"));
               return;
            }

            ServerLevel level = MinecraftVersionCompat.serverLevel(player);
            if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
               double latitude = clampLatitude(payload.latitude());
               double longitude = clampLongitude(payload.longitude());
               // The surface lookup downloads terrain data, so it must not run on the server thread.
               CompletableFuture<BlockPos> lookup = MinecraftVersionCompat.supplyBackground(
                  "tellus_geotp", () -> earthGenerator.getSurfacePosition(level, latitude, longitude)
               );
               GEO_TELEPORT_LOOKUPS.put(player.getUUID(), lookup);
               Boolean override = teleportProgressOverride;
               if (override != null ? override : earthGenerator.settings().teleportProgress()) {
                  player.sendSystemMessage(Component.translatable("tellus.command.geotp.preparing").withStyle(ChatFormatting.GRAY));
                  showTeleportProgress(player);
               }

               lookup.whenCompleteAsync((target, error) -> {
                  // Only the player's latest request teleports, and only while they are still in that level.
                  if (!GEO_TELEPORT_LOOKUPS.remove(player.getUUID(), lookup)) {
                     return;
                  }

                  hideTeleportProgress(player);
                  if (player.isRemoved() || MinecraftVersionCompat.serverLevel(player) != level) {
                     return;
                  }

                  if (error != null) {
                     LOGGER.warn("Failed to find the teleport surface at lat={}, lon={}", latitude, longitude, error);
                     player.sendSystemMessage(Component.translatable("tellus.command.geotp.failed").withStyle(ChatFormatting.RED));
                  } else {
                     player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                  }
               }, server);
            } else {
               player.sendSystemMessage(Component.translatable("tellus.command.geotp.tellus_world_only"));
            }
         });
      }
   }

   private static void showTeleportProgress(ServerPlayer player) {
      ServerBossEvent bar = MinecraftVersionCompat.bossEvent(
         Component.translatable("tellus.command.geotp.progress"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10
      );
      bar.setProgress(0.0F);
      bar.addPlayer(player);
      TellusCommon.TeleportProgress previous = GEO_TELEPORT_PROGRESS.put(player.getUUID(), new TellusCommon.TeleportProgress(bar, System.nanoTime()));
      if (previous != null) {
         previous.bar().removeAllPlayers();
      }
   }

   private static void hideTeleportProgress(ServerPlayer player) {
      TellusCommon.TeleportProgress progress = GEO_TELEPORT_PROGRESS.remove(player.getUUID());
      if (progress != null) {
         progress.bar().removeAllPlayers();
      }
   }

   public static void handleManagedTerrainView(ManagedTerrainViewPayload payload, ServerPlayer player) {
      MANAGED_TERRAIN_DOWNLOAD_MANAGER.updateViewDistance(player, payload.renderRadiusChunks());
   }

   private static EarthChunkGenerator resolveEarthGenerator(CommandSourceStack source) {
      MinecraftServer server = source.getServer();
      ServerLevel level = server.getLevel(Level.OVERWORLD);
      if (level == null) {
         return null;
      } else {
         return level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator ? earthGenerator : null;
      }
   }

   private static double clampLatitude(double latitude) {
      return Mth.clamp(latitude, -85.05112878, 85.05112878);
   }

   private static double clampLongitude(double longitude) {
      return Mth.clamp(longitude, -180.0, 180.0);
   }

   private static void logOverworldSettings(MinecraftServer server, Level world, ChunkGenerator generator) {
      DimensionType worldType = world.dimensionType();
      LOGGER.info("Overworld dimension type: {}", describeDimensionType(worldType));
      LOGGER.info(
         "Overworld generator: type={}, minY={}, height={}", new Object[]{generator.getClass().getSimpleName(), generator.getMinY(), generator.getGenDepth()}
      );
      LevelStem stem = TellusMinecraftCompat.overworldStem(server);
      if (stem == null) {
         LOGGER.warn("Overworld level stem missing from registry");
      } else {
         DimensionType stemType = (DimensionType)stem.type().value();
         LOGGER.info("Overworld level stem: dimensionType={}, generatorType={}", describeDimensionType(stemType), stem.generator().getClass().getSimpleName());
      }
   }

   private static String describeDimensionType(DimensionType type) {
      return "minY=" + type.minY() + ",height=" + type.height() + ",logicalHeight=" + type.logicalHeight();
   }

   private static void ensureDynamicDimensionPack(
      MinecraftServer server, Holder<DimensionType> dimensionTypeHolder, DimensionType currentDimensionType, EarthChunkGenerator earthGenerator
   ) {
      ResourceKey<DimensionType> dimensionKey = resolveTellusDimensionKey(dimensionTypeHolder).orElse(null);
      if (dimensionKey != null) {
         EarthGeneratorSettings settings = earthGenerator.settings();
         EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
         TellusMinecraftCompat.validateDynamicHeight(settings, limits);
         DimensionType updatedType = EarthGeneratorSettings.applyHeightLimits(currentDimensionType, limits);
         DynamicOps<JsonElement> jsonOps = Objects.requireNonNull(JsonOps.INSTANCE, "jsonOps");
         RegistryOps<JsonElement> registryOps = RegistryOps.create(jsonOps, server.registryAccess());
         JsonElement dimensionJson = (JsonElement)DimensionType.DIRECT_CODEC
            .encodeStart(registryOps, updatedType)
            .resultOrPartial(message -> LOGGER.error("Failed to encode dynamic dimension type: {}", message))
            .orElse(null);
         if (dimensionJson != null) {
            Path packDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(DYNAMIC_DIMENSION_PACK_NAME);
            Path packMetaPath = packDir.resolve("pack.mcmeta");
            Path dimensionPath = packDir.resolve(
               "data/"
                  + TellusMinecraftCompat.dimensionNamespace(dimensionKey)
                  + "/dimension_type/"
                  + TellusMinecraftCompat.dimensionPath(dimensionKey)
                  + ".json"
            );

            try {
               Files.createDirectories(dimensionPath.getParent());
               writeJson(packMetaPath, createPackMeta());
               writeJson(dimensionPath, dimensionJson);
            } catch (IOException var16) {
               LOGGER.warn("Failed to persist dynamic dimension type pack", var16);
               return;
            }

            enableDynamicPack(server.getWorldData());
         }
      }
   }

   private static Optional<ResourceKey<DimensionType>> resolveTellusDimensionKey(Holder<DimensionType> dimensionTypeHolder) {
      return dimensionTypeHolder.unwrapKey().filter(TellusCommon::isTellusDimensionKey);
   }

   private static boolean isTellusDimensionKey(ResourceKey<DimensionType> key) {
      return key.equals(DYNAMIC_DIMENSION_KEY) || key.equals(EARTH_DIMENSION_KEY);
   }

   private static JsonObject createPackMeta() {
      JsonObject pack = new JsonObject();
      TellusMinecraftCompat.writePackFormat(pack);
      pack.addProperty("description", "Tellus dynamic dimension settings");
      JsonObject root = new JsonObject();
      root.add("pack", pack);
      return root;
   }

   private static void writeJson(Path path, JsonElement payload) throws IOException {
      try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
         GSON.toJson(payload, writer);
      }
   }

   private static void enableDynamicPack(WorldData worldData) {
      WorldDataConfiguration configuration = worldData.getDataConfiguration();
      DataPackConfig dataPacks = configuration.dataPacks();
      List<String> enabled = new ArrayList<>(dataPacks.getEnabled());
      List<String> disabled = new ArrayList<>(dataPacks.getDisabled());
      if (!enabled.contains(DYNAMIC_DIMENSION_PACK_ID)) {
         enabled.add(DYNAMIC_DIMENSION_PACK_ID);
      }

      disabled.remove(DYNAMIC_DIMENSION_PACK_ID);
      if (!enabled.equals(dataPacks.getEnabled()) || !disabled.equals(dataPacks.getDisabled())) {
         WorldDataConfiguration updated = new WorldDataConfiguration(new DataPackConfig(enabled, disabled), configuration.enabledFeatures());
         worldData.setDataConfiguration(updated);
      }
   }

   private record WeatherDisplay(String translationKey, ChatFormatting color) {
   }

   private record TeleportProgress(ServerBossEvent bar, long startNanos) {
      // shortcut: the surface lookup reports no progress, so the bar eases toward full over time (half at 4 s); upgrade if downloads report their progress.
      void update() {
         double seconds = (System.nanoTime() - this.startNanos) / 1.0E9;
         this.bar.setProgress((float)(seconds / (seconds + 4.0)));
      }
   }
}
