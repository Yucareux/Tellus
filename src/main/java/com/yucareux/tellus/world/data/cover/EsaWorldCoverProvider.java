package com.yucareux.tellus.world.data.cover;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.Tellus;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;

public class EsaWorldCoverProvider implements LandCoverProvider {
    private static final String ENDPOINT = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map";
    private static final String TILE_PATTERN = "ESA_WorldCover_10m_2021_v200_%s_Map.tif";
    private static final int TILE_DEGREES = 3;
    private static final int MAX_CACHE_TILES = intProperty("tellus.landcover.cacheTiles", 64);
    private static final double MIN_LAT = -60.0;
    private static final double MAX_LAT = 84.0;
    private static final double MIN_LON = -180.0;
    private static final double MAX_LON = 180.0;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;

    private final Path cacheRoot;
    private final LoadingCache<TileKey, GeoTiffTile> cache;

    public EsaWorldCoverProvider() {
        this.cacheRoot = FabricLoader.getInstance().getGameDir().resolve("tellus/cache/worldcover2021");
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHE_TILES)
                .removalListener(notification -> {
                    GeoTiffTile tile = (GeoTiffTile) notification.getValue();
                    if (tile != null) {
                        tile.close();
                    }
                })
                .build(new CacheLoader<>() {
                    @Override
                    public GeoTiffTile load(TileKey key) throws Exception {
                        return loadTile(key);
                    }
                });
    }

    @Override
    public int sample(double lon, double lat) {
        TileKey key = tileKeyForLonLat(lon, lat);
        if (key == null) {
            return 0;
        }
        GeoTiffTile tile = getTile(key);
        return tile.sample(lon, lat);
    }

    @Override
    public void prefetch(double minLat, double minLon, double maxLat, double maxLon) {
        int startLat = (int) Math.floor(minLat / TILE_DEGREES) * TILE_DEGREES;
        int endLat = (int) Math.floor(maxLat / TILE_DEGREES) * TILE_DEGREES;
        int startLon = (int) Math.floor(minLon / TILE_DEGREES) * TILE_DEGREES;
        int endLon = (int) Math.floor(maxLon / TILE_DEGREES) * TILE_DEGREES;

        for (int lat = startLat; lat <= endLat; lat += TILE_DEGREES) {
            if (lat < MIN_LAT || lat > MAX_LAT)
                continue;
            for (int lon = startLon; lon <= endLon; lon += TILE_DEGREES) {
                if (lon < MIN_LON || lon > MAX_LON)
                    continue;
                prefetchTile(new TileKey(lat, lon));
            }
        }
    }

    private GeoTiffTile getTile(TileKey key) {
        try {
            return this.cache.get(key);
        } catch (Exception e) {
            Tellus.LOGGER.warn("Failed to load ESA land cover tile {}", key, e);
            return GeoTiffTile.MISSING;
        }
    }

    private void prefetchTile(TileKey key) {
        if (this.cache.getIfPresent(key) != null) {
            return;
        }
        try {
            this.cache.get(key);
        } catch (Exception e) {
            Tellus.LOGGER.debug("Failed to prefetch ESA land cover tile {}", key, e);
        }
    }

    public java.util.List<String> getActiveTileInfo() {
        java.util.List<String> info = new java.util.ArrayList<>();
        var snapshot = this.cache.asMap();
        if (snapshot.isEmpty()) {
            return info;
        }
        info.add("ESA WorldCover (10m):");
        for (var entry : snapshot.entrySet()) {
            TileKey key = entry.getKey();
            String url = String.format("%s/%s", ENDPOINT, key.fileName());
            info.add("  " + url);
        }
        return info;
    }

    private GeoTiffTile loadTile(TileKey key) throws IOException {
        Path cachePath = this.cacheRoot.resolve(key.fileName());
        if (Files.exists(cachePath)) {
            return GeoTiffTile.open(cachePath);
        }
        byte[] data = downloadTile(key);
        if (data == null) {
            return GeoTiffTile.MISSING;
        }
        cacheTile(cachePath, data);
        return GeoTiffTile.open(cachePath);
    }

    private byte[] downloadTile(TileKey key) throws IOException {
        URI uri = URI.create(String.format("%s/%s", ENDPOINT, key.fileName()));
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
        if (connection.getResponseCode() == 404) {
            return null;
        }
        try (InputStream input = connection.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private void cacheTile(Path cachePath, byte[] data) {
        try {
            Files.createDirectories(cachePath.getParent());
            Path tempPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
            Files.write(tempPath, data);
            Files.move(tempPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Tellus.LOGGER.warn("Failed to cache ESA land cover tile {}", cachePath, e);
        }
    }

    private static TileKey tileKeyForLonLat(double lon, double lat) {
        if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
            return null;
        }
        int tileLat = (int) Math.floor(lat / TILE_DEGREES) * TILE_DEGREES;
        int tileLon = (int) Math.floor(lon / TILE_DEGREES) * TILE_DEGREES;
        return new TileKey(tileLat, tileLon);
    }

    private static int intProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record TileKey(int lat, int lon) {
        String fileName() {
            return String.format(Locale.ROOT, TILE_PATTERN, formatLatLon(this.lat, this.lon));
        }

        private static String formatLatLon(int lat, int lon) {
            char latPrefix = lat >= 0 ? 'N' : 'S';
            char lonPrefix = lon >= 0 ? 'E' : 'W';
            int latAbs = Math.abs(lat);
            int lonAbs = Math.abs(lon);
            return String.format(Locale.ROOT, "%c%02d%c%03d", latPrefix, latAbs, lonPrefix, lonAbs);
        }
    }
}
