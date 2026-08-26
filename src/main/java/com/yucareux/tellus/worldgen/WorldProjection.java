package com.yucareux.tellus.worldgen;

import java.util.Objects;

/**
 * The horizontal projection of one Tellus world: spherical (Web) Mercator at the configured equatorial
 * {@link #worldScale()} whose block origin is either the prime meridian on the equator (the historical
 * {@link #global(double) global} layout) or the world's spawn point ({@link #centeredOn(double, double, double)
 * centred}).
 *
 * <p>All block{@code <->}geographic conversions in Tellus go through an instance of this class so that a
 * world's origin is applied exactly once. Centring keeps block coordinates small around the place the player
 * actually lives, puts vanilla origin-centred mechanics such as stronghold rings around spawn, and moves the
 * unreachable Mercator edge to the meridian opposite the spawn point. It does not change local distortion;
 * the {@link #heightScaleCorrection(double) height correction} is still the Mercator {@code sec(latitude)}.</p>
 *
 * <p>Block X grows eastward and block Z grows southward. "Projected metres" are global Web Mercator metres
 * measured from 0°N 0°E with the same axis directions; they are independent of the world's origin and scale
 * and are therefore the right key for caches shared between worlds.</p>
 */
public final class WorldProjection {
   private final double worldScale;
   private final double originLatitude;
   private final double originLongitude;
   private final boolean centered;
   private final double originMercatorY;
   private final double blocksPerDegree;
   private final double minMercatorY;
   private final double maxMercatorY;

   private WorldProjection(double worldScale, double originLatitude, double originLongitude, boolean centered) {
      if (!Double.isFinite(worldScale)) {
         throw new IllegalArgumentException("World scale must be finite: " + worldScale);
      }

      if (!Double.isFinite(originLatitude) || !Double.isFinite(originLongitude)) {
         throw new IllegalArgumentException("Projection origin must be finite: " + originLatitude + ", " + originLongitude);
      }

      this.worldScale = worldScale;
      this.centered = centered;
      this.originLatitude = centered ? EarthProjection.clampLatitude(originLatitude) : 0.0;
      this.originLongitude = centered ? wrapLongitude(originLongitude) : 0.0;
      this.originMercatorY = centered ? EarthProjection.northingMeters(this.originLatitude) : 0.0;
      this.blocksPerDegree = EarthProjection.equatorialBlocksPerDegree(worldScale);
      // Keep the usable Z range symmetric around the origin so Increase Height's packed coordinate
      // profile and the Mercator square stay valid; the latitude band shifts with the origin instead.
      double maximumNorthing = EarthProjection.maxNorthingMeters();
      this.minMercatorY = Math.max(-maximumNorthing, this.originMercatorY - maximumNorthing);
      this.maxMercatorY = Math.min(maximumNorthing, this.originMercatorY + maximumNorthing);
   }

   /** The historical layout: block (0, 0) is 0°N 0°E. */
   public static WorldProjection global(double worldScale) {
      return new WorldProjection(worldScale, 0.0, 0.0, false);
   }

   /** Block (0, 0) is the given point; longitude wraps around the meridian opposite it. */
   public static WorldProjection centeredOn(double worldScale, double originLatitude, double originLongitude) {
      return new WorldProjection(worldScale, originLatitude, originLongitude, true);
   }

   public static WorldProjection of(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      return settings.centerWorldOnSpawn()
         ? centeredOn(settings.worldScale(), settings.spawnLatitude(), settings.spawnLongitude())
         : global(settings.worldScale());
   }

   public WorldProjection withWorldScale(double worldScale) {
      return new WorldProjection(worldScale, this.originLatitude, this.originLongitude, this.centered);
   }

   public double worldScale() {
      return this.worldScale;
   }

   public boolean isCentered() {
      return this.centered;
   }

   public double originLatitude() {
      return this.originLatitude;
   }

   public double originLongitude() {
      return this.originLongitude;
   }

