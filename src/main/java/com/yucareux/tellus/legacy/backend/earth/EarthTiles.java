package com.yucareux.tellus.legacy.backend.earth;

import com.yucareux.tellus.legacy.backend.earth.cover.LegacyCover;
import com.yucareux.tellus.legacy.backend.layer.LeveledRasterSampler;
import com.yucareux.tellus.legacy.backend.layer.RasterSampler;
import com.yucareux.tellus.legacy.backend.loader.Cacher;
import com.yucareux.tellus.legacy.backend.loader.ConcurrencyLimiter;
import com.yucareux.tellus.legacy.backend.loader.FileCacher;
import com.yucareux.tellus.legacy.backend.loader.HttpLoader;
import com.yucareux.tellus.legacy.backend.loader.Loader;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.Raster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.RasterType;
import com.yucareux.tellus.legacy.backend.raster.ShortRaster;
import com.yucareux.tellus.legacy.backend.raster.reader.RasterFormat;
import com.yucareux.tellus.legacy.backend.raster.reader.RasterReader;
import com.yucareux.tellus.legacy.backend.tile.TileCache;
import com.yucareux.tellus.legacy.backend.tile.TileKey;
import com.yucareux.tellus.legacy.backend.tile.TileMap;
import com.yucareux.tellus.legacy.backend.tile.TiledRasterSampler;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public record EarthTiles(
        LeveledRasterSampler<ShortRaster> elevation,
        LeveledRasterSampler<EnumRaster<LegacyCover>> landCover
) {
    private static final int TILE_SIZE = 1000;
    private static final RasterShape TILE_SHAPE = new RasterShape(TILE_SIZE, TILE_SIZE);
    private static final int ZOOM_BASE = 3;

    private static final String ENDPOINT = "https://terrarium.gegy.dev/geo3";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(1);

    public static class Config {
        private final Loader<URI, byte[]> httpLoader;
        private final Path cacheRoot;
        private final Executor executor;
        private final Executor ioExecutor;

        public Config(final HttpClient httpClient, final ConcurrencyLimiter concurrencyLimiter, final Path cacheRoot, final Executor executor, final Executor ioExecutor) {
            httpLoader = concurrencyLimiter.wrap(new HttpLoader(httpClient, REQUEST_TIMEOUT));
            this.cacheRoot = cacheRoot;
            this.executor = executor;
            this.ioExecutor = ioExecutor;
        }

        public EarthTiles create(final TileCache cache) {
            return new EarthTiles(
                    elevation(cache),
                    landCover(cache)
            );
        }

        private LeveledRasterSampler<ShortRaster> elevation(final TileCache cache) {
            return createLeveledTiledRaster(cache, 0, 6, ShortRaster.TYPE, level -> {
                final Loader<TileKey, byte[]> fileLoader = httpLoader("elevation2", level)
                        .cached(fileCacher("elevation", level));
                return RasterReader.loader(RasterFormat.SHORT, executor)
                        .compose(fileLoader);
            });
        }

        private LeveledRasterSampler<EnumRaster<LegacyCover>> landCover(final TileCache cache) {
            final RasterType<EnumRaster<LegacyCover>> rasterType = EnumRaster.type(LegacyCover.NONE, LegacyCover.CODEC);
            return createLeveledTiledRaster(cache, 0, 4, rasterType, level -> {
                final Loader<TileKey, byte[]> fileLoader = httpLoader("landcover", level)
                        .cached(fileCacher("landcover", level));
                return RasterReader.loader(rasterType, LegacyCover::byId, executor)
                        .compose(fileLoader);
            });
        }

        private <V extends Raster> LeveledRasterSampler<V> createLeveledTiledRaster(final TileCache cache, final int minLevel, final int maxLevel, final RasterType<V> rasterType, final IntFunction<Loader<TileKey, V>> factory) {
            final List<RasterSampler<V>> levels = IntStream.rangeClosed(minLevel, maxLevel).mapToObj(level -> {
                final TileMap<V> map = createTileMap(level, factory.apply(level));
                final TileMap<V> cachedMap = map.cached(cache.createCacher(map));
                return new TiledRasterSampler<>(cachedMap, rasterType, executor);
            }).collect(Collectors.toList());
            return new LeveledRasterSampler<>(levels);
        }

        private <V extends Raster> TileMap<V> createTileMap(final int level, final Loader<TileKey, V> loader) {
            final int countY = (int) Math.floor(Math.pow(ZOOM_BASE, level));
            final int countX = countY * 2;
            return new TileMap<>(countX, countY, TILE_SHAPE, loader);
        }

        private Loader<TileKey, byte[]> httpLoader(final String route, final int level) {
            final String endpoint = ENDPOINT + "/" + route + "/" + level + "/";
            return httpLoader.mapKey(key -> URI.create(endpoint + key.path()));
        }

        private Cacher<TileKey, byte[]> fileCacher(final String name, final int level) {
            final Path sourceRoot = cacheRoot.resolve(name).resolve(String.valueOf(level));
            return new FileCacher(ioExecutor).mapKey(key -> sourceRoot.resolve(key.path()));
        }
    }
}
