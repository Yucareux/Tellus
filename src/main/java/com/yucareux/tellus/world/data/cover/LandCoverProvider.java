package com.yucareux.tellus.world.data.cover;

public interface LandCoverProvider {
    /**
     * Sample the land cover class at the given longitude and latitude.
     *
     * @param lon Longitude in degrees.
     * @param lat Latitude in degrees.
     * @return The land cover class ID, or 0 if no data.
     */
    int sample(double lon, double lat);

    /**
     * Prefetch tiles for the given area.
     *
     * @param minLat Minimum latitude.
     * @param minLon Minimum longitude.
     * @param maxLat Maximum latitude.
     * @param maxLon Maximum longitude.
     */
    void prefetch(double minLat, double minLon, double maxLat, double maxLon);
}