   /**
    * Blocks spanned by one degree of longitude at the equator. This is a resolution heuristic only; use
    * {@link #lonToBlockX(double)} and {@link #blockXToLon(double)} for coordinate conversion.
    */
   public double equatorialBlocksPerDegree() {
      return this.blocksPerDegree;
   }

   public double worldWidthBlocks() {
      return 360.0 * this.blocksPerDegree;
   }

   /**
    * True when the straight block-space segment would jump across the projection's longitude seam.
    * Such a segment must be split or skipped rather than rasterized across the whole world.
    */
   public boolean crossesWorldSeam(double firstBlockX, double secondBlockX) {
      return this.worldScale > 0.0 && Math.abs(secondBlockX - firstBlockX) > this.worldWidthBlocks() * 0.5;
   }

   public boolean containsHorizontalBlock(double blockX, double blockZ) {
      if (!(this.worldScale > 0.0) || !Double.isFinite(blockX) || !Double.isFinite(blockZ)) {
         return false;
      }
      double halfWorld = this.worldWidthBlocks() * 0.5;
      double northing = this.originMercatorY - blockZ * this.worldScale;
      return blockX >= -halfWorld
         && blockX <= halfWorld
         && northing >= this.minMercatorY
         && northing <= this.maxMercatorY;
   }

   public double lonToBlockX(double longitude) {
      if (!(this.worldScale > 0.0)) {
         return 0.0;
      }

      double relative = this.centered ? wrapLongitude(longitude - this.originLongitude) : longitude;
      return relative * this.blocksPerDegree;
   }

   public double blockXToLon(double blockX) {
      if (!(this.worldScale > 0.0)) {
         return 0.0;
      }

      double relative = blockX / this.blocksPerDegree;
      if (!this.centered || relative < -180.0 || relative > 180.0) {
         // Beyond the world's edge the result is intentionally left outside +-180 so data sources treat
         // it as uncovered instead of wrapping the planet around again.
         return relative;
      }

      return wrapLongitude(relative + this.originLongitude);
   }

   /** Longitude offset from the world's origin meridian without geographic wrapping. */
   public double blockXToRelativeLongitude(double blockX) {
      return !(this.worldScale > 0.0) ? 0.0 : blockX / this.blocksPerDegree;
   }

   public double latToBlockZ(double latitude) {
      if (!(this.worldScale > 0.0)) {
         return 0.0;
      } else if (EarthProjection.isLegacyMode()) {
         return -(latitude - this.originLatitude) * this.blocksPerDegree;
      }

      double northing = EarthProjection.northingMeters(this.clampLatitude(latitude));
      return -(northing - this.originMercatorY) / this.worldScale;
   }

   public double blockZToLat(double blockZ) {
      if (!(this.worldScale > 0.0)) {
         return 0.0;
      } else if (EarthProjection.isLegacyMode()) {
         return this.originLatitude - blockZ / this.blocksPerDegree;
      }

      double northing = this.clampMercatorY(this.originMercatorY - blockZ * this.worldScale);
      return this.clampLatitude(EarthProjection.latitudeFromNorthingMeters(northing));
   }

   /**
    * Vertical multiplier keeping Mercator-projected terrain at the same local scale on all three axes:
    * {@code sec(latitude)} expressed directly from the block row as {@code cosh(northing / R)}.
    */
   public double heightScaleCorrection(double blockZ) {
      if (!(this.worldScale > 0.0) || EarthProjection.isLegacyMode()) {
         return 1.0;
      }

      double northing = this.clampMercatorY(this.originMercatorY - blockZ * this.worldScale);
      return Math.cosh(northing / EarthProjection.EARTH_RADIUS_METERS);
   }

   public double groundMetersPerBlockX(double blockZ) {
      return this.groundMetersPerBlock(blockZ);
   }

   public double groundMetersPerBlockZ(double blockZ) {
      return this.groundMetersPerBlock(blockZ);
   }

