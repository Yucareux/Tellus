package com.yucareux.tellus.world.data.osm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class BuildingBvh {
    private final Node root;

    public BuildingBvh(List<TellusOsmDataset.OsmBuilding> buildings) {
        List<Polygon> polygons = new ArrayList<>();
        for (TellusOsmDataset.OsmBuilding building : buildings) {
            polygons.add(new Polygon(building.shell(), building.holes(), building.attributes()));
        }
        this.root = build(polygons);
    }

    private Node build(List<Polygon> polygons) {
        if (polygons.isEmpty()) {
            return null;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Polygon p : polygons) {
            minX = Math.min(minX, p.minX());
            maxX = Math.max(maxX, p.maxX());
            minZ = Math.min(minZ, p.minZ());
            maxZ = Math.max(maxZ, p.maxZ());
        }

        if (polygons.size() <= 16) {
            return new Leaf(minX, maxX, minZ, maxZ, polygons);
        }

        boolean splitX = (maxX - minX) > (maxZ - minZ);
        double splitCoord = splitX ? (minX + maxX) / 2.0 : (minZ + maxZ) / 2.0;

        List<Polygon> left = new ArrayList<>();
        List<Polygon> right = new ArrayList<>();
        for (Polygon p : polygons) {
            double center = splitX ? (p.minX() + p.maxX()) / 2.0 : (p.minZ() + p.maxZ()) / 2.0;
            if (center < splitCoord) {
                left.add(p);
            } else {
                right.add(p);
            }
        }

        // Handle edge case where all items end up on one side
        if (left.isEmpty() || right.isEmpty()) {
            return new Leaf(minX, maxX, minZ, maxZ, polygons);
        }

        return new Internal(minX, maxX, minZ, maxZ, build(left), build(right));
    }

    public void forEachContaining(double x, double z, Consumer<Polygon> action) {
        if (root != null) {
            root.forEachContaining(x, z, action);
        }
    }

    private abstract static class Node {
        final double minX, maxX, minZ, maxZ;

        Node(double minX, double maxX, double minZ, double maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        boolean containsPoint(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        abstract void forEachContaining(double x, double z, Consumer<Polygon> action);
    }

    private static final class Internal extends Node {
        final Node left, right;

        Internal(double minX, double maxX, double minZ, double maxZ, Node left, Node right) {
            super(minX, maxX, minZ, maxZ);
            this.left = left;
            this.right = right;
        }

        @Override
        void forEachContaining(double x, double z, Consumer<Polygon> action) {
            // A point can be in multiple overlapping bounds, though for buildings usually
            // not
            // Check bounding box first
            if (!containsPoint(x, z)) {
                return;
            }
            if (left != null) {
                left.forEachContaining(x, z, action);
            }
            if (right != null) {
                right.forEachContaining(x, z, action);
            }
        }
    }

    private static final class Leaf extends Node {
        final List<Polygon> polygons;

        Leaf(double minX, double maxX, double minZ, double maxZ, List<Polygon> polygons) {
            super(minX, maxX, minZ, maxZ);
            this.polygons = polygons;
        }

        @Override
        void forEachContaining(double x, double z, Consumer<Polygon> action) {
            if (!containsPoint(x, z)) {
                return;
            }
            for (Polygon p : polygons) {
                if (p.contains(x, z)) {
                    action.accept(p);
                }
            }
        }
    }

    public record Polygon(List<double[]> shell, List<List<double[]>> holes,
            TellusOsmDataset.BuildingAttributes attributes) {
        public double minX() {
            double min = Double.MAX_VALUE;
            for (double[] p : shell) {
                min = Math.min(min, p[0]);
            }
            return min;
        }

        public double maxX() {
            double max = -Double.MAX_VALUE;
            for (double[] p : shell) {
                max = Math.max(max, p[0]);
            }
            return max;
        }

        public double minZ() {
            double min = Double.MAX_VALUE;
            for (double[] p : shell) {
                min = Math.min(min, p[1]);
            }
            return min;
        }

        public double maxZ() {
            double max = -Double.MAX_VALUE;
            for (double[] p : shell) {
                max = Math.max(max, p[1]);
            }
            return max;
        }

        public boolean contains(double x, double z) {
            // Check AABB first
            if (x < minX() || x > maxX() || z < minZ() || z > maxZ()) {
                return false;
            }
            if (!isInsideRing(shell, x, z)) {
                return false;
            }
            for (List<double[]> hole : holes) {
                if (isInsideRing(hole, x, z)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isInsideRing(List<double[]> ring, double x, double z) {
            boolean inside = false;
            int j = ring.size() - 1;
            for (int i = 0; i < ring.size(); i++) {
                double[] pi = ring.get(i);
                double[] pj = ring.get(j);
                if ((pi[1] > z) != (pj[1] > z) &&
                        (x < (pj[0] - pi[0]) * (z - pi[1]) / (pj[1] - pi[1]) + pi[0])) {
                    inside = !inside;
                }
                j = i;
            }
            return inside;
        }
    }
}
