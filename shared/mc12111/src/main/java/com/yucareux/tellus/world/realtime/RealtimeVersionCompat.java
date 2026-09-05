package com.yucareux.tellus.world.realtime;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/** Minecraft 1.21.11 time, weather, and game-rule bridge. */
final class RealtimeVersionCompat {
   private RealtimeVersionCompat() {
   }

   static long dayTimeTicks(ServerLevel level) {
      return level.getDayTime();
   }

   static void setDayTimeTicks(ServerLevel level, long ticks) {
      level.setDayTime(ticks);
   }

   static void applyWeather(ServerLevel level, boolean raining, boolean thundering) {
      level.setWeatherParameters(0, 6000, raining, thundering);
   }

   static Boolean daylightCycle(ServerLevel level) {
      return level.getGameRules().get(GameRules.ADVANCE_TIME);
   }

   static Integer sleepingPercentage(ServerLevel level) {
      return level.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
   }

   static Boolean weatherCycle(ServerLevel level) {
      return level.getGameRules().get(GameRules.ADVANCE_WEATHER);
   }

   static void setDaylightCycle(ServerLevel level, MinecraftServer server, boolean value) {
      level.getGameRules().set(GameRules.ADVANCE_TIME, value, server);
   }

   static void setSleepingPercentage(ServerLevel level, MinecraftServer server, int value) {
      level.getGameRules().set(GameRules.PLAYERS_SLEEPING_PERCENTAGE, value, server);
   }

   static void setWeatherCycle(ServerLevel level, MinecraftServer server, boolean value) {
      level.getGameRules().set(GameRules.ADVANCE_WEATHER, value, server);
   }
}
