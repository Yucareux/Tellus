package com.yucareux.tellus.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;

public final class HmaAccessConfig {
   private static final String TOKEN_KEY = "earthdata_bearer_token";
   private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("tellus-hma-access.properties");
   private static final Object LOCK = new Object();
   private static volatile String bearerToken = loadToken();

   private HmaAccessConfig() {
   }

   public static String bearerToken() {
      return bearerToken;
   }

   public static boolean hasBearerToken() {
      return !bearerToken().isBlank();
   }

   public static Path configPath() {
      return CONFIG_PATH;
   }

   public static void setBearerToken(String token) {
      String normalized = normalize(token);
      synchronized (LOCK) {
         bearerToken = normalized;
         try {
            saveLocked(normalized);
         } catch (IOException error) {
            throw new IllegalStateException("Failed to save HMA access config", error);
         }
      }
   }

   public static void clearBearerToken() {
      setBearerToken("");
   }

   private static String loadToken() {
      synchronized (LOCK) {
         if (!Files.exists(CONFIG_PATH)) {
            return "";
         } else {
            Properties properties = new Properties();

            try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
               properties.load(input);
               return normalize(properties.getProperty(TOKEN_KEY, ""));
            } catch (IOException error) {
               return "";
            }
         }
      }
   }

   private static void saveLocked(String token) throws IOException {
      if (token.isBlank()) {
         Files.deleteIfExists(CONFIG_PATH);
      } else {
         Files.createDirectories(Objects.requireNonNull(CONFIG_PATH.getParent(), "configParent"));
         Properties properties = new Properties();
         properties.setProperty(TOKEN_KEY, token);

         try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
            properties.store(output, "Tellus HMA Earthdata access");
         }
      }
   }

   private static String normalize(String token) {
      return token == null ? "" : token.trim();
   }
}
