package com.yucareux.tellus.platform;

import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainStatusPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Fabric payload registry bridge for Minecraft 1.21.11. */
public final class FabricNetworkingVersionCompat {
   private FabricNetworkingVersionCompat() {
   }

   public static void registerPayloadTypes() {
      PayloadTypeRegistry.playC2S().register(
         GeoTpTeleportPayload.TYPE,
         Objects.requireNonNull(GeoTpTeleportPayload.CODEC.cast(), "geoTpTeleportCodec")
      );
      PayloadTypeRegistry.playC2S().register(
         ManagedTerrainViewPayload.TYPE,
         Objects.requireNonNull(ManagedTerrainViewPayload.CODEC.cast(), "managedTerrainViewCodec")
      );
      PayloadTypeRegistry.playS2C().register(
         GeoTpOpenMapPayload.TYPE,
         Objects.requireNonNull(GeoTpOpenMapPayload.CODEC.cast(), "geoTpOpenMapCodec")
      );
      PayloadTypeRegistry.playS2C().register(
         TellusWeatherPayload.TYPE,
         Objects.requireNonNull(TellusWeatherPayload.CODEC.cast(), "tellusWeatherCodec")
      );
      PayloadTypeRegistry.playS2C().register(
         ManagedTerrainStatusPayload.TYPE,
         Objects.requireNonNull(ManagedTerrainStatusPayload.CODEC.cast(), "managedTerrainStatusCodec")
      );
   }
}
