package com.yucareux.tellus.client;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public final class LoadingTerrainScreenTiming {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellus.loadingTerrainTiming", "false"));
   private static final long ACTIVE_LOG_INTERVAL_NANOS = 5000000000L;
   private static final Map<Integer, LoadingTerrainScreenTiming.Session> SESSIONS = new HashMap<>();

   private LoadingTerrainScreenTiming() {
   }

   public static void onScreenRender(LevelLoadingScreen screen, String reason) {
      if (ENABLED) {
         int key = System.identityHashCode(screen);
         long now = System.nanoTime();
         LoadingTerrainScreenTiming.Session session = SESSIONS.get(key);
         if (session == null) {
            session = new LoadingTerrainScreenTiming.Session(reason, now);
            SESSIONS.put(key, session);
            LOGGER.info("Loading terrain screen shown reason={} elapsed={}", reason, formatMillis(0L));
         } else if (now - session.lastLogNanos >= ACTIVE_LOG_INTERVAL_NANOS) {
            session.lastLogNanos = now;
            LOGGER.info("Loading terrain screen active reason={} elapsed={}", session.reason, formatMillis(now - session.startNanos));
         }
      }
   }

   public static void onScreenChange(Screen previousScreen, Screen nextScreen) {
      if (ENABLED && previousScreen instanceof LevelLoadingScreen previousLoading && previousScreen != nextScreen) {
         LoadingTerrainScreenTiming.Session session = SESSIONS.remove(System.identityHashCode(previousLoading));
         if (session != null) {
            long elapsed = System.nanoTime() - session.startNanos;
            LOGGER.info(
               "Loading terrain screen finished reason={} elapsed={} nextScreen={}",
               session.reason,
               formatMillis(elapsed),
               describeScreen(nextScreen)
            );
         }
      }
   }

   private static String describeScreen(Screen screen) {
      return screen == null ? "null" : screen.getClass().getSimpleName();
   }

   private static String formatMillis(long nanos) {
      return String.format(Locale.ROOT, "%.3fms", (double)nanos / 1000000.0);
   }

   private static final class Session {
      private final String reason;
      private final long startNanos;
      private long lastLogNanos;

      private Session(String reason, long startNanos) {
         this.reason = reason;
         this.startNanos = startNanos;
         this.lastLogNanos = startNanos;
      }
   }
}
