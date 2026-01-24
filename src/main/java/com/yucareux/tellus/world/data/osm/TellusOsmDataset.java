package com.yucareux.tellus.world.data.osm;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yucareux.tellus.Tellus;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;

public final class TellusOsmDataset {
    private static final String[] SERVERS = {
            "https://cloud.daporkchop.net/gis/osm/"
    };
    private static final int MAX_CACHE_TILES = 256;
    private static final Gson GSON = new Gson();

    public record OsmData(RoadBvh roads, BuildingBvh buildings) {
    }

    private final LoadingCache<TileKey, OsmData> cache;
    private final Set<TileKey> pendingFetches = Collections.synchronizedSet(new HashSet<>());

    public TellusOsmDataset() {
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHE_TILES)
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build(new CacheLoader<>() {
                    @Override
                    public OsmData load(@NonNull TileKey key) throws Exception {
                        List<OsmFeature> features = new ArrayList<>();
                        List<OsmBuilding> buildings = new ArrayList<>();
                        fetchTile(key, features, buildings);
                        return new OsmData(new RoadBvh(features), new BuildingBvh(buildings));
                    }
                });
    }

    public OsmData getData(int zoom, int x, int y) {
        try {
            return cache.get(new TileKey(zoom, x, y));
        } catch (Exception e) {
            Tellus.LOGGER.debug("Failed to load OSM tile {}/{}/{}: {}", zoom, x, y, e.getMessage());
            return new OsmData(new RoadBvh(Collections.emptyList()), new BuildingBvh(Collections.emptyList()));
        }
    }

    public void prefetchTiles(double blockX, double blockZ, double worldScale, int zoom, int radius) {
        double metersPerDegree = 40075017.0 / 360.0;
        double blocksPerDegree = metersPerDegree / worldScale;
        double lon = blockX / blocksPerDegree;
        double lat = -blockZ / blocksPerDegree;

        double actualTileSize = (1.0 / 64.0) * (1 << zoom);

        int tileX = Mth.floor(lon / actualTileSize);
        int tileY = Mth.floor(lat / actualTileSize);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                triggerFetch(new TileKey(zoom, tileX + dx, tileY + dy));
            }
        }
    }

    private void triggerFetch(TileKey key) {
        if (cache.getIfPresent(key) != null) {
            return;
        }
        if (pendingFetches.add(key)) {
            CompletableFuture.runAsync(() -> {
                try {
                    cache.get(key);
                } catch (Exception e) {
                    Tellus.LOGGER.debug("Failed to prefetch OSM tile {}/{}/{}: {}", key.zoom, key.x, key.y,
                            e.getMessage());
                } finally {
                    pendingFetches.remove(key);
                }
            });
        }
    }

    private void fetchTile(TileKey key, List<OsmFeature> outFeatures, List<OsmBuilding> outBuildings)
            throws IOException {
        String server = SERVERS[0];
        String url = String.format("%s%d/tile/%d/%d.json", server, key.zoom, key.x, key.y);

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);

        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            if (code != HttpURLConnection.HTTP_NOT_FOUND) {
                Tellus.LOGGER.warn("OSM server returned {} for {}", code, url);
            }
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject object = GSON.fromJson(line, JsonObject.class);
                    parseObject(object, outFeatures, outBuildings);
                } catch (Exception e) {
                    Tellus.LOGGER.debug("Failed to parse OSM feature: {}", e.getMessage());
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void parseObject(JsonObject object, List<OsmFeature> outFeatures, List<OsmBuilding> outBuildings) {
        if (!object.has("type") || !object.get("type").getAsString().equals("Feature")) {
            return;
        }

        JsonObject geometry = object.getAsJsonObject("geometry");
        if (geometry == null)
            return;

        JsonObject properties = object.getAsJsonObject("properties");
        if (properties == null)
            return;

        String type = geometry.get("type").getAsString();

        if (properties.has("highway")) {
            List<double[]> points = new ArrayList<>();
            if (type.equals("LineString")) {
                parseLineString(geometry.getAsJsonArray("coordinates"), points);
            } else if (type.equals("MultiLineString")) {
                JsonArray multi = geometry.getAsJsonArray("coordinates");
                for (JsonElement el : multi) {
                    parseLineString(el.getAsJsonArray(), points);
                }
            }
            if (!points.isEmpty()) {
                outFeatures.add(new OsmFeature(properties.get("highway").getAsString(), points));
            }
        }

        if (properties.has("building")) {
            BuildingAttributes attributes = parseBuildingAttributes(properties);
            if (type.equals("Polygon")) {
                parsePolygon(geometry.getAsJsonArray("coordinates"), attributes, outBuildings);
            } else if (type.equals("MultiPolygon")) {
                JsonArray multi = geometry.getAsJsonArray("coordinates");
                for (JsonElement el : multi) {
                    parsePolygon(el.getAsJsonArray(), attributes, outBuildings);
                }
            }
        }
    }

    private BuildingAttributes parseBuildingAttributes(JsonObject properties) {
        float levels = 1.0f;
        if (properties.has("building:levels")) {
            try {
                levels = properties.get("building:levels").getAsFloat();
            } catch (Exception ignored) {
            }
        } else if (properties.has("levels")) {
            try {
                levels = properties.get("levels").getAsFloat();
            } catch (Exception ignored) {
            }
        }

        String color = properties.has("building:colour") ? properties.get("building:colour").getAsString() : null;
        if (color == null && properties.has("colour")) {
            color = properties.get("colour").getAsString();
        }

        String roofShape = properties.has("roof:shape") ? properties.get("roof:shape").getAsString() : "flat";
        String roofColor = properties.has("roof:colour") ? properties.get("roof:colour").getAsString() : null;
        String roofMaterial = properties.has("roof:material") ? properties.get("roof:material").getAsString() : null;

        return new BuildingAttributes(levels, color, roofShape, roofColor, roofMaterial);
    }

    private void parsePolygon(JsonArray coordinates, BuildingAttributes attributes, List<OsmBuilding> outBuildings) {
        if (coordinates.size() == 0) {
            return;
        }
        List<double[]> shell = new ArrayList<>();
        parseLineString(coordinates.get(0).getAsJsonArray(), shell);
        List<List<double[]>> holes = new ArrayList<>();
        for (int i = 1; i < coordinates.size(); i++) {
            List<double[]> hole = new ArrayList<>();
            parseLineString(coordinates.get(i).getAsJsonArray(), hole);
            holes.add(hole);
        }
        if (!shell.isEmpty()) {
            outBuildings.add(new OsmBuilding(shell, holes, attributes));
        }
    }

    private void parseLineString(JsonArray array, List<double[]> out) {
        for (JsonElement el : array) {
            JsonArray pt = el.getAsJsonArray();
            out.add(new double[] { pt.get(0).getAsDouble(), pt.get(1).getAsDouble() });
        }
    }

    private record TileKey(int zoom, int x, int y) {
    }

    public record OsmFeature(String highway, List<double[]> points) {
    }

    public record BuildingAttributes(float levels, String color, String roofShape, String roofColor,
            String roofMaterial) {
    }

    public record OsmBuilding(List<double[]> shell, List<List<double[]>> holes, BuildingAttributes attributes) {
    }
}
