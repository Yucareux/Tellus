package com.yucareux.tellus.preload;

import com.yucareux.tellus.worldgen.WorldProjection;
import java.util.Objects;

public record TerrainPreloadArea(
   double centerLatitude,
   double centerLongitude,
   int chunksPerSide,
   double worldScale,
   int minChunkX,
   int minChunkZ,
   int maxChunkX,
   int maxChunkZ,
   double northLatitude,
   double southLatitude,
   double westLongitude,
   double eastLongitude
) {
   public static final int CHUNK_SIZE = 16;
   public static final int DEFAULT_CHUNKS_PER_SIDE = 32;
   public static final int MIN_CHUNKS_PER_SIDE = 1;
   /** Covers DH's maximum 4096-chunk radius plus its 128-chunk managed safety ring. */
   public static final int DEFAULT_MAX_CHUNKS_PER_SIDE = 8480;
   /** Keeps {@link #totalChunks()} representable as a positive {@code int}. */
   static final int HARD_MAX_CHUNKS_PER_SIDE = 46_340;

   public TerrainPreloadArea {
      if (!Double.isFinite(centerLatitude) || !Double.isFinite(centerLongitude)) {
         throw new IllegalArgumentException("Area center must be finite");
      }

      if (!(worldScale > 0.0) || !Double.isFinite(worldScale)) {
         throw new IllegalArgumentException("World scale must be positive");
      }

      int maxChunks = maxChunksPerSide();
      if (chunksPerSide < MIN_CHUNKS_PER_SIDE || chunksPerSide > maxChunks) {
         throw new IllegalArgumentException("Chunks per side must be between " + MIN_CHUNKS_PER_SIDE + " and " + maxChunks);
      }

      if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
         throw new IllegalArgumentException("Invalid chunk bounds");
      }

      long width = (long)maxChunkX - minChunkX + 1L;
      long depth = (long)maxChunkZ - minChunkZ + 1L;
      if (width <= 0L || depth <= 0L || width > chunksPerSide || depth > chunksPerSide) {
         throw new IllegalArgumentException("Chunk bounds exceed the declared area size");
      }

      checkedMinBlock(minChunkX);
      checkedMinBlock(minChunkZ);
      checkedMaxBlock(maxChunkX);
      checkedMaxBlock(maxChunkZ);

      if (!Double.isFinite(northLatitude)
         || !Double.isFinite(southLatitude)
         || !Double.isFinite(westLongitude)
         || !Double.isFinite(eastLongitude)
         || northLatitude < southLatitude
         || eastLongitude < westLongitude) {
         throw new IllegalArgumentException("Area bounds must be finite and ordered");
      }
   }

   public static TerrainPreloadArea centered(double latitude, double longitude, int chunksPerSide, WorldProjection projection) {
      Objects.requireNonNull(projection, "projection");
      int safeChunks = clampChunksPerSide(chunksPerSide);
      double centerBlockX = projection.lonToBlockX(longitude);
      double centerBlockZ = projection.latToBlockZ(latitude);
      double sideBlocks = safeChunks * (double)CHUNK_SIZE;
      int minChunkX = Math.floorDiv(floorToIntExact(centerBlockX - sideBlocks * 0.5), CHUNK_SIZE);
      int minChunkZ = Math.floorDiv(floorToIntExact(centerBlockZ - sideBlocks * 0.5), CHUNK_SIZE);
      int maxChunkX = checkedChunkEnd(minChunkX, safeChunks);
      int maxChunkZ = checkedChunkEnd(minChunkZ, safeChunks);
      return fromChunkBounds(latitude, longitude, safeChunks, projection, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
   }

   /** Compatibility convenience for callers that explicitly want the historical global origin. */
   public static TerrainPreloadArea centered(double latitude, double longitude, int chunksPerSide, double worldScale) {
      return centered(latitude, longitude, chunksPerSide, WorldProjection.global(worldScale));
   }

   public static TerrainPreloadArea fromChunkBounds(
      double latitude,
      double longitude,
      int chunksPerSide,
      WorldProjection projection,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ
   ) {
      Objects.requireNonNull(projection, "projection");
      int minBlockX = checkedMinBlock(minChunkX);
      int minBlockZ = checkedMinBlock(minChunkZ);
      int maxBlockX = checkedMaxBlock(maxChunkX);
      int maxBlockZ = checkedMaxBlock(maxChunkZ);
      double lonA = projection.blockXToLon(minBlockX);
      double lonB = projection.blockXToLon(maxBlockX);
      double latA = projection.blockZToLat(minBlockZ);
      double latB = projection.blockZToLat(maxBlockZ);
      double north = Math.max(latA, latB);
      double south = Math.min(latA, latB);
      // A centred world's edge is the meridian opposite its origin, so an area that straddles it wraps
      // in longitude; keep the stored bounds ordered by widening east past +180 in that case.
      double west = Math.min(lonA, lonB);
      double east = Math.max(lonA, lonB);
      if (projection.isCentered() && lonB < lonA) {
         double positiveWest = lonA;
         double positiveEast = lonB + 360.0;
         double negativeWest = lonA - 360.0;
         double negativeEast = lonB;
         double positiveDistance = Math.abs((positiveWest + positiveEast) * 0.5 - longitude);
         double negativeDistance = Math.abs((negativeWest + negativeEast) * 0.5 - longitude);
         if (negativeDistance < positiveDistance) {
            west = negativeWest;
            east = negativeEast;
         } else {
            west = positiveWest;
            east = positiveEast;
         }
      }

      return new TerrainPreloadArea(
         projection.clampLatitude(latitude),
         longitude,
         chunksPerSide,
         projection.worldScale(),
         minChunkX,
         minChunkZ,
         maxChunkX,
         maxChunkZ,
         north,
         south,
         west,
         east
      );
   }

   /** Compatibility convenience for callers that explicitly want the historical global origin. */
   public static TerrainPreloadArea fromChunkBounds(
      double latitude,
      double longitude,
      int chunksPerSide,
      double worldScale,
      int minChunkX,
      int minChunkZ,
      int maxChunkX,
      int maxChunkZ
   ) {
      return fromChunkBounds(
         latitude, longitude, chunksPerSide, WorldProjection.global(worldScale), minChunkX, minChunkZ, maxChunkX, maxChunkZ
      );
   }

   public static int clampChunksPerSide(int chunksPerSide) {
      return Math.max(MIN_CHUNKS_PER_SIDE, Math.min(maxChunksPerSide(), chunksPerSide));
   }

   public static int maxChunksPerSide() {
      int configured = Integer.getInteger("tellus.preload.maxChunksPerSide", DEFAULT_MAX_CHUNKS_PER_SIDE);
      return Math.max(MIN_CHUNKS_PER_SIDE, Math.min(HARD_MAX_CHUNKS_PER_SIDE, configured));
   }

   public int totalChunks() {
      return this.chunkWidth() * this.chunkDepth();
   }

   public int chunkWidth() {
      return this.maxChunkX - this.minChunkX + 1;
   }

   public int chunkDepth() {
      return this.maxChunkZ - this.minChunkZ + 1;
   }

   public int minBlockX() {
      return checkedMinBlock(this.minChunkX);
   }

   public int minBlockZ() {
      return checkedMinBlock(this.minChunkZ);
   }

   public int maxBlockX() {
      return checkedMaxBlock(this.maxChunkX);
   }

   public int maxBlockZ() {
      return checkedMaxBlock(this.maxChunkZ);
   }

   public boolean containsChunk(int chunkX, int chunkZ) {
      return chunkX >= this.minChunkX && chunkX <= this.maxChunkX && chunkZ >= this.minChunkZ && chunkZ <= this.maxChunkZ;
   }

   public String summary() {
      return String.format(
         "%d x %d chunks, lat %.5f..%.5f, lon %.5f..%.5f",
         this.chunkWidth(),
         this.chunkDepth(),
         this.southLatitude,
         this.northLatitude,
         this.westLongitude,
         this.eastLongitude
      );
   }

   public String stableKey() {
      return Objects.hash(
         Math.round(this.centerLatitude * 100000.0),
         Math.round(this.centerLongitude * 100000.0),
         this.chunksPerSide,
         Math.round(this.worldScale * 1000.0),
         this.minChunkX,
         this.minChunkZ,
         this.maxChunkX,
         this.maxChunkZ
      )
         + "";
   }

   private static int checkedMinBlock(int chunk) {
      return checkedBlockCoordinate((long)chunk * CHUNK_SIZE);
   }

   private static int checkedMaxBlock(int chunk) {
      return checkedBlockCoordinate(((long)chunk + 1L) * CHUNK_SIZE - 1L);
   }

   private static int checkedBlockCoordinate(long value) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Area lies outside the supported block-coordinate range");
      }
      return (int)value;
   }

   private static int checkedChunkEnd(int minChunk, int chunksPerSide) {
      long value = (long)minChunk + chunksPerSide - 1L;
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Area lies outside the supported chunk-coordinate range");
      }
      return (int)value;
   }

   private static int floorToIntExact(double value) {
      if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
         throw new IllegalArgumentException("Area lies outside the supported block-coordinate range");
      }
      return (int)Math.floor(value);
   }
}
