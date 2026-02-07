package com.yucareux.tellus.legacy.backend.raster.reader;

import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.raster.RasterShape;

public enum RasterFilter {
    NONE {
        @Override
        public int evaluate(final int x, final int a, final int b, final int c) {
            return x;
        }

        @Override
        public <T extends IntLikeRaster> void evaluateInPlace(final T input) {
        }
    },
    LEFT {
        @Override
        public int evaluate(final int x, final int a, final int b, final int c) {
            return x + a;
        }
    },
    UP {
        @Override
        public int evaluate(final int x, final int a, final int b, final int c) {
            return x + b;
        }
    },
    AVERAGE {
        @Override
        public int evaluate(final int x, final int a, final int b, final int c) {
            return x + (a + b) / 2;
        }
    },
    PAETH {
        @Override
        public int evaluate(final int x, final int a, final int b, final int c) {
            final int p = a + b - c;
            final int da = Math.abs(a - p);
            final int db = Math.abs(b - p);
            final int dc = Math.abs(c - p);
            if (da < db && da < dc) {
                return x + a;
            } else if (db < dc) {
                return x + b;
            } else {
                return x + c;
            }
        }
    };

    public abstract int evaluate(int x, int a, int b, int c);

    public <T extends IntLikeRaster> void evaluateInPlace(final T input) {
        final RasterShape shape = input.shape();
        for (int y = 0; y < shape.height(); y++) {
            int lastValue = 0;
            for (int x = 0; x < shape.width(); x++) {
                final int value = input.getInt(x, y);
                final int a = lastValue;
                final int b = y > 0 ? input.getInt(x, y - 1) : 0;
                final int c = x > 0 && y > 0 ? input.getInt(x - 1, y - 1) : 0;
                final int evaluatedValue = evaluate(value, a, b, c);
                input.putInt(x, y, evaluatedValue);
                lastValue = evaluatedValue;
            }
        }
    }

    public static RasterFilter byId(final int id) {
        return switch (id) {
            case 1 -> LEFT;
            case 2 -> UP;
            case 3 -> AVERAGE;
            case 4 -> PAETH;
            default -> NONE;
        };
    }
}
