package com.yucareux.tellus.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;

public final class TellusNeoForgeClientNetworking {
   private TellusNeoForgeClientNetworking() {
   }

   // 1.21.1 registers its client handlers with the payloads in TellusNeoForgeNetworking.
   public static void registerClientPayloadHandlers(IEventBus modEventBus) {
   }

   public static void sendToServer(CustomPacketPayload payload) {
      PacketDistributor.sendToServer(payload);
   }
}
