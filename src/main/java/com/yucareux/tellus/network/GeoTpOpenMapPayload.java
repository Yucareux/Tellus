package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GeoTpOpenMapPayload(double latitude, double longitude, double spawnLatitude, double spawnLongitude) implements CustomPacketPayload {
   
   public static final CustomPacketPayload.Type<GeoTpOpenMapPayload> TYPE = new CustomPacketPayload.Type<>(Tellus.id("geotp_open_map"));
   public static final StreamCodec<FriendlyByteBuf, GeoTpOpenMapPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::latitude,
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::longitude,
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::spawnLatitude,
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::spawnLongitude,
      GeoTpOpenMapPayload::fromBoxed
   );

   
   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   private static GeoTpOpenMapPayload fromBoxed(Double latitude, Double longitude, Double spawnLatitude, Double spawnLongitude) {
      return new GeoTpOpenMapPayload(
         Objects.requireNonNull(latitude, "latitude"),
         Objects.requireNonNull(longitude, "longitude"),
         Objects.requireNonNull(spawnLatitude, "spawnLatitude"),
         Objects.requireNonNull(spawnLongitude, "spawnLongitude")
      );
   }
}
