package com.yucareux.tellus.legacy.backend.projection.cylindrical;

import com.yucareux.tellus.legacy.backend.GeoView;
import com.yucareux.tellus.legacy.backend.raster.IntLikeRaster;
import com.yucareux.tellus.legacy.backend.util.Util;

public interface InterpolationMode extends Resampler<IntLikeRaster> {
    InterpolationMode NEAREST = new InterpolationMode() {
        @Override
        public GeoView extend(final GeoView view) {
            return view;
        }

        @Override
        public <V extends IntLikeRaster> void resample(final V source, final V target, final float scaleX,
                final float scaleY, final float offsetX, final float offsetY, final int seedX, final int seedY) {
            for (int targetY = 0; targetY < target.height(); targetY++) {
                for (int targetX = 0; targetX < target.width(); targetX++) {
                    final float sourceX = targetX * scaleX + offsetX;
                    final float sourceY = targetY * scaleY + offsetY;
                    target.putInt(targetX, targetY, source.getInt(Util.floorInt(sourceX), Util.floorInt(sourceY)));
                }
            }
        }
    };

    interface TwoPoint extends InterpolationMode {
        @Override
        default GeoView extend(final GeoView view) {
            return new GeoView(
                    view.x0(),
                    view.z0(),
                    view.x1() + 1,
                    view.z1() + 1);
        }

        @Override
        default <V extends IntLikeRaster> void resample(final V source, final V target, final float scaleX,
                final float scaleY, final float offsetX, final float offsetY, final int seedX, final int seedY) {
            final int sourceHeight = source.height();
            final int targetWidth = target.width();
            final int targetHeight = target.height();
            final float[] intermediate = new float[targetWidth * sourceHeight];
            for (int sourceY = 0; sourceY < sourceHeight; sourceY++) {
                for (int targetX = 0; targetX < targetWidth; targetX++) {
                    final float sourceX = targetX * scaleX + offsetX;
                    final int originX = Util.floorInt(sourceX);
                    final float midX = sourceX - originX;
                    intermediate[targetX + sourceY * targetWidth] = interpolate(
                            source.getInt(originX, sourceY),
                            source.getInt(originX + 1, sourceY),
                            midX);
                }
            }
            for (int targetY = 0; targetY < targetHeight; targetY++) {
                final float sourceY = targetY * scaleY + offsetY;
                final int originY = Util.floorInt(sourceY);
                final float midY = sourceY - originY;
                int i = originY * targetWidth;
                for (int targetX = 0; targetX < targetWidth; targetX++) {
                    target.putInt(targetX, targetY, Util.floorInt(interpolate(
                            intermediate[i],
                            intermediate[i + targetWidth],
                            midY)));
                    i++;
                }
            }
        }

        float interpolate(float a, float b, float x);
    }

    InterpolationMode LINEAR = (TwoPoint) Util::lerp;
    InterpolationMode COSINE = (TwoPoint) (a, b, x) -> Util.lerp(a, b, (1.0f - (float) Math.cos(x * Math.PI)) / 2.0f);

    InterpolationMode CUBIC = new InterpolationMode() {
        @Override
        public GeoView extend(final GeoView view) {
            return new GeoView(
                    view.x0() - 1,
                    view.z0() - 1,
                    view.x1() + 2,
                    view.z1() + 2);
        }

        @Override
        public <V extends IntLikeRaster> void resample(final V source, final V target, final float scaleX,
                final float scaleY, final float offsetX, final float offsetY, final int seedX, final int seedY) {
            final int sourceHeight = source.height();
            final int targetWidth = target.width();
            final int targetHeight = target.height();
            final float[] intermediate = new float[targetWidth * sourceHeight];
            for (int sourceY = 0; sourceY < sourceHeight; sourceY++) {
                for (int targetX = 0; targetX < targetWidth; targetX++) {
                    final float sourceX = targetX * scaleX + offsetX;
                    final int originX = Util.floorInt(sourceX);
                    final float midX = sourceX - originX;
                    intermediate[targetX + sourceY * targetWidth] = interpolate(
                            source.getInt(originX - 1, sourceY),
                            source.getInt(originX, sourceY),
                            source.getInt(originX + 1, sourceY),
                            source.getInt(originX + 2, sourceY),
                            midX);
                }
            }
            for (int targetY = 0; targetY < targetHeight; targetY++) {
                final float sourceY = targetY * scaleY + offsetY;
                final int originY = Util.floorInt(sourceY);
                final float midY = sourceY - originY;
                int i = originY * targetWidth;
                for (int targetX = 0; targetX < targetWidth; targetX++) {
                    target.putInt(targetX, targetY, Util.floorInt(interpolate(
                            intermediate[i - targetWidth],
                            intermediate[i],
                            intermediate[i + targetWidth],
                            intermediate[i + targetWidth * 2],
                            midY)));
                    i++;
                }
            }
        }

        private static float interpolate(final float a, final float b, final float c, final float d, final float x) {
            return b + 0.5f * x * (c - a + x * (2.0f * a - 5.0f * b + 4.0f * c - d + x * (3.0f * (b - c) + d - a)));
        }
    };

    static InterpolationMode choose(final double relativeScale) {
        if (relativeScale < 0.3) {
            return CUBIC;
        } else if (relativeScale < 0.5) {
            return COSINE;
        } else if (relativeScale < 1.0) {
            return LINEAR;
        }
        return NEAREST;
    }
}
