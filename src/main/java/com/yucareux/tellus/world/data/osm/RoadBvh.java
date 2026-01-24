package com.yucareux.tellus.world.data.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class RoadBvh {
    private final Node root;

    public RoadBvh(List<TellusOsmDataset.OsmFeature> features) {
        List<Segment> segments = new ArrayList<>();
        for (TellusOsmDataset.OsmFeature feature : features) {
            List<double[]> points = feature.points();
            for (int i = 0; i < points.size() - 1; i++) {
                double[] p1 = points.get(i);
                double[] p2 = points.get(i + 1);
                segments.add(new Segment(p1[0], p1[1], p2[0], p2[1], feature.highway()));
            }
        }
        this.root = build(segments);
    }

    private Node build(List<Segment> segments) {
        if (segments.isEmpty())
            return null;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Segment s : segments) {
            minX = Math.min(minX, s.minX());
            maxX = Math.max(maxX, s.maxX());
            minZ = Math.min(minZ, s.minZ());
            maxZ = Math.max(maxZ, s.maxZ());
        }

        if (segments.size() <= 16) {
            return new Leaf(minX, maxX, minZ, maxZ, segments);
        }

        boolean splitX = (maxX - minX) > (maxZ - minZ);
        double splitCoord = splitX ? (minX + maxX) / 2.0 : (minZ + maxZ) / 2.0;

        List<Segment> left = new ArrayList<>();
        List<Segment> right = new ArrayList<>();
        for (Segment s : segments) {
            double center = splitX ? (s.minX() + s.maxX()) / 2.0 : (s.minZ() + s.maxZ()) / 2.0;
            if (center < splitCoord)
                left.add(s);
            else
                right.add(s);
        }

        if (left.isEmpty() || right.isEmpty()) {
            return new Leaf(minX, maxX, minZ, maxZ, segments);
        }

        return new Internal(minX, maxX, minZ, maxZ, build(left), build(right));
    }

    public void forEachIntersecting(double minX, double maxX, double minZ, double maxZ, Consumer<Segment> action) {
        if (root != null)
            root.forEachIntersecting(minX, maxX, minZ, maxZ, action);
    }

    private abstract static class Node {
        final double minX, maxX, minZ, maxZ;

        Node(double minX, double maxX, double minZ, double maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        boolean intersects(double minX, double maxX, double minZ, double maxZ) {
            return this.minX <= maxX && this.maxX >= minX && this.minZ <= maxZ && this.maxZ >= minZ;
        }

        abstract void forEachIntersecting(double minX, double maxX, double minZ, double maxZ, Consumer<Segment> action);
    }

    private static final class Internal extends Node {
        final Node left, right;

        Internal(double minX, double maxX, double minZ, double maxZ, Node left, Node right) {
            super(minX, maxX, minZ, maxZ);
            this.left = left;
            this.right = right;
        }

        @Override
        void forEachIntersecting(double minX, double maxX, double minZ, double maxZ, Consumer<Segment> action) {
            if (left != null && left.intersects(minX, maxX, minZ, maxZ))
                left.forEachIntersecting(minX, maxX, minZ, maxZ, action);
            if (right != null && right.intersects(minX, maxX, minZ, maxZ))
                right.forEachIntersecting(minX, maxX, minZ, maxZ, action);
        }
    }

    private static final class Leaf extends Node {
        final List<Segment> segments;

        Leaf(double minX, double maxX, double minZ, double maxZ, List<Segment> segments) {
            super(minX, maxX, minZ, maxZ);
            this.segments = segments;
        }

        @Override
        void forEachIntersecting(double minX, double maxX, double minZ, double maxZ, Consumer<Segment> action) {
            for (Segment s : segments) {
                if (s.minX() <= maxX && s.maxX() >= minX && s.minZ() <= maxZ && s.maxZ() >= minZ) {
                    action.accept(s);
                }
            }
        }
    }

    public record Segment(double x1, double z1, double x2, double z2, String highway) {
        public double minX() {
            return Math.min(x1, x2);
        }

        public double maxX() {
            return Math.max(x1, x2);
        }

        public double minZ() {
            return Math.min(z1, z2);
        }

        public double maxZ() {
            return Math.max(z1, z2);
        }

        public double distSq(double px, double py) {
            double l2 = (x1 - x2) * (x1 - x2) + (z1 - z2) * (z1 - z2);
            if (l2 == 0)
                return (px - x1) * (px - x1) + (py - z1) * (py - z1);
            double t = ((px - x1) * (x2 - x1) + (py - z1) * (z2 - z1)) / l2;
            t = Math.max(0, Math.min(1, t));
            double dx = px - (x1 + t * (x2 - x1));
            double dz = py - (z1 + t * (z2 - z1));
            return dx * dx + dz * dz;
        }
    }
}
