package com.yucareux.tellus.world.data.source;

import java.io.IOException;
import java.io.InputStream;
import org.tukaani.xz.SingleXZInputStream;

/**
 * A bundled global latitude-longitude raster of 16-bit values whose rows are run-length encoded.
 *
 * <p>Written by {@code tools/build_resolve_lookup.py} and {@code tools/lithology-lookup}: a 24-byte header, one
 * run offset per row plus a final one, then little-endian (start column, value) pairs.</p>
 */
public final class RunLengthGridLookup {
   private static final byte[] MAGIC = new byte[]{'T', 'R', 'S', 'L'};
   private static final int FORMAT_VERSION = 2;
   private static final int HEADER_BYTES = 24;
   private static final int MAX_XZ_MEMORY_KIB = 32 * 1024;
   private static final int MAX_WIDTH = 65_535;
   private static final int MAX_HEIGHT = 32_768;
   private static final double WARP_BROAD_WAVELENGTH_CELLS = 4.5;
   private static final double WARP_BROAD_AMPLITUDE_CELLS = 1.1;
   private static final double WARP_DETAIL_WAVELENGTH_CELLS = 1.3;
   private static final double WARP_DETAIL_AMPLITUDE_CELLS = 0.35;
   private static final long WARP_DETAIL_SALT = 0x4C1E8A7B39D6F25DL;

   private final byte[] data;
   private final String label;
   private final int arcsecondsPerCell;
   private final int width;
   private final int height;
   private final int runCount;
   private final int noDataId;
   private final int rowOffsetsStart;
   private final int runsStart;
   private final int cellsPerDegree;

   private RunLengthGridLookup(
      byte[] data,
      String label,
      int arcsecondsPerCell,
      int width,
      int height,
      int runCount,
      int noDataId,
      int rowOffsetsStart,
      int runsStart
   ) {
      this.data = data;
      this.label = label;
      this.arcsecondsPerCell = arcsecondsPerCell;
      this.width = width;
      this.height = height;
      this.runCount = runCount;
      this.noDataId = noDataId;
      this.rowOffsetsStart = rowOffsetsStart;
      this.runsStart = runsStart;
      this.cellsPerDegree = 3600 / arcsecondsPerCell;
   }

   /** Reads and validates an XZ-compressed lookup bundled with {@code owner}. */
   public static RunLengthGridLookup readXzResource(
      Class<?> owner, String resource, String label, int maxDecompressedBytes, int maxRunCount
   ) throws IOException {
      try (InputStream raw = owner.getResourceAsStream(resource)) {
         if (raw == null) {
            throw new IOException("Missing " + label + " resource " + resource);
         }

         byte[] bytes;
         try (SingleXZInputStream xz = new SingleXZInputStream(raw, MAX_XZ_MEMORY_KIB)) {
            bytes = InputStreamSafety.readAllBytes(xz, maxDecompressedBytes, label);
         }
         return parse(bytes, label, maxRunCount);
      }
   }

