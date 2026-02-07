package com.yucareux.tellus.world.data.cover;

import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.net.URI;

public class ModisProvider implements LandCoverProvider {
    // 2022 IGBP Land Cover (Type 1)
    private static final String COG_URL = "https://zenodo.org/record/8266497/files/lc_mcd12q1v061.p1_c_500m_s_20220101_20221231_go_epsg.4326_v20230818.tif";

    private GeoTiffTile cog;

    public ModisProvider() {
        try {
            URI uri = URI.create(COG_URL);
            HttpSeekableByteChannel channel = new HttpSeekableByteChannel(uri);
            this.cog = GeoTiffTile.open(channel);
        } catch (IOException e) {
            Tellus.LOGGER.warn("Failed to initialize MODIS provider: {}", e.getMessage());
            this.cog = GeoTiffTile.MISSING;
        }
    }

    @Override
    public int sample(double lon, double lat) {
        if (cog == GeoTiffTile.MISSING)
            return 0;
        int raw = cog.sample(lon, lat);
        return mapIgbpToEsa(raw);
    }

    @Override
    public void prefetch(double minLat, double minLon, double maxLat, double maxLon) {
        // No prefetching for single COG currently needed as it uses internal caching
    }

    public java.util.List<String> getActiveTileInfo() {
        java.util.List<String> info = new java.util.ArrayList<>();
        if (cog != GeoTiffTile.MISSING) {
            info.add("MODIS MCD12Q1 (500m):");
            info.add("  " + COG_URL);
        }
        return info;
    }

    private static int mapIgbpToEsa(int igbp) {
        return switch (igbp) {
            case 0 -> TellusLandCoverSource.ESA_WATER;
            case 1, 2, 3, 4, 5 -> TellusLandCoverSource.ESA_TREE_COVER;
            case 6, 7 -> TellusLandCoverSource.ESA_SHRUBLAND;
            case 8, 9 -> TellusLandCoverSource.ESA_SHRUBLAND; // Woody savannas/savannas -> shrubland? or grassland?
            case 10 -> TellusLandCoverSource.ESA_GRASSLAND;
            case 11 -> TellusLandCoverSource.ESA_HERBACEOUS_WETLAND;
            case 12 -> TellusLandCoverSource.ESA_CROPLAND;
            case 13 -> TellusLandCoverSource.ESA_BUILT_UP;
            case 14 -> TellusLandCoverSource.ESA_CROPLAND; // Mosaic
            case 15 -> TellusLandCoverSource.ESA_SNOW_ICE;
            case 16 -> TellusLandCoverSource.ESA_BARE_VEGETATION;
            default -> TellusLandCoverSource.ESA_NO_DATA;
        };
    }
}
