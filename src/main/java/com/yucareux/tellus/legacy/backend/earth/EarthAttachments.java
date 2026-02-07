package com.yucareux.tellus.legacy.backend.earth;

import com.yucareux.tellus.legacy.backend.GeoAttachment;
import com.yucareux.tellus.legacy.backend.GeoAttachmentSet;
import com.yucareux.tellus.legacy.backend.GeoChunk;
import com.yucareux.tellus.legacy.backend.earth.cover.LegacyCover;
import com.yucareux.tellus.legacy.backend.raster.EnumRaster;
import com.yucareux.tellus.legacy.backend.raster.ShortRaster;

import java.util.Optional;

public record EarthAttachments(
        ShortRaster elevation,
        EnumRaster<LegacyCover> landCover) {
    public static final GeoAttachment<ShortRaster> ELEVATION = GeoAttachment.register("elevation", ShortRaster.TYPE);
    public static final GeoAttachment<EnumRaster<LegacyCover>> LAND_COVER = GeoAttachment.register("land_cover",
            EnumRaster.type(LegacyCover.NONE, LegacyCover.CODEC));

    public static final GeoAttachmentSet REQUIRED_SET = GeoAttachmentSet.of(
            ELEVATION,
            LAND_COVER);

    public static Optional<EarthAttachments> from(final GeoChunk chunk) {
        return chunk.requireAll(REQUIRED_SET).map(completeChunk -> new EarthAttachments(
                completeChunk.getOrThrow(ELEVATION),
                completeChunk.getOrThrow(LAND_COVER)));
    }
}
