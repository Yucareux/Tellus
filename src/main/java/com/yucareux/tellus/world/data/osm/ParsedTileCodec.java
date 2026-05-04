package com.yucareux.tellus.world.data.osm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ParsedTileCodec {
   private static final int MAGIC_ROAD = 1413827666;
   private static final int MAGIC_WATER = 1465349234;
   private static final int MAGIC_BUILDING = 1112428356;
   private static final int MAGIC_SAND = 1396787524;
   private static final int ROAD_VERSION = 3;
   private static final int WATER_VERSION = 1;
   private static final int BUILDING_VERSION = 5;
   private static final int SAND_VERSION = 1;
   private static final int MAX_FEATURES = 100000;
   private static final int MAX_POINTS_PER_FEATURE = 100000;

   private ParsedTileCodec() {
   }

   static OverpassRoadTile readRoadTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_ROAD) {
            throw new IOException("Invalid road tile magic");
         }

         int version = input.readInt();
         if (version != ROAD_VERSION) {
            throw new IOException("Unsupported road tile version " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "road feature");
         List<RoadFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long wayId = input.readLong();
            int classOrdinal = Byte.toUnsignedInt(input.readByte());
            if (classOrdinal >= RoadClass.values().length) {
               throw new IOException("Invalid road class ordinal " + classOrdinal);
            }

            int modeOrdinal = Byte.toUnsignedInt(input.readByte());
            if (modeOrdinal >= RoadMode.values().length) {
               throw new IOException("Invalid road mode ordinal " + modeOrdinal);
            }

            int bridgeLevel = input.readInt();
            String highwayTag = input.readUTF();
            int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "road point");
            if (pointCount < 2) {
               throw new IOException("Road feature has too few points");
            }

            double[] longitudes = new double[pointCount];
            double[] latitudes = new double[pointCount];

            for (int point = 0; point < pointCount; point++) {
               longitudes[point] = input.readDouble();
               latitudes[point] = input.readDouble();
            }

            features.add(new RoadFeature(wayId, RoadClass.values()[classOrdinal], RoadMode.values()[modeOrdinal], bridgeLevel, highwayTag, longitudes, latitudes));
         }

         return features.isEmpty() ? OverpassRoadTile.empty() : new OverpassRoadTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeRoadTile(Path path, OverpassRoadTile tile) throws IOException {
      Files.createDirectories(path.getParent());
      Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");

      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
         output.writeInt(MAGIC_ROAD);
         output.writeInt(ROAD_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<RoadFeature> features = tile.features();
         output.writeInt(features.size());

         for (RoadFeature feature : features) {
            output.writeLong(feature.wayId());
            output.writeByte(feature.roadClass().ordinal());
            output.writeByte(feature.mode().ordinal());
            output.writeInt(feature.bridgeLevel());
            output.writeUTF(feature.highwayTag());
            int points = feature.pointCount();
            output.writeInt(points);

            for (int point = 0; point < points; point++) {
               output.writeDouble(feature.lonAt(point));
               output.writeDouble(feature.latAt(point));
            }
         }
      }

      Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
   }

   static OsmWaterTile readWaterTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_WATER) {
            throw new IOException("Invalid water tile magic");
         }

         int version = input.readInt();
         if (version != WATER_VERSION) {
            throw new IOException("Unsupported water tile version " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "water feature");
         List<OsmWaterFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long featureId = input.readLong();
            boolean lineGeometry = input.readBoolean();
            boolean oceanHint = input.readBoolean();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "water part");
            if (partCount <= 0) {
               throw new IOException("Water feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "water point");
               int minPoints = lineGeometry ? 2 : 4;
               if (pointCount < minPoints) {
                  throw new IOException("Water feature part has too few points");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(new OsmWaterFeature(featureId, lineGeometry, oceanHint, longitudes, latitudes));
         }

         return features.isEmpty() ? OsmWaterTile.empty() : new OsmWaterTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeWaterTile(Path path, OsmWaterTile tile) throws IOException {
      Files.createDirectories(path.getParent());
      Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");

      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
         output.writeInt(MAGIC_WATER);
         output.writeInt(WATER_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<OsmWaterFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmWaterFeature feature : features) {
            output.writeLong(feature.featureId());
            output.writeBoolean(feature.lineGeometry());
            output.writeBoolean(feature.oceanHint());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      }

      Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
   }

   static OsmSandTile readSandTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_SAND) {
            throw new IOException("Invalid sand tile magic");
         }

         int version = input.readInt();
         if (version != SAND_VERSION) {
            throw new IOException("Unsupported sand tile version " + version);
         }

         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "sand feature");
         List<OsmSandFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            long featureId = input.readLong();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "sand part");
            if (partCount <= 0) {
               throw new IOException("Sand feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "sand point");
               if (pointCount < 4) {
                  throw new IOException("Sand feature part has too few points");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(new OsmSandFeature(featureId, longitudes, latitudes));
         }

         return features.isEmpty() ? OsmSandTile.empty() : new OsmSandTile(features);
      }
   }

   static void writeSandTile(Path path, OsmSandTile tile) throws IOException {
      Files.createDirectories(path.getParent());
      Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");

      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
         output.writeInt(MAGIC_SAND);
         output.writeInt(SAND_VERSION);
         List<OsmSandFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmSandFeature feature : features) {
            output.writeLong(feature.featureId());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      }

      Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
   }

   static OsmBuildingTile readBuildingTile(Path path) throws IOException {
      try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
         int magic = input.readInt();
         if (magic != MAGIC_BUILDING) {
            throw new IOException("Invalid building tile magic");
         }

         int version = input.readInt();
         if (version != BUILDING_VERSION) {
            throw new IOException("Unsupported building tile version " + version);
         }

         double tileSouth = input.readDouble();
         double tileWest = input.readDouble();
         double tileNorth = input.readDouble();
         double tileEast = input.readDouble();
         int featureCount = boundedCount(input.readInt(), MAX_FEATURES, "building feature");
         List<OsmBuildingFeature> features = new ArrayList<>(featureCount);

         for (int i = 0; i < featureCount; i++) {
            int kindOrdinal = Byte.toUnsignedInt(input.readByte());
            if (kindOrdinal >= OsmBuildingKind.values().length) {
               throw new IOException("Invalid building kind ordinal " + kindOrdinal);
            }

            long featureId = input.readLong();
            boolean hasParts = input.readBoolean();
            boolean hasBuildingId = input.readBoolean();
            String buildingId = hasBuildingId ? input.readUTF() : null;
            String buildingClass = readOptionalUtf(input);
            String subtype = readOptionalUtf(input);
            String use = readOptionalUtf(input);
            String name = readOptionalUtf(input);
            int floorCount = input.readInt();
            String roofShape = readOptionalUtf(input);
            int roofLevels = input.readInt();
            double roofHeightMeters = input.readDouble();
            String roofMaterial = readOptionalUtf(input);
            String wallMaterial = readOptionalUtf(input);
            String roofColor = readOptionalUtf(input);
            String wallColor = readOptionalUtf(input);
            double heightMeters = input.readDouble();
            double minHeightMeters = input.readDouble();
            int partCount = boundedCount(input.readInt(), MAX_FEATURES, "building part");
            if (partCount <= 0) {
               throw new IOException("Building feature has no geometry parts");
            }

            double[][] longitudes = new double[partCount][];
            double[][] latitudes = new double[partCount][];

            for (int part = 0; part < partCount; part++) {
               int pointCount = boundedCount(input.readInt(), MAX_POINTS_PER_FEATURE, "building point");
               if (pointCount < 4) {
                  throw new IOException("Building feature part has too few points");
               }

               longitudes[part] = new double[pointCount];
               latitudes[part] = new double[pointCount];

               for (int point = 0; point < pointCount; point++) {
                  longitudes[part][point] = input.readDouble();
                  latitudes[part][point] = input.readDouble();
               }
            }

            features.add(
               new OsmBuildingFeature(
                  OsmBuildingKind.values()[kindOrdinal],
                  featureId,
                  buildingId,
                  hasParts,
                  new OsmBuildingMetadata(
                     buildingClass,
                     subtype,
                     use,
                     name,
                     floorCount,
                     roofShape,
                     roofLevels,
                     roofHeightMeters,
                     roofMaterial,
                     wallMaterial,
                     roofColor,
                     wallColor
                  ),
                  heightMeters,
                  minHeightMeters,
                  longitudes,
                  latitudes
               )
            );
         }

         return features.isEmpty() ? OsmBuildingTile.empty() : new OsmBuildingTile(features, tileSouth, tileWest, tileNorth, tileEast);
      }
   }

   static void writeBuildingTile(Path path, OsmBuildingTile tile) throws IOException {
      Files.createDirectories(path.getParent());
      Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");

      try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tempPath)))) {
         output.writeInt(MAGIC_BUILDING);
         output.writeInt(BUILDING_VERSION);
         output.writeDouble(tile.tileSouth());
         output.writeDouble(tile.tileWest());
         output.writeDouble(tile.tileNorth());
         output.writeDouble(tile.tileEast());
         List<OsmBuildingFeature> features = tile.features();
         output.writeInt(features.size());

         for (OsmBuildingFeature feature : features) {
            output.writeByte(feature.kind().ordinal());
            output.writeLong(feature.featureId());
            output.writeBoolean(feature.hasParts());
            output.writeBoolean(feature.buildingId() != null);
            if (feature.buildingId() != null) {
               output.writeUTF(feature.buildingId());
            }

            writeOptionalUtf(output, feature.metadata().buildingClass());
            writeOptionalUtf(output, feature.metadata().subtype());
            writeOptionalUtf(output, feature.metadata().use());
            writeOptionalUtf(output, feature.metadata().name());
            output.writeInt(feature.metadata().floorCount());
            writeOptionalUtf(output, feature.metadata().roofShape());
            output.writeInt(feature.metadata().roofLevels());
            output.writeDouble(feature.metadata().roofHeightMeters());
            writeOptionalUtf(output, feature.metadata().roofMaterial());
            writeOptionalUtf(output, feature.metadata().wallMaterial());
            writeOptionalUtf(output, feature.metadata().roofColor());
            writeOptionalUtf(output, feature.metadata().wallColor());
            output.writeDouble(feature.heightMeters());
            output.writeDouble(feature.minHeightMeters());
            output.writeInt(feature.partCount());

            for (int part = 0; part < feature.partCount(); part++) {
               int points = feature.pointCount(part);
               output.writeInt(points);

               for (int point = 0; point < points; point++) {
                  output.writeDouble(feature.lonAt(part, point));
                  output.writeDouble(feature.latAt(part, point));
               }
            }
         }
      }

      Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
   }

   private static int boundedCount(int count, int max, String label) throws IOException {
      if (count >= 0 && count <= max) {
         return count;
      } else {
         throw new IOException("Invalid " + label + " count: " + count);
      }
   }

   private static void writeOptionalUtf(DataOutputStream output, String value) throws IOException {
      output.writeBoolean(value != null);
      if (value != null) {
         output.writeUTF(value);
      }
   }

   private static String readOptionalUtf(DataInputStream input) throws IOException {
      return input.readBoolean() ? input.readUTF() : null;
   }
}
