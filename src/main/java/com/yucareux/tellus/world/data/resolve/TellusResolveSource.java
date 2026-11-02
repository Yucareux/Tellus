package com.yucareux.tellus.world.data.resolve;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.source.RunLengthGridLookup;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline point lookup for the bundled RESOLVE Ecoregions 2017 derivative.
 *
 * <p>The lookup is a 30-arc-second global raster whose latitude rows are
 * run-length encoded. It retains every source ECO_ID while requiring only one
 * small bundled resource and no runtime network access.</p>
 */
public final class TellusResolveSource {
   static final String DEFAULT_LOOKUP_RESOURCE = "/tellus/resolve/resolve_ecoregions_2017.bin.xz";
   static final String DEFAULT_METADATA_RESOURCE = "/tellus/resolve/resolve_ecoregions_2017.csv";
   private static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
   private static final int MAX_RUN_COUNT = 4_000_000;
   private static final int MAX_ECOREGION_ID = 4_096;
   private static final long WARP_X_SEED = 0x2F6D9B41C83A5E17L;
   private static final long WARP_Z_SEED = -0x61B2C9E4A7D3F05BL;

   private final String lookupResource;
   private final String metadataResource;
   private volatile Dataset dataset;

   public TellusResolveSource() {
      this(DEFAULT_LOOKUP_RESOURCE, DEFAULT_METADATA_RESOURCE);
   }

   /**
    * Returns the process-wide bundled lookup used by biome and vegetation
    * classification. The decompressed raster is immutable, so sharing it avoids
    * retaining a second copy when tree generation also needs the exact
    * ecoregion.
    */
   public static TellusResolveSource shared() {
      return SharedHolder.INSTANCE;
   }

   TellusResolveSource(String lookupResource, String metadataResource) {
      this.lookupResource = requireResourcePath(lookupResource);
      this.metadataResource = requireResourcePath(metadataResource);
   }

