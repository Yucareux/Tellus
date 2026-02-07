package com.yucareux.tellus.integration.distant_horizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.yucareux.tellus.legacy.backend.GeoChunk;
import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.earth.EarthAttachments;
import com.yucareux.tellus.legacy.backend.earth.EarthLayers;
import com.yucareux.tellus.legacy.backend.earth.cover.LegacyCover;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import com.yucareux.tellus.legacy.backend.projection.Projection;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;
import com.yucareux.tellus.legacy.backend.raster.ShortRaster;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import com.yucareux.tellus.legacy.backend.earth.EarthTiles;
import com.yucareux.tellus.legacy.backend.projection.cylindrical.Equirectangular;
import com.yucareux.tellus.legacy.backend.loader.ConcurrencyLimiter;
import com.yucareux.tellus.legacy.backend.tile.GuavaTileCache;
import com.yucareux.tellus.integration.distant_horizons.TellusLodGenerator.CanopyColumn;
import com.yucareux.tellus.integration.distant_horizons.TellusLodGenerator.CanopyProfile;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Paths;
import java.util.concurrent.ForkJoinPool;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class LegacyLodGenerator implements IDhApiWorldGenerator {

    private final IDhApiLevelWrapper levelWrapper;
    private final EarthLayers earthLayers;
    private final Projection projection;
    private final EarthGeneratorSettings settings;
    private final TellusKoppenSource koppenSource;
    private final EarthBiomeSource biomeSource;
    private final ThreadLocal<WrapperCache> wrapperCache;

    private final int minY;
    private final int maxY;

    public LegacyLodGenerator(final IDhApiLevelWrapper levelWrapper, final EarthChunkGenerator generator) {
        this.levelWrapper = levelWrapper;
        this.settings = generator.settings();

        // Resolve height limits
        final EarthGeneratorSettings.HeightLimits limits = EarthGeneratorSettings.resolveHeightLimits(settings);
        this.minY = limits.minY();
        this.maxY = limits.minY() + limits.height() - 1;

        // Correct metersPerBlock for Legacy datasets
        // Legacy datasets (geo3) are roughly 111km per 360 degrees,
        // worldScale is blocks per 360 degrees (Equatorial circumference)
        // Equirectangular projection expects metersPerBlock.
        this.projection = new Equirectangular(settings.worldScale());

        final EarthTiles.Config config = new EarthTiles.Config(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
                new ConcurrencyLimiter(16),
                Paths.get("tellus_cache", "legacy"),
                ForkJoinPool.commonPool(),
                ForkJoinPool.commonPool());
        final EarthTiles tiles = config.create(new GuavaTileCache(Duration.ofMinutes(5), 1000));
        this.earthLayers = EarthLayers.create(tiles, projection, ForkJoinPool.commonPool());
        this.koppenSource = TellusWorldgenSources.koppen();
        this.biomeSource = (EarthBiomeSource) generator.getBiomeSource();
        this.wrapperCache = ThreadLocal.withInitial(() -> new WrapperCache(levelWrapper));
    }

    @Override
    public void preGeneratorTaskStart() {
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return 24;
    }

    @Override
    public CompletableFuture<Void> generateLod(final int chunkPosMinX, final int chunkPosMinZ, final int lodPosX,
            final int lodPosZ, final byte detailLevel, final IDhApiFullDataSource pooledFullDataSource,
            final EDhApiDistantGeneratorMode generatorMode, final ExecutorService worldGeneratorThreadPool,
            final Consumer<IDhApiFullDataSource> resultConsumer) {
        final int lodSizePoints = pooledFullDataSource.getWidthInDataColumns();
        final int lodSizeBlocks = lodSizePoints * (1 << detailLevel);

        final int x0 = SectionPos.sectionToBlockCoord(chunkPosMinX);
        final int z0 = SectionPos.sectionToBlockCoord(chunkPosMinZ);
        final int x1 = x0 + lodSizeBlocks - 1;
        final int z1 = z0 + lodSizeBlocks - 1;
        final GeoView blockSampleView = new GeoView(x0, z0, x1, z1);

        final RasterShape outputShape = new RasterShape(lodSizePoints, lodSizePoints);

        return earthLayers.get(blockSampleView, outputShape).thenAcceptAsync(
                geoChunk -> {
                    if (geoChunk.isPresent()) {
                        buildLod(pooledFullDataSource, geoChunk.get(), x0, z0, detailLevel);
                    }
                    resultConsumer.accept(pooledFullDataSource);
                },
                worldGeneratorThreadPool);
    }

    private void buildLod(final IDhApiFullDataSource output, final GeoChunk geoChunk, final int x0, final int z0,
            final byte detailLevel) {
        final WrapperCache wrappers = wrapperCache.get();
        final VanillaSurfaceLodOutput lodOutput = new VanillaSurfaceLodOutput(output, wrappers,
                levelWrapper.getMinHeight(), levelWrapper.getMaxHeight());

        final Optional<EarthAttachments> earth = EarthAttachments.from(geoChunk);
        if (earth.isEmpty()) {
            return;
        }

        final ShortRaster elevation = earth.get().elevation();
        final EnumRaster<LegacyCover> landCover = earth.get().landCover();
        final int seaLevel = settings.resolveSeaLevel();
        final float heightScale = (float) (settings.terrestrialHeightScale() / projection.idealMetersPerBlock());

        // Simple biome fallback since we don't have the full biome source readily
        // available here without more plumbing
        // But we can approximate or just use Plains for the wrapper if needed, or query
        // the world
        // However, DH expects biomes. Let's try to query the level wrapper if possible,
        // or just use a default.
        // Actually, we should ideally use the biome source from the generator, but we
        // are isolated.
        // For legacy port, let's just use the world's biome if we can't get it easily.
        // Or, we can just use "Plains" as a placeholder since we are controlling the
        // blocks manually.
        // Wait, DH needs biomes for colormaps.
        // We really should pass a biome source. But let's check if we can get it from
        // levelWrapper.
        // The levelWrapper provides access to the level.

        // The levelWrapper provides access to the level.

        // Use a default biome for now as accessing registry from levelWrapper is tricky
        // and we control the blocks manually anyway.
        // We use "minecraft:plains" as a safe default that always exists.
        IDhApiBiomeWrapper defaultBiomeWrapper = wrappers.getBiome("minecraft:plains");

        for (int z = 0; z < elevation.height(); z++) {
            for (int x = 0; x < elevation.width(); x++) {

                // Sample biome at world coordinates
                // We use the center of the LOD column for sampling
                final int worldX = x0 + (x << detailLevel) + (1 << (detailLevel - 1));
                final int worldZ = z0 + (z << detailLevel) + (1 << (detailLevel - 1));

                final Holder<Biome> biomeHolder = biomeSource.getBiomeAtBlock(worldX, worldZ);
                final IDhApiBiomeWrapper biomeWrapper = wrappers.getBiome(biomeHolder);

                lodOutput.beginColumn(x, z, biomeWrapper != null ? biomeWrapper : defaultBiomeWrapper);

                final int elevationValue = elevation.getInt(x, z);
                final int surfaceY = Mth.clamp(Mth.floor((elevationValue * heightScale) + settings.heightOffset()),
                        minY, maxY);
                final LegacyCover cover = landCover.get(x, z);

                // Improved Water Logic
                // 1. Ocean: implied by elevation < seaLevel (Legacy behavior)
                // 2. Upland Water: implied by cover == LegacyCover.WATER but elevation >=
                // seaLevel

                final boolean isOcean = surfaceY < seaLevel;
                final boolean isUplandWater = cover == LegacyCover.WATER && surfaceY >= seaLevel;

                if (isOcean) {
                    // Ocean logic: Fill from bottom up to seaLevel
                    final BlockState underwaterMaterial = getLodUnderwaterMaterial(cover);
                    lodOutput.addLayerUpTo(surfaceY, underwaterMaterial);
                    lodOutput.addLayerUpTo(seaLevel, Blocks.WATER.defaultBlockState());
                } else if (isUplandWater) {
                    // Upland Water logic: Create artificial depth
                    // Elevation data for water bodies usually represents the SURFACE level.
                    // We need to carve out a floor to give it volume for shaders.
                    final int waterTo = surfaceY;
                    final int floorTo = Math.max(minY, waterTo - 25); // 25 blocks depth

                    final BlockState floorMaterial = getLodUnderwaterMaterial(cover);
                    lodOutput.addLayerUpTo(floorTo, floorMaterial);
                    lodOutput.addLayerUpTo(waterTo, Blocks.WATER.defaultBlockState());
                } else {
                    // Dry Land Logic
                    final BlockState surfaceMaterial = getSurfaceMaterial(cover, surfaceY, worldX, worldZ);
                    lodOutput.addLayerUpTo(surfaceY, surfaceMaterial);
                }

                if (isForest(cover)) {
                    final CanopyProfile profile = TellusLodGenerator.getCanopyProfile(biomeHolder);
                    final CanopyColumn canopy = TellusLodGenerator.resolveCanopyColumn(profile, worldX, worldZ,
                            1 << detailLevel);
                    if (canopy != null) {
                        lodOutput.addCanopy(canopy);
                    }
                }

                lodOutput.endColumn();
            }
        }
    }

    private static boolean isForest(final LegacyCover cover) {
        return switch (cover) {
            case TREE_OR_SHRUB_COVER,
                    BROADLEAF_EVERGREEN,
                    BROADLEAF_DECIDUOUS, BROADLEAF_DECIDUOUS_CLOSED, BROADLEAF_DECIDUOUS_OPEN,
                    NEEDLE_LEAF_EVERGREEN, NEEDLE_LEAF_EVERGREEN_CLOSED, NEEDLE_LEAF_EVERGREEN_OPEN,
                    NEEDLE_LEAF_DECIDUOUS, NEEDLE_LEAF_DECIDUOUS_CLOSED, NEEDLE_LEAF_DECIDUOUS_OPEN,
                    MIXED_LEAF_TYPE,
                    TREE_AND_SHRUB_WITH_HERBACEOUS_COVER, HERBACEOUS_COVER_WITH_TREE_AND_SHRUB,
                    SHRUBLAND, SHRUBLAND_EVERGREEN, SHRUBLAND_DECIDUOUS,
                    SPARSE_TREE,
                    FRESH_FLOODED_FOREST, SALINE_FLOODED_FOREST, FLOODED_VEGETATION ->
                true;
            default -> false;
        };
    }

    private BlockState getSurfaceMaterial(final LegacyCover cover, final int surfaceY, final int worldX,
            final int worldZ) {
        // Legacy surface rules
        final double lat = projection.lat(worldX, worldZ);
        final double lon = projection.lon(worldX, worldZ);

        /*
         * if (isSnowyRegion(lat, lon, worldX, worldZ)) {
         * // we don't need biome temp check for now, relying on lat/lon
         * return Blocks.SNOW_BLOCK.defaultBlockState();
         * }
         */

        return switch (cover) {
            case BROADLEAF_DECIDUOUS -> Blocks.GRASS_BLOCK.defaultBlockState();
            case NEEDLE_LEAF_EVERGREEN -> Blocks.PODZOL.defaultBlockState();
            case BROADLEAF_EVERGREEN -> Blocks.GRASS_BLOCK.defaultBlockState();
            case SHRUBLAND -> Blocks.GRASS_BLOCK.defaultBlockState();
            case SPARSE_VEGETATION -> Blocks.COARSE_DIRT.defaultBlockState();
            case BARE_CONSOLIDATED -> Blocks.STONE.defaultBlockState();
            case BARE_UNCONSOLIDATED -> Blocks.SAND.defaultBlockState();
            case URBAN -> Blocks.BRICKS.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case FLOODED_VEGETATION -> Blocks.GRASS_BLOCK.defaultBlockState(); // Wetland approximation
            case IRRIGATED_CROPLAND -> Blocks.GRASS_BLOCK.defaultBlockState(); // Paddy field
            case RAINFED_CROPLAND -> Blocks.FARMLAND.defaultBlockState();
            /*
             * case PERMANENT_SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
             */
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    private BlockState getLodUnderwaterMaterial(final LegacyCover cover) {
        return switch (cover) {
            case BARE_UNCONSOLIDATED, SPARSE_VEGETATION -> Blocks.SAND.defaultBlockState();
            case BARE_CONSOLIDATED, URBAN -> Blocks.STONE.defaultBlockState();
            /*
             * case PERMANENT_SNOW -> Blocks.PACKED_ICE.defaultBlockState();
             */
            default -> Blocks.DIRT.defaultBlockState();
        };
    }

    private boolean isSnowyRegion(final double lat, final double lon, final int worldX, final int worldZ) {
        // First check: Koppen-Geiger climate zones (Bundled offline data)
        final String koppen = koppenSource.sampleRawCode(worldX, worldZ, settings.worldScale());
        if (koppen != null && (koppen.equals("ET") || koppen.equals("EF"))) {
            return true;
        }

        // Second check: Hardcoded fallback regions
        if (Math.abs(lat) > 60)
            return true;
        if (lat >= 27 && lat <= 36 && lon >= 70 && lon <= 100)
            return true; // Himalayas
        if (lat >= 45 && lat <= 48 && lon >= 5 && lon <= 16)
            return true; // Alps
        if (lat >= -56 && lat <= 10 && lon >= -80 && lon <= -60)
            return true; // Andes
        if (lat >= 37 && lat <= 60 && lon >= -120 && lon <= -105)
            return true; // Rockies
        return false;
    }

    private static class VanillaSurfaceLodOutput {
        private final IDhApiFullDataSource output;
        private final WrapperCache wrappers;
        private final int minY;
        private final int absoluteTop;

        private final List<DhApiTerrainDataPoint> columnDataPoints = new ArrayList<>();
        private int columnX;
        private int columnZ;
        @Nullable
        private IDhApiBiomeWrapper columnBiome;
        private int lastLayerTop;

        public VanillaSurfaceLodOutput(final IDhApiFullDataSource output, final WrapperCache wrappers,
                final int minY, final int absoluteTop) {
            this.output = output;
            this.wrappers = wrappers;
            this.minY = minY;
            this.absoluteTop = absoluteTop;
        }

        public void beginColumn(final int x, final int z, final IDhApiBiomeWrapper biome) {
            columnX = x;
            columnZ = z;
            columnBiome = biome;
            lastLayerTop = 0;
        }

        public void addLayerUpTo(final int inclusiveTopY, final BlockState blockState) {
            final int layerTop = Mth.clamp(inclusiveTopY - minY + 1, 0, absoluteTop);
            if (layerTop <= lastLayerTop) {
                return;
            }

            final IDhApiBlockStateWrapper block = wrappers.getBlockState(blockState);
            final IDhApiBiomeWrapper biome = Objects.requireNonNull(columnBiome);

            columnDataPoints.add(DhApiTerrainDataPoint.create(
                    (byte) 0,
                    0, // Block light
                    15, // Sky light
                    lastLayerTop,
                    layerTop,
                    block,
                    biome));
            lastLayerTop = layerTop;
        }

        public void endColumn() {
            if (lastLayerTop < absoluteTop) {
                final IDhApiBiomeWrapper biome = Objects.requireNonNull(columnBiome);
                columnDataPoints.add(DhApiTerrainDataPoint.create(
                        (byte) 0,
                        0,
                        15,
                        lastLayerTop,
                        absoluteTop,
                        wrappers.airBlock(),
                        biome));
            }

            output.setApiDataPointColumn(columnX, columnZ, columnDataPoints);
            columnDataPoints.clear();
        }

        public void addCanopy(final CanopyColumn canopyColumn) {
            if (lastLayerTop >= absoluteTop) {
                return;
            }

            int layerTop = lastLayerTop;
            final IDhApiBiomeWrapper biome = Objects.requireNonNull(columnBiome);

            if (canopyColumn.trunkHeight > 0 && canopyColumn.trunkBlock != null) {
                final int trunkTop = Math.min(absoluteTop, layerTop + canopyColumn.trunkHeight);
                if (trunkTop > layerTop) {
                    final IDhApiBlockStateWrapper trunkBlock = wrappers.getBlockState(canopyColumn.trunkBlock);
                    columnDataPoints.add(
                            DhApiTerrainDataPoint.create((byte) 0, 0, TellusLodGenerator.CANOPY_MAX_LIGHT, layerTop,
                                    trunkTop, trunkBlock,
                                    biome));
                    layerTop = trunkTop;
                }
            }

            if (canopyColumn.leafLift > 0) {
                final int liftTop = Math.min(absoluteTop, layerTop + canopyColumn.leafLift);
                if (liftTop > layerTop) {
                    columnDataPoints.add(
                            DhApiTerrainDataPoint.create((byte) 0, 0, TellusLodGenerator.CANOPY_MAX_LIGHT, layerTop,
                                    liftTop,
                                    wrappers.airBlock(), biome));
                    layerTop = liftTop;
                }
            }

            if (canopyColumn.leavesHeight > 0 && canopyColumn.leavesBlock != null) {
                final int canopyTop = Math.min(absoluteTop, layerTop + canopyColumn.leavesHeight);
                if (canopyTop > layerTop) {
                    final IDhApiBlockStateWrapper canopyBlock = wrappers.getBlockState(canopyColumn.leavesBlock);
                    columnDataPoints.add(
                            DhApiTerrainDataPoint.create((byte) 0, 0, TellusLodGenerator.CANOPY_MAX_LIGHT, layerTop,
                                    canopyTop, canopyBlock,
                                    biome));
                    layerTop = canopyTop;
                }
            }

            lastLayerTop = layerTop;
        }
    }

    private static class WrapperCache {
        private final IDhApiLevelWrapper levelWrapper;
        private final IDhApiBlockStateWrapper airBlock;
        @Nullable
        private final IDhApiBiomeWrapper defaultBiome;
        private final Map<BlockState, IDhApiBlockStateWrapper> blockStates = new IdentityHashMap<>();
        private final Map<String, IDhApiBiomeWrapper> biomeCache = new HashMap<>();
        private final Map<Holder<Biome>, IDhApiBiomeWrapper> holderBiomeCache = new HashMap<>();

        private WrapperCache(final IDhApiLevelWrapper levelWrapper) {
            this.levelWrapper = levelWrapper;
            airBlock = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            defaultBiome = lookupBiomeById("minecraft:the_void");
        }

        public IDhApiBlockStateWrapper airBlock() {
            return airBlock;
        }

        public IDhApiBlockStateWrapper getBlockState(final BlockState blockState) {
            return blockStates.computeIfAbsent(blockState, this::lookupBlockState);
        }

        private IDhApiBlockStateWrapper lookupBlockState(final BlockState blockState) {
            return DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new BlockState[] { blockState }, levelWrapper);
        }

        public IDhApiBiomeWrapper getBiome(final Holder<Biome> biome) {
            return holderBiomeCache.computeIfAbsent(biome, this::lookupBiomeByHolder);
        }

        @Nullable
        private IDhApiBiomeWrapper lookupBiomeByHolder(final Holder<Biome> biome) {
            return biome.unwrapKey().map(key -> getBiome(key.identifier().toString())).orElse(null);
        }

        public IDhApiBiomeWrapper getBiome(final String biomeId) {
            return biomeCache.computeIfAbsent(biomeId, this::lookupBiomeById);
        }

        @Nullable
        private IDhApiBiomeWrapper lookupBiomeById(final String biomeId) {
            try {
                return DhApi.Delayed.wrapperFactory.getBiomeWrapper(biomeId, levelWrapper);
            } catch (final IOException ignored) {
                return null;
            }
        }
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return false;
    }

    @Override
    public void close() {
    }
}
