package com.yucareux.tellus;

import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainClientState;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainViewDistance;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusNeoForgeClientNetworking;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.platform.TellusClientPlatform;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TemperatureGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lwjgl.glfw.GLFW;

public final class TellusClient {
   private static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
      "key.tellus.open_map",
      InputConstants.Type.KEYSYM,
      GLFW.GLFW_KEY_M,
      ClientMinecraftCompat.keyCategory("key.category.tellus.controls")
   );
   private static int managedTerrainViewUpdateTicks;

   private TellusClient() {
   }

   public static void register(IEventBus modEventBus) {
      TellusClientPlatform.configureGeoTeleport(
         () -> true,
         (latitude, longitude) -> TellusNeoForgeClientNetworking.sendToServer(new GeoTpTeleportPayload(latitude, longitude))
      );
      modEventBus.addListener(TellusClient::registerGuiLayers);
      modEventBus.addListener(TellusClient::registerKeyMappings);
      NeoForge.EVENT_BUS.addListener(TellusClient::onClientDisconnect);
      NeoForge.EVENT_BUS.addListener(TellusClient::onClientTick);
   }

   private static void registerKeyMappings(RegisterKeyMappingsEvent event) {
      event.register(OPEN_MAP_KEY);
   }

   private static void registerGuiLayers(RegisterGuiLayersEvent event) {
      event.registerAboveAll(Tellus.id("managed_terrain_status"), (graphics, deltaTracker) -> ManagedTerrainDownloadOverlay.render(graphics));
   }

   public static void handleOpenMapPayload(GeoTpOpenMapPayload payload, IPayloadContext context) {
      Minecraft.getInstance().execute(() -> {
         Minecraft minecraft = Minecraft.getInstance();
         Screen parent = minecraft.screen;
         minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
      });
   }

   public static void handleWeatherPayload(TellusWeatherPayload payload, IPayloadContext context) {
      Minecraft.getInstance().execute(() -> {
         SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
            ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
            : SnowGrid.empty();
         TemperatureGrid temperatureGrid = payload.spacingBlocks() > 0
            ? new TemperatureGrid(
               payload.centerX(),
               payload.centerZ(),
               payload.spacingBlocks(),
               payload.temperatureC(),
               System.currentTimeMillis() - Math.max(0L, payload.temperatureAgeMs())
            )
            : TemperatureGrid.empty();
         TellusRealtimeState.updateWeatherState(
            payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid, temperatureGrid
         );
      });
   }

   public static void handleManagedTerrainStatusPayload(ManagedTerrainStatusPayload payload, IPayloadContext context) {
      Minecraft.getInstance().execute(() -> ManagedTerrainClientState.update(payload.status()));
   }

   private static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
      TellusRealtimeState.reset();
      ManagedTerrainClientState.reset();
      managedTerrainViewUpdateTicks = 0;
   }

   private static void onClientTick(ClientTickEvent.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      while (OPEN_MAP_KEY.consumeClick()) {
         if (minecraft.screen == null && minecraft.player != null && minecraft.getConnection() != null) {
            minecraft.getConnection().sendCommand("tellus map");
         }
      }

      if (minecraft.player != null && ++managedTerrainViewUpdateTicks >= 40) {
         managedTerrainViewUpdateTicks = 0;
         TellusNeoForgeClientNetworking.sendToServer(new ManagedTerrainViewPayload(ManagedTerrainViewDistance.detect()));
      }
   }
}
