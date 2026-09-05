package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Fabric payload registry names introduced after Minecraft 1.21.11. */
public final class FabricNetworkingVersionCompat {
   private FabricNetworkingVersionCompat() {
   }

   public static void registerPayloadTypes() {
      PayloadTypeRegistry.serverboundPlay().register(GeoTpTeleportPayload.TYPE, GeoTpTeleportPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(ManagedTerrainViewPayload.TYPE, ManagedTerrainViewPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(GeoTpOpenMapPayload.TYPE, GeoTpOpenMapPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(TellusWeatherPayload.TYPE, TellusWeatherPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(ManagedTerrainStatusPayload.TYPE, ManagedTerrainStatusPayload.CODEC);
   }
}
