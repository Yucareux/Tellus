package com.yucareux.tellus.legacy.backend.tile;

public record TileKey(int x, int y) {
    public String path() {
        return x + "/" + y;
    }
}
