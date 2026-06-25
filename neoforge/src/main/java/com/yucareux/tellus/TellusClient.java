package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@OnlyIn(Dist.CLIENT)
public class TellusClient {

   static void init(IEventBus modEventBus) {
      NeoForge.EVENT_BUS.addListener(TellusClient::onPlayerLogout);
   }

   private static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
      TellusRealtimeState.clearRealtimeWeather();
   }

   static void handleOpenMapPayload(GeoTpOpenMapPayload payload) {
      Minecraft minecraft = Minecraft.getInstance();
      Screen parent = minecraft.screen;
      minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
   }

   static void handleWeatherPayload(TellusWeatherPayload payload) {
      SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
         ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
         : SnowGrid.empty();
      TellusRealtimeState.updateWeatherState(payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid);
   }
}
