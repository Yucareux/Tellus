package com.yucareux.tellus.world.data.resolve;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tukaani.xz.SingleXZInputStream;

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
   private static final byte[] MAGIC = new byte[]{'T', 'R', 'S', 'L'};
   private static final int FORMAT_VERSION = 2;
   private static final int HEADER_BYTES = 24;
   private static final int MAX_XZ_MEMORY_KIB = 32 * 1024;
   private static final int MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
   private static final int MAX_WIDTH = 65_535;
   private static final int MAX_HEIGHT = 32_768;
   private static final int MAX_RUN_COUNT = 4_000_000;
   private static final int MAX_ECOREGION_ID = 4_096;

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

   public ResolveEcoregion sampleEcoregion(double blockX, double blockZ, WorldProjection projection) {
      double worldScale = projection.worldScale();
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ) || !Double.isFinite(worldScale) || worldScale <= 0.0) {
         return ResolveEcoregion.UNKNOWN;
      }

      double longitude = projection.blockXToLon(blockX);
      double latitude = projection.blockZToLat(blockZ);
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
         RasterLookup lookup = this.readLookup();
         lookup.validateEcoregionIds(ecoregions);
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

   private RasterLookup readLookup() throws IOException {
      try (InputStream raw = TellusResolveSource.class.getResourceAsStream(this.lookupResource)) {
         if (raw == null) {
            throw new IOException("Missing RESOLVE lookup resource " + this.lookupResource);
         }

         byte[] bytes;
         try (SingleXZInputStream xz = new SingleXZInputStream(raw, MAX_XZ_MEMORY_KIB)) {
            bytes = InputStreamSafety.readAllBytes(xz, MAX_DECOMPRESSED_BYTES, "RESOLVE lookup");
         }
         return RasterLookup.parse(bytes);
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

   private record Dataset(RasterLookup lookup, ResolveEcoregion[] ecoregions, int knownEcoregionCount) {
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

   private static final class RasterLookup {
      private final byte[] data;
      private final int arcsecondsPerCell;
      private final int width;
      private final int height;
      private final int runCount;
      private final int noDataId;
      private final int rowOffsetsStart;
      private final int runsStart;
      private final int cellsPerDegree;

      private RasterLookup(
         byte[] data,
         int arcsecondsPerCell,
         int width,
         int height,
         int runCount,
         int noDataId,
         int rowOffsetsStart,
         int runsStart
      ) {
         this.data = data;
         this.arcsecondsPerCell = arcsecondsPerCell;
         this.width = width;
         this.height = height;
         this.runCount = runCount;
         this.noDataId = noDataId;
         this.rowOffsetsStart = rowOffsetsStart;
         this.runsStart = runsStart;
         this.cellsPerDegree = 3600 / arcsecondsPerCell;
      }

      static RasterLookup parse(byte[] data) throws IOException {
         if (data.length < HEADER_BYTES) {
            throw new IOException("Truncated RESOLVE lookup header");
         }
         for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
               throw new IOException("Invalid RESOLVE lookup signature");
            }
         }

         int version = readUnsignedShort(data, 4);
         if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported RESOLVE lookup version " + version);
         }

         int arcseconds = readUnsignedShort(data, 6);
         int width = readInt(data, 8);
         int height = readInt(data, 12);
         int runCount = readInt(data, 16);
         int noDataId = readInt(data, 20);
         if (arcseconds <= 0 || 3600 % arcseconds != 0) {
            throw new IOException("Invalid RESOLVE cell resolution " + arcseconds);
         }
         int cellsPerDegree = 3600 / arcseconds;
         if (width != 360 * cellsPerDegree
            || height != 180 * cellsPerDegree
            || width <= 0
            || width > MAX_WIDTH
            || height <= 0
            || height > MAX_HEIGHT) {
            throw new IOException("Invalid RESOLVE grid dimensions " + width + "x" + height);
         }
         if (runCount <= 0 || runCount > MAX_RUN_COUNT) {
            throw new IOException("Invalid RESOLVE run count " + runCount);
         }
         if (noDataId < 0 || noDataId > 65_535) {
            throw new IOException("Invalid RESOLVE no-data ID " + noDataId);
         }

         int rowOffsetsStart = HEADER_BYTES;
         long runsStartLong = rowOffsetsStart + (long)(height + 1) * Integer.BYTES;
         long expectedBytes = runsStartLong + (long)runCount * 2L * Short.BYTES;
         if (runsStartLong > Integer.MAX_VALUE || expectedBytes != data.length) {
            throw new IOException("Unexpected RESOLVE lookup byte length " + data.length);
         }

         RasterLookup lookup = new RasterLookup(
            data,
            arcseconds,
            width,
            height,
            runCount,
            noDataId,
            rowOffsetsStart,
            (int)runsStartLong
         );
         lookup.validateStructure();
         return lookup;
      }

      int sample(double longitude, double latitude) {
         int x = clampCell((int)Math.floor((longitude + 180.0) * this.cellsPerDegree), this.width);
         int y = clampCell((int)Math.floor((90.0 - latitude) * this.cellsPerDegree), this.height);
         int firstRun = this.rowOffset(y);
         int endRun = this.rowOffset(y + 1);
         int low = firstRun;
         int high = endRun - 1;
         while (low <= high) {
            int middle = (low + high) >>> 1;
            int startX = this.runStartX(middle);
            if (startX <= x) {
               low = middle + 1;
            } else {
               high = middle - 1;
            }
         }
         return this.runEcoregionId(Math.max(firstRun, high));
      }

      int arcsecondsPerCell() {
         return this.arcsecondsPerCell;
      }

      int noDataId() {
         return this.noDataId;
      }

      void validateEcoregionIds(ResolveEcoregion[] ecoregions) throws IOException {
         for (int run = 0; run < this.runCount; run++) {
            int ecoId = this.runEcoregionId(run);
            if (ecoId != this.noDataId && (ecoId < 0 || ecoId >= ecoregions.length || ecoregions[ecoId] == null)) {
               throw new IOException("RESOLVE lookup references missing ECO_ID " + ecoId);
            }
         }
      }

      private void validateStructure() throws IOException {
         if (this.rowOffset(0) != 0 || this.rowOffset(this.height) != this.runCount) {
            throw new IOException("Invalid RESOLVE row-offset bounds");
         }

         int previousEnd = 0;
         for (int row = 0; row < this.height; row++) {
            int firstRun = this.rowOffset(row);
            int endRun = this.rowOffset(row + 1);
            if (firstRun != previousEnd || firstRun < 0 || endRun <= firstRun || endRun > this.runCount) {
               throw new IOException("Invalid RESOLVE run slice at row " + row);
            }
            if (this.runStartX(firstRun) != 0) {
               throw new IOException("RESOLVE row " + row + " does not begin at longitude cell 0");
            }

            int previousX = -1;
            for (int run = firstRun; run < endRun; run++) {
               int startX = this.runStartX(run);
               if (startX <= previousX || startX >= this.width) {
                  throw new IOException("Invalid RESOLVE run ordering at row " + row);
               }
               previousX = startX;
            }
            previousEnd = endRun;
         }
      }

      private int rowOffset(int row) {
         return readInt(this.data, this.rowOffsetsStart + row * Integer.BYTES);
      }

      private int runStartX(int run) {
         return readUnsignedShort(this.data, this.runsStart + run * 4);
      }

      private int runEcoregionId(int run) {
         return readUnsignedShort(this.data, this.runsStart + run * 4 + 2);
      }

      private static int clampCell(int value, int size) {
         return Math.max(0, Math.min(size - 1, value));
      }

      private static int readUnsignedShort(byte[] data, int offset) {
         return Byte.toUnsignedInt(data[offset]) | Byte.toUnsignedInt(data[offset + 1]) << 8;
      }

      private static int readInt(byte[] data, int offset) {
         return Byte.toUnsignedInt(data[offset])
            | Byte.toUnsignedInt(data[offset + 1]) << 8
            | Byte.toUnsignedInt(data[offset + 2]) << 16
            | Byte.toUnsignedInt(data[offset + 3]) << 24;
      }
   }
}