   public static RunLengthGridLookup parse(byte[] data, String label, int maxRunCount) throws IOException {
      if (data.length < HEADER_BYTES) {
         throw new IOException("Truncated " + label + " header");
      }
      for (int i = 0; i < MAGIC.length; i++) {
         if (data[i] != MAGIC[i]) {
            throw new IOException("Invalid " + label + " signature");
         }
      }

      int version = readUnsignedShort(data, 4);
      if (version != FORMAT_VERSION) {
         throw new IOException("Unsupported " + label + " version " + version);
      }

      int arcseconds = readUnsignedShort(data, 6);
      int width = readInt(data, 8);
      int height = readInt(data, 12);
      int runCount = readInt(data, 16);
      int noDataId = readInt(data, 20);
      if (arcseconds <= 0 || 3600 % arcseconds != 0) {
         throw new IOException("Invalid " + label + " cell resolution " + arcseconds);
      }
      int cellsPerDegree = 3600 / arcseconds;
      if (width != 360 * cellsPerDegree
         || height != 180 * cellsPerDegree
         || width <= 0
         || width > MAX_WIDTH
         || height <= 0
         || height > MAX_HEIGHT) {
         throw new IOException("Invalid " + label + " grid dimensions " + width + "x" + height);
      }
      if (runCount <= 0 || runCount > maxRunCount) {
         throw new IOException("Invalid " + label + " run count " + runCount);
      }
      if (noDataId < 0 || noDataId > 65_535) {
         throw new IOException("Invalid " + label + " no-data ID " + noDataId);
      }

      int rowOffsetsStart = HEADER_BYTES;
      long runsStartLong = rowOffsetsStart + (long)(height + 1) * Integer.BYTES;
      long expectedBytes = runsStartLong + (long)runCount * 2L * Short.BYTES;
      if (runsStartLong > Integer.MAX_VALUE || expectedBytes != data.length) {
         throw new IOException("Unexpected " + label + " byte length " + data.length);
      }

      RunLengthGridLookup lookup = new RunLengthGridLookup(
         data,
         label,
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

   public int sample(double longitude, double latitude) {
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
      return this.runValue(Math.max(firstRun, high));
   }

   public int arcsecondsPerCell() {
      return this.arcsecondsPerCell;
   }

   public int noDataId() {
      return this.noDataId;
   }

   public int runCount() {
      return this.runCount;
   }

   public int runValue(int run) {
      return readUnsignedShort(this.data, this.runsStart + run * 4 + 2);
   }

   /**
    * A smooth offset of about one cell, fixed in block space. Adding it to a block position before a nearest-cell
    * lookup bends the staircase of raster cell borders into natural curves.
    */
   public static double borderWarp(double blockX, double blockZ, double cellBlocks, long seed) {
      if (!(Double.isFinite(cellBlocks) && cellBlocks > 1.0)) {
         return 0.0;
      }
      double broadBlocks = cellBlocks * WARP_BROAD_WAVELENGTH_CELLS;
      double detailBlocks = cellBlocks * WARP_DETAIL_WAVELENGTH_CELLS;
      double broad = valueNoise(blockX / broadBlocks, blockZ / broadBlocks, seed) * 2.0 - 1.0;
      double detail = valueNoise(blockX / detailBlocks + 23.5, blockZ / detailBlocks - 7.25, seed ^ WARP_DETAIL_SALT) * 2.0 - 1.0;
      return (broad * WARP_BROAD_AMPLITUDE_CELLS + detail * WARP_DETAIL_AMPLITUDE_CELLS) * cellBlocks;
   }

   private static double valueNoise(double x, double z, long seed) {
      double floorX = Math.floor(x);
      double floorZ = Math.floor(z);
      long x0 = (long)floorX;
      long z0 = (long)floorZ;
      double fx = smootherStep(x - floorX);
      double fz = smootherStep(z - floorZ);
      double north = lerp(fx, hashToUnit(x0, z0, seed), hashToUnit(x0 + 1L, z0, seed));
      double south = lerp(fx, hashToUnit(x0, z0 + 1L, seed), hashToUnit(x0 + 1L, z0 + 1L, seed));
      return lerp(fz, north, south);
   }

   private static double smootherStep(double value) {
      return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
   }

   private static double lerp(double delta, double start, double end) {
      return start + delta * (end - start);
   }

   private static double hashToUnit(long x, long z, long seed) {
      long hash = seed ^ x * -7046029254386353131L ^ z * -4417276706812531889L;
      hash = (hash ^ hash >>> 33) * -49064778989728563L;
      hash = (hash ^ hash >>> 33) * -4265267296055464877L;
      hash ^= hash >>> 33;
      return (hash >>> 11) * 0x1.0p-53;
   }

   private void validateStructure() throws IOException {
      if (this.rowOffset(0) != 0 || this.rowOffset(this.height) != this.runCount) {
         throw new IOException("Invalid " + this.label + " row-offset bounds");
      }

      int previousEnd = 0;
      for (int row = 0; row < this.height; row++) {
         int firstRun = this.rowOffset(row);
         int endRun = this.rowOffset(row + 1);
         if (firstRun != previousEnd || firstRun < 0 || endRun <= firstRun || endRun > this.runCount) {
            throw new IOException("Invalid " + this.label + " run slice at row " + row);
         }
         if (this.runStartX(firstRun) != 0) {
            throw new IOException(this.label + " row " + row + " does not begin at longitude cell 0");
         }

         int previousX = -1;
         for (int run = firstRun; run < endRun; run++) {
            int startX = this.runStartX(run);
            if (startX <= previousX || startX >= this.width) {
               throw new IOException("Invalid " + this.label + " run ordering at row " + row);
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
