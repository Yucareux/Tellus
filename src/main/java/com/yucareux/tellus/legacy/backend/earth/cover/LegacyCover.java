package com.yucareux.tellus.legacy.backend.earth.cover;

import com.mojang.serialization.Codec;
import com.yucareux.tellus.legacy.backend.util.Util;

import java.util.Arrays;

public enum LegacyCover {
    NONE(0, "none"),
    RAINFED_CROPLAND(10, "rainfed_cropland"),
    HERBACEOUS_COVER(11, "herbaceous_cover"),
    TREE_OR_SHRUB_COVER(12, "tree_or_shrub_cover"),
    IRRIGATED_CROPLAND(20, "irrigated_cropland"),
    CROPLAND_WITH_VEGETATION(30, "cropland_with_vegetation"),
    VEGETATION_WITH_CROPLAND(40, "vegetation_with_cropland"),
    BROADLEAF_EVERGREEN(50, "broadleaf_evergreen"),
    BROADLEAF_DECIDUOUS(60, "broadleaf_deciduous"),
    BROADLEAF_DECIDUOUS_CLOSED(61, "broadleaf_deciduous_closed"),
    BROADLEAF_DECIDUOUS_OPEN(62, "broadleaf_deciduous_open"),
    NEEDLE_LEAF_EVERGREEN(70, "needle_leaf_evergreen"),
    NEEDLE_LEAF_EVERGREEN_CLOSED(71, "needle_leaf_evergreen_closed"),
    NEEDLE_LEAF_EVERGREEN_OPEN(72, "needle_leaf_evergreen_open"),
    NEEDLE_LEAF_DECIDUOUS(80, "needle_leaf_deciduous"),
    NEEDLE_LEAF_DECIDUOUS_CLOSED(81, "needle_leaf_deciduous_closed"),
    NEEDLE_LEAF_DECIDUOUS_OPEN(82, "needle_leaf_deciduous_open"),
    MIXED_LEAF_TYPE(90, "mixed_leaf_type"),
    TREE_AND_SHRUB_WITH_HERBACEOUS_COVER(100, "tree_and_shrub_with_herbaceous_cover"),
    HERBACEOUS_COVER_WITH_TREE_AND_SHRUB(110, "herbaceous_cover_with_tree_and_shrub"),
    SHRUBLAND(120, "shrubland"),
    SHRUBLAND_EVERGREEN(121, "shrubland_evergreen"),
    SHRUBLAND_DECIDUOUS(122, "shrubland_deciduous"),
    GRASSLAND(130, "grassland"),
    LICHENS_AND_MOSSES(140, "lichens_and_mosses"),
    SPARSE_VEGETATION(150, "sparse_vegetation"),
    SPARSE_TREE(151, "sparse_tree"),
    SPARSE_SHRUB(152, "sparse_shrub"),
    SPARSE_HERBACEOUS_COVER(153, "sparse_herbaceous_cover"),
    FRESH_FLOODED_FOREST(160, "fresh_flooded_forest"),
    SALINE_FLOODED_FOREST(170, "saline_flooded_forest"),
    FLOODED_VEGETATION(180, "flooded_vegetation"),
    URBAN(190, "urban"),
    BARE(200, "bare"),
    BARE_CONSOLIDATED(201, "bare_consolidated"),
    BARE_UNCONSOLIDATED(202, "bare_unconsolidated"),
    WATER(210, "water"),
    PERMANENT_SNOW(220, "permanent_snow");

    public static final Codec<LegacyCover> CODEC = Util.stringLookupCodec(values(), LegacyCover::getName);

    private static final LegacyCover[] LOOKUP = new LegacyCover[256];

    private final int id;
    private final String name;

    LegacyCover(final int id, final String name) {
        this.id = id;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static LegacyCover byId(final int id) {
        if (id < 0 || id >= LOOKUP.length) {
            return NONE;
        }
        return LOOKUP[id];
    }

    static {
        Arrays.fill(LOOKUP, NONE);
        for (final LegacyCover cover : LegacyCover.values()) {
            LOOKUP[cover.id] = cover;
        }
    }
}
