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
import com.yucareux.tellus.integration.voxy.TellusVoxyPregenManager;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.TellusRealtimeManager;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
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
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.Permission.HasCommandLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Tellus implements ModInitializer {
   
   public static final String MOD_ID = "tellus";
   private static final String DYNAMIC_DIMENSION_PACK_NAME = "tellus_dynamic_dimension";
   private static final String DYNAMIC_DIMENSION_PACK_ID = "file/tellus_dynamic_dimension";
   
   private static final Identifier EARTH_DIMENSION_ID = Objects.requireNonNull(Identifier.fromNamespaceAndPath("tellus", "earth"), "earthDimensionId");
   
   private static final Identifier DYNAMIC_DIMENSION_ID = Objects.requireNonNull(
      Identifier.fromNamespaceAndPath("tellus", "earth_dynamic"), "dynamicDimensionId"
   );
   
   private static final ResourceKey<DimensionType> EARTH_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, EARTH_DIMENSION_ID), "earthDimensionKey"
   );
   
   private static final ResourceKey<DimensionType> DYNAMIC_DIMENSION_KEY = Objects.requireNonNull(
      ResourceKey.create(Registries.DIMENSION_TYPE, DYNAMIC_DIMENSION_ID), "dynamicDimensionKey"
   );
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final TellusRealtimeManager REALTIME_MANAGER = new TellusRealtimeManager();
   private static final TellusVoxyPregenManager VOXY_PREGEN_MANAGER = new TellusVoxyPregenManager();
   public static final Logger LOGGER = LoggerFactory.getLogger("tellus");

   
   public static Identifier id( String path) {
      return Objects.requireNonNull(Identifier.fromNamespaceAndPath("tellus", path), "identifier");
   }

   public void onInitialize() {
      Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
      Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(GeoTpTeleportPayload.TYPE, GeoTpTeleportPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC);
      ServerPlayNetworking.registerGlobalReceiver(GeoTpTeleportPayload.TYPE, Tellus::handleGeoTeleport);
      CommandRegistrationCallback.EVENT
         .register(
            (dispatcher, registryAccess, environment) -> dispatcher.register(
               ((Commands.literal("tellus")
                        .then(
                           (Commands.literal("map")
                                 .requires(source -> source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS))))
                              .executes(context -> openGeoTpMap((CommandSourceStack)context.getSource()))
                        ))
                     .then(
                        (Commands.literal("weather")
                              .executes(context -> showTellusWeather((CommandSourceStack)context.getSource())))
                           .then(
                              Commands.literal("enable_realtime_time")
                                 .requires(source -> source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS)))
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
                                 .requires(source -> source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS)))
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
                              .requires(source -> source.permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS))))
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
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> server.execute(() -> {
         ServerLevel world = server.getLevel(Level.OVERWORLD);
         if (world != null) {
            ChunkGenerator generator = world.getChunkSource().getGenerator();
            logOverworldSettings(server, world, generator);
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               BlockPos spawn = Objects.requireNonNull(earthGenerator.getSpawnPosition(world), "spawnPosition");
               world.setRespawnData(RespawnData.of(world.dimension(), spawn, 0.0F, 0.0F));
               ensureDynamicDimensionPack(server, world.dimensionTypeRegistration(), world.dimensionType(), earthGenerator);
            }
         }
      }));
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         REALTIME_MANAGER.onServerStopping(server);
         VOXY_PREGEN_MANAGER.shutdown();
      });
      ServerTickEvents.END_SERVER_TICK.register(REALTIME_MANAGER::onServerTick);
      ServerTickEvents.END_SERVER_TICK.register(VOXY_PREGEN_MANAGER::onServerTick);
      ServerTickEvents.END_SERVER_TICK.register(server -> {
         for (ServerLevel level : server.getAllLevels()) {
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (generator instanceof EarthChunkGenerator earthGenerator) {
               earthGenerator.processDeferredChunkDetailTick(level);
            }
         }
      });
      ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
         ChunkGenerator generator = level.getChunkSource().getGenerator();
         if (generator instanceof EarthChunkGenerator earthGenerator) {
            earthGenerator.discardPreparedChunkState(chunk.getPos());
         }
      });
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, server) -> REALTIME_MANAGER.onPlayerJoin(server, handler.getPlayer()));
      if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
         DistantHorizonsIntegration.bootstrap();
      }

      LOGGER.info("Tellus worldgen initialized");
   }

   private static int openGeoTpMap(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.literal("Tellus: GeoTP map can only be used by a player."));
         return 0;
      } else {
         ServerLevel level = player.level();
         if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
            double latitude = clampLatitude(earthGenerator.latitudeFromBlock(player.getZ()));
            double longitude = clampLongitude(earthGenerator.longitudeFromBlock(player.getX()));
            EarthGeneratorSettings settings = earthGenerator.settings();
            double spawnLatitude = clampLatitude(settings.spawnLatitude());
            double spawnLongitude = clampLongitude(settings.spawnLongitude());
            ServerPlayNetworking.send(player, new GeoTpOpenMapPayload(latitude, longitude, spawnLatitude, spawnLongitude));
            return 1;
         } else {
            source.sendFailure(Component.literal("Tellus: GeoTP map is only available in Tellus worlds."));
            return 0;
         }
      }
   }

   private static int showTellusWeather(CommandSourceStack source) {
      ServerPlayer player = source.getPlayer();
      if (player == null) {
         source.sendFailure(Component.literal("Tellus: /tellus weather can only be used by a player."));
         return 0;
      } else {
         ServerLevel level = player.level();
         if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
            source.sendFailure(Component.literal("Tellus: /tellus weather is only available in Tellus worlds."));
            return 0;
         } else {
            BlockPos pos = player.blockPosition();
            double latitude = clampLatitude(earthGenerator.latitudeFromBlock(pos.getZ()));
            double longitude = clampLongitude(earthGenerator.longitudeFromBlock(pos.getX()));
            EarthGeneratorSettings settings = earthGenerator.settings();
            boolean realtimeTime = REALTIME_MANAGER.isRealtimeTimeEnabled(settings);
            boolean realtimeWeather = REALTIME_MANAGER.isRealtimeWeatherEnabled(settings);
            boolean realtimeWeatherActive = realtimeWeather && TellusRealtimeState.isWeatherEnabled();
            TellusRealtimeState.PrecipitationMode mode = TellusRealtimeState.precipitationMode();
            Tellus.WeatherDisplay weather = realtimeWeatherActive ? weatherFromRealtime(mode) : weatherFromVanilla(level, pos);
            source.sendSuccess(() -> Component.literal("Tellus Weather").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
            String locationText = Objects.requireNonNull(String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude), "locationText");
            source.sendSuccess(
               () -> Component.literal("Location: ").withStyle(ChatFormatting.GRAY).append(Component.literal(locationText).withStyle(ChatFormatting.AQUA)),
               false
            );
            String gameTime = Objects.requireNonNull(formatGameTime(level), "gameTime");
            source.sendSuccess(
               () -> Component.literal("Game time: ").withStyle(ChatFormatting.GRAY).append(Component.literal(gameTime).withStyle(ChatFormatting.YELLOW)),
               false
            );
            TellusRealtimeManager.WeatherSnapshot snapshot = REALTIME_MANAGER.lastWeatherSnapshot();
            ZoneId zone = null;
            boolean approximateTime = true;
            if (snapshot != null) {
               zone = resolveZoneId(snapshot.timeZoneId());
               approximateTime = zone == null;
            }

            if (zone == null && realtimeTime && REALTIME_MANAGER.hasTimeOffset()) {
               ZoneId managerZone = REALTIME_MANAGER.currentTimeZone();
               if (managerZone != null) {
                  zone = managerZone;
                  approximateTime = false;
               }
            }

            if (zone == null) {
               int offsetSeconds = approximateUtcOffsetSeconds(longitude);
               zone = ZoneOffset.ofTotalSeconds(offsetSeconds);
               approximateTime = true;
            }

            Instant now = REALTIME_MANAGER.currentInstant();
            int offsetSeconds = zone.getRules().getOffset(now).getTotalSeconds();
            String timeLabel = formatLocalTime(now, zone);
            String utcOffsetLabel = formatUtcOffset(offsetSeconds);
            MutableComponent timeLine = Component.literal("Real time: ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(timeLabel + " " + utcOffsetLabel).withStyle(ChatFormatting.YELLOW));
            if (approximateTime) {
               timeLine.append(Component.literal(" (approx)").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> timeLine, false);
            MutableComponent tempLine = Component.literal("Temperature: ").withStyle(ChatFormatting.GRAY);
            if (snapshot != null) {
               String tempLabel = Objects.requireNonNull(String.format(Locale.ROOT, "%.1f C", snapshot.temperatureC()), "tempLabel");
               tempLine.append(Component.literal(tempLabel).withStyle(ChatFormatting.YELLOW));
            } else {
               tempLine.append(Component.literal("n/a").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> tempLine, false);
            String weatherLabel = Objects.requireNonNull(weather.label(), "weatherLabel");
            ChatFormatting weatherColor = Objects.requireNonNull(weather.color(), "weatherColor");
            MutableComponent weatherLine = Component.literal("Weather: ")
               .withStyle(ChatFormatting.GRAY)
               .append(Component.literal(weatherLabel).withStyle(weatherColor));
            if (!realtimeWeather) {
               weatherLine.append(Component.literal(" (vanilla)").withStyle(ChatFormatting.DARK_GRAY));
            } else if (!realtimeWeatherActive) {
               weatherLine.append(Component.literal(" (real-time pending)").withStyle(ChatFormatting.DARK_GRAY));
            }

            source.sendSuccess(() -> weatherLine, false);
            return 1;
         }
      }
   }

   private static int setRealtimeTimeOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config weather is only available in Tellus worlds."));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeTimeOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: real-time time set to " + enabled + "."), false);
         return 1;
      }
   }

   private static int setRealtimeWeatherOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config weather is only available in Tellus worlds."));
         return 0;
      } else {
         REALTIME_MANAGER.setRealtimeWeatherOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: real-time weather set to " + enabled + "."), false);
         return 1;
      }
   }

   private static int setVoxyPregenEnabledOverride(CommandSourceStack source, boolean enabled) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy is only available in Tellus worlds."));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setEnabledOverride(enabled);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy pregen enabled override set to " + enabled + "."), false);
         return 1;
      }
   }

   private static int setVoxyPregenMaxRadiusOverride(CommandSourceStack source, int chunks) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy is only available in Tellus worlds."));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setMaxRadiusOverride(chunks);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy pregen max radius override set to " + chunks + " chunks."), false);
         return 1;
      }
   }

   private static int setVoxyPregenChunksPerTickOverride(CommandSourceStack source, int chunksPerTick) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy is only available in Tellus worlds."));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.setChunksPerTickOverride(chunksPerTick);
         source.sendSuccess(() -> Component.literal("Tellus: Voxy pregen budget override set to " + chunksPerTick + " chunks/tick."), false);
         return 1;
      }
   }

   private static int resetVoxyPregenOverrides(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy is only available in Tellus worlds."));
         return 0;
      } else {
         VOXY_PREGEN_MANAGER.clearOverrides();
         source.sendSuccess(() -> Component.literal("Tellus: Voxy pregen overrides reset to world settings."), false);
         return 1;
      }
   }

   private static int showVoxyPregenStatus(CommandSourceStack source) {
      EarthChunkGenerator earthGenerator = resolveEarthGenerator(source);
      if (earthGenerator == null) {
         source.sendFailure(Component.literal("Tellus: /tellus config voxy is only available in Tellus worlds."));
         return 0;
      } else {
         EarthGeneratorSettings settings = earthGenerator.settings();
         boolean enabled = VOXY_PREGEN_MANAGER.effectiveEnabled(settings);
         int maxRadius = VOXY_PREGEN_MANAGER.effectiveMaxRadius(settings);
         int chunksPerTick = VOXY_PREGEN_MANAGER.effectiveChunksPerTick(settings);
         source.sendSuccess(() -> Component.literal("Tellus Voxy pregen").withStyle(new ChatFormatting[]{ChatFormatting.GOLD, ChatFormatting.BOLD}), false);
         source.sendSuccess(
            () -> Component.literal(
                  "Enabled: " + enabled + " (world=" + settings.voxyChunkPregenEnabled() + ", override=" + VOXY_PREGEN_MANAGER.enabledOverride() + ")"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "Max radius: "
                     + maxRadius
                     + " chunks (world="
                     + settings.voxyChunkPregenMaxRadius()
                     + ", override="
                     + VOXY_PREGEN_MANAGER.maxRadiusOverride()
                     + ")"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "Budget: "
                     + chunksPerTick
                     + " chunks/tick (world="
                     + settings.voxyChunkPregenChunksPerTick()
                     + ", override="
                     + VOXY_PREGEN_MANAGER.chunksPerTickOverride()
                     + ")"
               )
               .withStyle(ChatFormatting.GRAY),
            false
         );
         source.sendSuccess(
            () -> Component.literal(
                  "Voxy radius: configured="
                     + VOXY_PREGEN_MANAGER.lastConfiguredVoxyRadiusChunks()
                     + " chunks, effective="
                     + VOXY_PREGEN_MANAGER.lastEffectiveRadiusChunks()
                     + " chunks"
               )
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         source.sendSuccess(
            () -> Component.literal("Queue: queued=" + VOXY_PREGEN_MANAGER.queuedChunkCount() + ", in-flight=" + VOXY_PREGEN_MANAGER.inFlightChunkCount())
               .withStyle(ChatFormatting.DARK_AQUA),
            false
         );
         return 1;
      }
   }

   private static Tellus.WeatherDisplay weatherFromRealtime(TellusRealtimeState.PrecipitationMode mode) {
      return switch (mode) {
         case THUNDER -> new Tellus.WeatherDisplay("Thunder", ChatFormatting.DARK_PURPLE);
         case SNOW -> new Tellus.WeatherDisplay("Snow", ChatFormatting.AQUA);
         case RAIN -> new Tellus.WeatherDisplay("Rain", ChatFormatting.BLUE);
         case CLEAR -> new Tellus.WeatherDisplay("Clear", ChatFormatting.GREEN);
      };
   }

   private static Tellus.WeatherDisplay weatherFromVanilla(ServerLevel level,  BlockPos pos) {
      if (level.isThundering()) {
         return new Tellus.WeatherDisplay("Thunder", ChatFormatting.DARK_PURPLE);
      } else if (level.isRainingAt(pos)) {
         Biome biome = (Biome)level.getBiome(pos).value();
         boolean snow = biome.getPrecipitationAt(pos, level.getSeaLevel()) == Precipitation.SNOW;
         return new Tellus.WeatherDisplay(snow ? "Snow" : "Rain", snow ? ChatFormatting.AQUA : ChatFormatting.BLUE);
      } else {
         return new Tellus.WeatherDisplay("Clear", ChatFormatting.GREEN);
      }
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

   private static long resolveDayTime(ServerLevel level) {
      Optional<Holder<WorldClock>> defaultClock = level.dimensionTypeRegistration().value().defaultClock();
      return Math.floorMod(defaultClock.map(clock -> level.clockManager().getTotalTicks(clock)).orElse(level.getGameTime()), 24000L);
   }

   private static String formatGameTime(ServerLevel level) {
      long timeOfDay = resolveDayTime(level);
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

   private static void handleGeoTeleport(GeoTpTeleportPayload payload, Context context) {
      if (Double.isFinite(payload.latitude()) && Double.isFinite(payload.longitude())) {
         ServerPlayer player = context.player();
         MinecraftServer server = player.level().getServer();
         if (server == null) {
            return;
         }

         server.execute(() -> {
            if (!player.createCommandSourceStack().permissions().hasPermission(new HasCommandLevel(PermissionLevel.GAMEMASTERS))) {
               player.sendSystemMessage(Component.literal("Tellus: You do not have permission to use GeoTP."));
               return;
            }

            ServerLevel level = player.level();
            if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator) {
               double latitude = clampLatitude(payload.latitude());
               double longitude = clampLongitude(payload.longitude());
               BlockPos target = earthGenerator.getSurfacePosition(level, latitude, longitude);
               player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            } else {
               player.sendSystemMessage(Component.literal("Tellus: GeoTP is only available in Tellus worlds."));
            }
         });
      }
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
      Registry<LevelStem> stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
      Optional<Reference<LevelStem>> stemRef = stems.get(LevelStem.OVERWORLD);
      if (stemRef.isEmpty()) {
         LOGGER.warn("Overworld level stem missing from registry");
      } else {
         LevelStem stem = (LevelStem)stemRef.get().value();
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
            Identifier dimensionId = dimensionKey.identifier();
            Path dimensionPath = packDir.resolve("data/" + dimensionId.getNamespace() + "/dimension_type/" + dimensionId.getPath() + ".json");

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
      return dimensionTypeHolder.unwrapKey().filter(Tellus::isTellusDimensionKey);
   }

   private static boolean isTellusDimensionKey(ResourceKey<DimensionType> key) {
      return key.equals(DYNAMIC_DIMENSION_KEY) || key.equals(EARTH_DIMENSION_KEY);
   }

   private static JsonObject createPackMeta() {
      JsonObject pack = new JsonObject();
      int packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA).major();
      pack.addProperty("pack_format", packFormat);
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

   private record WeatherDisplay(String label, ChatFormatting color) {
   }
}
