package com.yucareux.tellus.world.data.cover;

import com.yucareux.tellus.Tellus;

public class GlcFcs30Provider implements LandCoverProvider {
    private boolean loggedMissing = false;

    @Override
    public int sample(double lon, double lat) {
        if (!loggedMissing) {
            Tellus.LOGGER.warn("GLC FCS 30 provider not implemented (missing tile source). Returning NO_DATA.");
            loggedMissing = true;
        }
        return 0;
    }

    @Override
    public void prefetch(double minLat, double minLon, double maxLat, double maxLon) {
        // No-op
    }

    public java.util.List<String> getActiveTileInfo() {
        return java.util.Collections.emptyList();
    }
}
