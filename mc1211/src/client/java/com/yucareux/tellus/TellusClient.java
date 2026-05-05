package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class TellusClient implements ClientModInitializer {
   private static KeyMapping openEarthMapKey;

   @Override
   public void onInitializeClient() {
      openEarthMapKey = KeyBindingHelper.registerKeyBinding(
         new KeyMapping("key.tellus.open_earth_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.tellus.keybinds")
      );
      ClientTickEvents.END_CLIENT_TICK.register(TellusClient::handleClientTick);
      ClientPlayNetworking.registerGlobalReceiver(Objects.requireNonNull(GeoTpOpenMapPayload.TYPE, "GeoTpOpenMapPayload.TYPE"), (payload, context) -> context.client().execute(() -> {
         Minecraft minecraft = context.client();
         Screen parent = minecraft.screen;
         minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude(), payload.spawnLatitude(), payload.spawnLongitude()));
      }));
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusWeatherPayload.TYPE, "TellusWeatherPayload.TYPE"),
         (payload, context) -> context.client()
            .execute(
               () -> {
                  SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
                     ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
                     : SnowGrid.empty();
                  TellusRealtimeState.updateWeatherState(payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid);
               }
            )
      );
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TellusRealtimeState.clearRealtimeWeather());
   }

   private static void handleClientTick(Minecraft client) {
      while (openEarthMapKey != null && openEarthMapKey.consumeClick()) {
         if (client.screen == null && client.player != null && client.player.connection != null) {
            client.player.connection.sendCommand("tellus map");
         }
      }
   }
}
