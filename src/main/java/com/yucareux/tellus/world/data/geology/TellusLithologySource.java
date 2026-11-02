package com.yucareux.tellus.world.data.geology;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.source.RunLengthGridLookup;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.IOException;

/**
 * Offline rock-class lookup for the bundled GLiM lithology derivative.
 *
 * <p>The lookup is a 60-arc-second global raster built by {@code tools/lithology-lookup} from the OpenLandMap
 * GLiM lithology class map (Hengl 2018, CC BY-SA 4.0). Water cells next to land carry the shore's class, so sea
 * cliffs keep their rock.</p>
 */
public final class TellusLithologySource {
   static final String DEFAULT_LOOKUP_RESOURCE = "/tellus/geology/glim_lithology_60s.bin.xz";
   private static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
   private static final int MAX_RUN_COUNT = 8_000_000;
   private static final long WARP_X_SEED = 0x51A7E3C94D2B86F1L;
   private static final long WARP_Z_SEED = -0x3C8E1F6A2D97B54DL;

   private final String lookupResource;
   private volatile RunLengthGridLookup lookup;
   private volatile boolean loaded;

   TellusLithologySource(String lookupResource) {
      this.lookupResource = lookupResource;
   }

   /** The process-wide lookup; the decompressed raster is immutable and about 18 MB, so it is loaded once. */
   public static TellusLithologySource shared() {
      return SharedHolder.INSTANCE;
   }

   /**
    * Rock class at a block position. A smooth warp of about a cell bends the raster's staircase borders, as for the
    * RESOLVE ecoregions.
    */
   public Lithology sample(double blockX, double blockZ, double worldScale) {
      if (!Double.isFinite(blockX) || !Double.isFinite(blockZ) || !Double.isFinite(worldScale) || worldScale <= 0.0) {
         return Lithology.UNKNOWN;
      }

      RunLengthGridLookup grid = this.lookup();
      if (grid == null) {
         return Lithology.UNKNOWN;
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      double cellBlocks = blocksPerDegree * grid.arcsecondsPerCell() / 3600.0;
      double warpedX = blockX + RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_X_SEED);
      double warpedZ = blockZ + RunLengthGridLookup.borderWarp(blockX, blockZ, cellBlocks, WARP_Z_SEED);
      return this.sampleAtLonLat(warpedX / blocksPerDegree, EarthProjection.blockZToLat(warpedZ, worldScale));
   }

   public Lithology sampleAtLonLat(double longitude, double latitude) {
      if (!Double.isFinite(longitude) || !Double.isFinite(latitude) || latitude < -90.0 || latitude > 90.0) {
         return Lithology.UNKNOWN;
      }

      RunLengthGridLookup grid = this.lookup();
      if (grid == null) {
         return Lithology.UNKNOWN;
      }
      double wrappedLongitude = longitude - 360.0 * Math.floor((longitude + 180.0) / 360.0);
      return Lithology.fromCode(grid.sample(wrappedLongitude, latitude));
   }

   public boolean available() {
      return this.lookup() != null;
   }

   private RunLengthGridLookup lookup() {
      if (this.loaded) {
         return this.lookup;
      }

      synchronized (this) {
         if (!this.loaded) {
            try {
               RunLengthGridLookup grid = RunLengthGridLookup.readXzResource(
                  TellusLithologySource.class, this.lookupResource, "lithology lookup", MAX_DECOMPRESSED_BYTES, MAX_RUN_COUNT
               );
               validateClasses(grid);
               this.lookup = grid;
               Tellus.LOGGER.info("Loaded GLiM rock classes at {} arc-second resolution", grid.arcsecondsPerCell());
            } catch (IOException | RuntimeException error) {
               Tellus.LOGGER.warn("Failed to load bundled lithology lookup", error);
            }
            this.loaded = true;
         }
      }
      return this.lookup;
   }

   private static void validateClasses(RunLengthGridLookup grid) throws IOException {
      int classCount = Lithology.values().length;
      for (int run = 0; run < grid.runCount(); run++) {
         if (grid.runValue(run) >= classCount) {
            throw new IOException("Lithology lookup references unknown class " + grid.runValue(run));
         }
      }
   }

   private static final class SharedHolder {
      private static final TellusLithologySource INSTANCE = new TellusLithologySource(DEFAULT_LOOKUP_RESOURCE);

      private SharedHolder() {
      }
   }
}
