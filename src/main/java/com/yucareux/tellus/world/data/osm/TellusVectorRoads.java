package com.yucareux.tellus.world.data.osm;

import net.minecraft.util.Mth;

public final class TellusVectorRoads {
    private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
    private final TellusOsmDataset dataset;
    private final double defaultRoadRadius = 2.5;

    public TellusVectorRoads(TellusOsmDataset dataset) {
        this.dataset = dataset;
    }

    public static int detailLevelToZoom(int detailLevel) {
        // Always use zoom 0 (1/64 degree tiles) to ensure we get building data.
        // Higher zoom levels (larger tiles) lack building definitions on the server.
        return 0;
    }

    public RoadSample sample(double blockX, double blockZ, double worldScale, int detailLevel) {
        if (worldScale <= 0)
            return RoadSample.UNKNOWN;

        double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
        double blocksPerDegree = metersPerDegree / worldScale;
        double lon = blockX / blocksPerDegree;
        double lat = -blockZ / blocksPerDegree;

        // Correct T++ Tiling: higher zoom = larger tiles
        int zoom = detailLevelToZoom(detailLevel);
        double baseTileSize = 1.0 / 64.0;
        double actualTileSize = baseTileSize * (1 << zoom);

        int tileX = Mth.floor(lon / actualTileSize);
        int tileY = Mth.floor(lat / actualTileSize);

        TellusOsmDataset.OsmData data = dataset.getData(zoom, tileX, tileY);
        RoadBvh bvh = data.roads();

        double radiusDegrees = defaultRoadRadius / metersPerDegree;
        double radiusSq = radiusDegrees * radiusDegrees;

        // Query BVH for nearby segments
        long[] found = { 0 }; // Use array to bypass effectively final restriction
        String[] nearestHighway = { null };
        double[] minDistanceSq = { Double.MAX_VALUE };

        bvh.forEachIntersecting(lon - radiusDegrees, lon + radiusDegrees, lat - radiusDegrees, lat + radiusDegrees,
                segment -> {
                    double distSq = segment.distSq(lon, lat);
                    if (distSq < radiusSq && distSq < minDistanceSq[0]) {
                        minDistanceSq[0] = distSq;
                        nearestHighway[0] = segment.highway();
                        found[0] = 1;
                    }
                });

        if (found[0] == 1) {
            return new RoadSample(true, nearestHighway[0]);
        }

        return RoadSample.NOT_ROAD;
    }

    public record RoadSample(boolean isRoad, String highway) {
        public static final RoadSample UNKNOWN = new RoadSample(false, null);
        public static final RoadSample NOT_ROAD = new RoadSample(false, null);
    }
}