   public ResolveEcoregion sampleEcoregion(double blockX, double blockZ, double worldScale) {
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ) || !Double.isFinite(worldScale) || worldScale <= 0.0) {
         return ResolveEcoregion.UNKNOWN;
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (!(blocksPerDegree > 0.0) || !Double.isFinite(blocksPerDegree)) {
         return ResolveEcoregion.UNKNOWN;
      }

      // A nearest-cell lookup draws every ecoregion border, and the biomes and trees that follow
      // it, as a staircase of 30" cells (926 m; 31 blocks at 1:30). A smooth warp of about a cell,
      // fixed in block space, bends those borders into natural curves.
      Dataset loaded = this.dataset();
      if (loaded.available()) {
         double cellBlocks = blocksPerDegree * loaded.lookup().arcsecondsPerCell() / 3600.0;
         double warpX = RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_X_SEED);
         double warpZ = RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_Z_SEED);
         blockX += warpX;
         blockZ += warpZ;
      }

      double longitude = blockX / blocksPerDegree;
      double latitude = EarthProjection.blockZToLat(blockZ, worldScale);
      return this.sampleAtLonLat(longitude, latitude);
   }

   public ResolveEcoregion sampleAtLonLat(double longitude, double latitude) {
      if (!Double.isFinite(longitude)
         || !Double.isFinite(latitude)
         || longitude < -180.0
         || longitude > 180.0
         || latitude < -90.0
         || latitude > 90.0) {
         return ResolveEcoregion.UNKNOWN;
      }

      Dataset loaded = this.dataset();
      if (!loaded.available()) {
         return ResolveEcoregion.UNKNOWN;
      }

      int ecoId = loaded.lookup().sample(longitude, latitude);
      if (ecoId == loaded.lookup().noDataId() || ecoId < 0 || ecoId >= loaded.ecoregions().length) {
         return ResolveEcoregion.UNKNOWN;
      }

      ResolveEcoregion ecoregion = loaded.ecoregions()[ecoId];
      return ecoregion == null ? ResolveEcoregion.UNKNOWN : ecoregion;
   }

   public boolean available() {
      return this.dataset().available();
   }

   public int knownEcoregionCount() {
      return this.dataset().knownEcoregionCount();
   }

   int arcsecondsPerCell() {
      Dataset loaded = this.dataset();
      return loaded.available() ? loaded.lookup().arcsecondsPerCell() : 0;
   }

   private Dataset dataset() {
      Dataset loaded = this.dataset;
      if (loaded != null) {
         return loaded;
      }

      synchronized (this) {
         loaded = this.dataset;
         if (loaded == null) {
            this.dataset = loaded = this.loadDataset();
         }
      }
      return loaded;
   }

   private Dataset loadDataset() {
      try {
         ResolveEcoregion[] ecoregions = this.readMetadata();
         RunLengthGridLookup lookup = RunLengthGridLookup.readXzResource(
            TellusResolveSource.class, this.lookupResource, "RESOLVE lookup", MAX_DECOMPRESSED_BYTES, MAX_RUN_COUNT
         );
         validateEcoregionIds(lookup, ecoregions);
         int knownCount = 0;
         for (ResolveEcoregion ecoregion : ecoregions) {
            if (ecoregion != null) {
               knownCount++;
            }
         }

         Tellus.LOGGER.info(
            "Loaded {} RESOLVE ecoregions at {} arc-second resolution",
            knownCount,
            lookup.arcsecondsPerCell()
         );
         return new Dataset(lookup, ecoregions, knownCount);
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.warn("Failed to load bundled RESOLVE ecoregion lookup", error);
         return Dataset.unavailable();
      }
   }

   private static void validateEcoregionIds(RunLengthGridLookup lookup, ResolveEcoregion[] ecoregions) throws IOException {
      for (int run = 0; run < lookup.runCount(); run++) {
         int ecoId = lookup.runValue(run);
         if (ecoId != lookup.noDataId() && (ecoId < 0 || ecoId >= ecoregions.length || ecoregions[ecoId] == null)) {
            throw new IOException("RESOLVE lookup references missing ECO_ID " + ecoId);
         }
      }
   }

   private ResolveEcoregion[] readMetadata() throws IOException {
      try (InputStream input = TellusResolveSource.class.getResourceAsStream(this.metadataResource)) {
         if (input == null) {
            throw new IOException("Missing RESOLVE metadata resource " + this.metadataResource);
         }

         Map<Integer, ResolveEcoregion> byId = new HashMap<>();
         try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
               throw new IOException("RESOLVE metadata is empty");
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
               lineNumber++;
               if (line.isBlank()) {
                  continue;
               }

               List<String> fields = parseCsvLine(line);
               if (fields.size() < 6) {
                  throw new IOException("Invalid RESOLVE metadata at line " + lineNumber);
               }

               int ecoId;
               int biomeNumber;
               try {
                  ecoId = Integer.parseInt(fields.get(0).trim());
                  biomeNumber = Integer.parseInt(fields.get(2).trim());
               } catch (NumberFormatException error) {
                  throw new IOException("Invalid RESOLVE numeric metadata at line " + lineNumber, error);
               }
               if (ecoId < 0 || ecoId > MAX_ECOREGION_ID) {
                  throw new IOException("RESOLVE ECO_ID out of range at line " + lineNumber + ": " + ecoId);
               }

               String name = fields.get(1).trim();
               String biomeName = fields.get(3).trim();
               String realmName = fields.get(4).trim();
               ResolveEcoregion ecoregion = new ResolveEcoregion(
                  ecoId,
                  name,
                  ResolveBiome.fromSource(ecoId, biomeNumber, biomeName),
                  biomeName,
                  ResolveRealm.fromSource(realmName),
                  realmName,
                  fields.get(5).trim()
               );
               if (ecoregion.biome() == ResolveBiome.UNKNOWN) {
                  throw new IOException("Unknown RESOLVE biome at line " + lineNumber + ": " + biomeName);
               }
               if (byId.putIfAbsent(ecoId, ecoregion) != null) {
                  throw new IOException("Duplicate RESOLVE ECO_ID " + ecoId + " at line " + lineNumber);
               }
            }
         }

         if (byId.isEmpty()) {
            throw new IOException("RESOLVE metadata contains no ecoregions");
         }

         int maximumId = byId.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
         ResolveEcoregion[] ecoregions = new ResolveEcoregion[maximumId + 1];
         byId.forEach((id, ecoregion) -> ecoregions[id] = ecoregion);
         return ecoregions;
      }
   }

   private static String requireResourcePath(String resourcePath) {
      if (resourcePath == null || resourcePath.isBlank() || !resourcePath.startsWith("/")) {
         throw new IllegalArgumentException("Resource path must be absolute");
      }
      return resourcePath;
   }

   private static List<String> parseCsvLine(String line) throws IOException {
      List<String> fields = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      boolean quoted = false;
      for (int i = 0; i < line.length(); i++) {
         char ch = line.charAt(i);
         if (ch == '"') {
            if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
               current.append('"');
               i++;
            } else {
               quoted = !quoted;
            }
         } else if (ch == ',' && !quoted) {
            fields.add(current.toString());
            current.setLength(0);
         } else {
            current.append(ch);
         }
      }
      if (quoted) {
         throw new IOException("Unterminated quoted CSV field");
      }
      fields.add(current.toString());
      return fields;
   }

   private record Dataset(RunLengthGridLookup lookup, ResolveEcoregion[] ecoregions, int knownEcoregionCount) {
      private static final Dataset UNAVAILABLE = new Dataset(null, new ResolveEcoregion[0], 0);

      static Dataset unavailable() {
         return UNAVAILABLE;
      }

      boolean available() {
         return this.lookup != null;
      }
   }

   private static final class SharedHolder {
      private static final TellusResolveSource INSTANCE = new TellusResolveSource();

      private SharedHolder() {
      }
   }
}
