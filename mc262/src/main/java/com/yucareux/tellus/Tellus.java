package com.yucareux.tellus;

import com.yucareux.tellus.compat.MinecraftRelease;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.network.ManagedTerrainViewPayload;
import com.yucareux.tellus.platform.FabricNetworkingVersionCompat;
import com.yucareux.tellus.platform.FabricTellusRuntimePlatform;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** Fabric entrypoint; loader-independent server behavior lives in {@link TellusCommon}. */
public final class Tellus extends TellusCommon implements ModInitializer {
   public static Identifier id(String path) {
      return MinecraftRelease.resourceLocation("tellus", path);
   }

   @Override
   public void onInitialize() {
      TellusCommon.validateRuntime();
      Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);
      Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
      FabricNetworkingVersionCompat.registerPayloadTypes();
      ServerPlayNetworking.registerGlobalReceiver(
         GeoTpTeleportPayload.TYPE, (payload, context) -> TellusCommon.handleGeoTeleport(payload, context.player())
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ManagedTerrainViewPayload.TYPE, (payload, context) -> TellusCommon.handleManagedTerrainView(payload, context.player())
      );
      TellusCommon.initializeRuntime(new FabricTellusRuntimePlatform());
   }
}
