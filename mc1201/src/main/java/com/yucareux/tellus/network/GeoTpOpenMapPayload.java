package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

public record GeoTpOpenMapPayload(double latitude, double longitude, double spawnLatitude, double spawnLongitude) implements FabricPacket {
   public static final PacketType<GeoTpOpenMapPayload> TYPE = PacketType.create(Tellus.id("geotp_open_map"), GeoTpOpenMapPayload::new);

   public GeoTpOpenMapPayload(FriendlyByteBuf buffer) {
      this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
   }

   public GeoTpOpenMapPayload(double latitude, double longitude) {
      this(latitude, longitude, latitude, longitude);
   }

   public GeoTpOpenMapPayload(double latitude, double longitude, double spawnLatitude, double spawnLongitude) {
      this.latitude = latitude;
      this.longitude = longitude;
      this.spawnLatitude = spawnLatitude;
      this.spawnLongitude = spawnLongitude;
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeDouble(this.latitude());
      buffer.writeDouble(this.longitude());
      buffer.writeDouble(this.spawnLatitude());
      buffer.writeDouble(this.spawnLongitude());
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
