package com.yucareux.tellus.network;

import com.yucareux.tellus.TellusClient;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Minecraft 1.21.11 client-packet namespace bridge. */
public final class TellusNeoForgeClientNetworking {
   private TellusNeoForgeClientNetworking() {
   }

   // The payloads come from the 26.2 networking, which leaves their client handlers to this event;
   // NeoForge refuses to start when a clientbound payload has none.
   public static void registerClientPayloadHandlers(IEventBus modEventBus) {
      modEventBus.addListener(TellusNeoForgeClientNetworking::registerHandlers);
   }

   private static void registerHandlers(RegisterClientPayloadHandlersEvent event) {
      event.register(GeoTpOpenMapPayload.TYPE, TellusClient::handleOpenMapPayload);
      event.register(TellusWeatherPayload.TYPE, TellusClient::handleWeatherPayload);
      event.register(ManagedTerrainStatusPayload.TYPE, TellusClient::handleManagedTerrainStatusPayload);
   }

   public static void sendToServer(CustomPacketPayload payload) {
      ClientPacketDistributor.sendToServer(payload);
   }
}