   private double groundMetersPerBlock(double blockZ) {
      if (!(this.worldScale > 0.0)) {
         return 0.0;
      }

      return EarthProjection.isLegacyMode() ? this.worldScale : this.worldScale / this.heightScaleCorrection(blockZ);
   }

   /** Global Web Mercator easting in metres from the prime meridian for a block column. */
   public double blockXToProjectedMeters(double blockX) {
      return this.centered ? this.blockXToLon(blockX) * EarthProjection.METERS_PER_DEGREE : blockX * this.worldScale;
   }

   /** Global Web Mercator "southing" in metres from the equator for a block row (positive toward the south). */
   public double blockZToProjectedMeters(double blockZ) {
      return blockZ * this.worldScale - this.originMercatorY;
   }

   public double projectedMetersToBlockX(double projectedX) {
      return this.centered ? this.lonToBlockX(projectedX / EarthProjection.METERS_PER_DEGREE) : projectedX / this.worldScale;
   }

   public double projectedMetersToBlockZ(double projectedZ) {
      return (projectedZ + this.originMercatorY) / this.worldScale;
   }

   /** The northernmost latitude representable in this world. */
   public double maxLatitude() {
      return this.centered ? EarthProjection.latitudeFromNorthingMeters(this.maxMercatorY) : EarthProjection.MAX_MERCATOR_LATITUDE;
   }

   /** The southernmost latitude representable in this world. */
   public double minLatitude() {
      return this.centered ? EarthProjection.latitudeFromNorthingMeters(this.minMercatorY) : -EarthProjection.MAX_MERCATOR_LATITUDE;
   }

   public double clampLatitude(double latitude) {
      if (!this.centered) {
         return EarthProjection.clampLatitude(latitude);
      }

      return Math.max(this.minLatitude(), Math.min(this.maxLatitude(), latitude));
   }

   private double clampMercatorY(double mercatorY) {
      return Math.max(this.minMercatorY, Math.min(this.maxMercatorY, mercatorY));
   }

   /** Wraps a longitude into {@code [-180, 180)}. */
   public static double wrapLongitude(double longitude) {
      if (!Double.isFinite(longitude)) {
         return longitude;
      }

      double wrapped = (longitude + 180.0) % 360.0;
      if (wrapped < 0.0) {
         wrapped += 360.0;
      }

      return wrapped - 180.0;
   }

   /** Wraps global Web Mercator easting into the canonical half-open world interval. */
   public static double wrapProjectedX(double projectedX) {
      if (!Double.isFinite(projectedX)) {
         return projectedX;
      }

      double halfCircumference = EarthProjection.EQUATOR_CIRCUMFERENCE_METERS * 0.5;
      double wrapped = (projectedX + halfCircumference) % EarthProjection.EQUATOR_CIRCUMFERENCE_METERS;
      if (wrapped < 0.0) {
         wrapped += EarthProjection.EQUATOR_CIRCUMFERENCE_METERS;
      }
      return wrapped - halfCircumference;
   }

   public static double clampProjectedZ(double projectedZ) {
      double maximum = EarthProjection.maxNorthingMeters();
      return Math.max(-maximum, Math.min(maximum, projectedZ));
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof WorldProjection)) {
         return false;
      }

      WorldProjection that = (WorldProjection)other;
      return this.centered == that.centered
         && Double.compare(this.worldScale, that.worldScale) == 0
         && Double.compare(this.originLatitude, that.originLatitude) == 0
         && Double.compare(this.originLongitude, that.originLongitude) == 0;
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.worldScale, this.originLatitude, this.originLongitude, this.centered);
   }

   @Override
   public String toString() {
      return this.centered
         ? "WorldProjection[scale=" + this.worldScale + ", origin=" + this.originLatitude + "," + this.originLongitude + "]"
         : "WorldProjection[scale=" + this.worldScale + ", global]";
   }
}
