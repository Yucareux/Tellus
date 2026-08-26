package com.yucareux.tellus.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TerrainPreloadStorageTest {
   @Test
   void rejectsIdentifiersThatCouldEscapeTheCacheRoot() {
      assertTrue(TerrainPreloadStorage.isValidIdentifier("preload-1234_test.v2"));
      assertFalse(TerrainPreloadStorage.isValidIdentifier("../../outside"));
      assertFalse(TerrainPreloadStorage.isValidIdentifier(".."));
      assertThrows(
         IllegalArgumentException.class,
         () -> TerrainPreloadStorage.instance().publishedDirectory("../../outside")
      );
   }

   @Test
   void defaultFalseProjectionFieldsKeepLegacyPackageFingerprints() {
      TerrainPreloadStorage storage = TerrainPreloadStorage.instance();
      JsonObject legacy = new JsonObject();
      legacy.addProperty("world_scale", 30.0);
      legacy.addProperty("spawn_latitude", 37.7459);

      JsonObject explicitDefaults = legacy.deepCopy();
      explicitDefaults.addProperty("world_scale_at_spawn", false);
      explicitDefaults.addProperty("center_world_on_spawn", false);
      assertEquals(storage.settingsFingerprint(legacy), storage.settingsFingerprint(explicitDefaults));

      JsonObject centered = explicitDefaults.deepCopy();
      centered.addProperty("center_world_on_spawn", true);
      assertNotEquals(storage.settingsFingerprint(legacy), storage.settingsFingerprint(centered));
   }
}
