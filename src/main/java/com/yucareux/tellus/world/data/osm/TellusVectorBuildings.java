package com.yucareux.tellus.world.data.osm;

import net.minecraft.util.Mth;

public final class TellusVectorBuildings {
    private static final double EQUATOR_CIRCUMFERENCE = 40075017.0;
    private final TellusOsmDataset dataset;

    public TellusVectorBuildings(TellusOsmDataset dataset) {
        this.dataset = dataset;
    }

    public BuildingSample sample(double blockX, double blockZ, double worldScale, int detailLevel) {
        if (worldScale <= 0) {
            return BuildingSample.NONE;
        }

        double metersPerDegree = EQUATOR_CIRCUMFERENCE / 360.0;
        double blocksPerDegree = metersPerDegree / worldScale;
        double lon = blockX / blocksPerDegree;
        double lat = -blockZ / blocksPerDegree;

        // Use same zoom logic as roads for synchronization
        int zoom = TellusVectorRoads.detailLevelToZoom(detailLevel);
        double baseTileSize = 1.0 / 64.0;
        double actualTileSize = baseTileSize * (1 << zoom);

        int tileX = Mth.floor(lon / actualTileSize);
        int tileY = Mth.floor(lat / actualTileSize);

        TellusOsmDataset.OsmData data = dataset.getData(zoom, tileX, tileY);
        // Note: We use getData to retrieve the combined data, but we only access
        // buildings here.
        // Since we use the same cache, road sampling will reuse this data entry.

        // Check if point is inside any building polygon
        final BuildingBvh.Polygon[] found = { null };
        data.buildings().forEachContaining(lon, lat, polygon -> found[0] = polygon);

        if (found[0] != null) {
            return new BuildingSample(true, found[0].attributes());
        }
        return BuildingSample.NONE;
    }

    public record BuildingSample(boolean isBuilding, TellusOsmDataset.BuildingAttributes attributes) {
        public static final BuildingSample NONE = new BuildingSample(false, null);
    }
}
