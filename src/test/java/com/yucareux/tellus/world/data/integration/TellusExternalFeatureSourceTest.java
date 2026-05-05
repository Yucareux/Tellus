package com.yucareux.tellus.world.data.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TellusExternalFeatureSourceTest {
   @TempDir
   Path tempDir;

   @Test
   void loadsFeaturesForBlockArea() throws Exception {
      Path featureFile = this.tempDir.resolve("external-features.json");
      Files.writeString(
         featureFile,
         """
         {
           "roads": [
             {
               "source": "arnis",
               "sourceId": "road-1",
               "roadClass": "MAIN",
               "points": [
                 {"lat": 0.0, "lon": 0.0},
                 {"lat": 0.0001, "lon": 0.0001}
               ]
             }
           ],
           "buildings": [
             {
               "source": "arnis",
               "sourceId": "building-1",
               "heightMeters": 6.0,
               "rings": [[
                 {"lat": 0.0, "lon": 0.0},
                 {"lat": 0.0, "lon": 0.0001},
                 {"lat": 0.0001, "lon": 0.0001},
                 {"lat": 0.0001, "lon": 0.0},
                 {"lat": 0.0, "lon": 0.0}
               ]]
             }
           ],
           "areas": [
             {
               "source": "arnis",
               "sourceId": "parking-1",
               "kind": "PARKING",
               "typeTag": "parking",
               "rings": [[
                 {"lat": 0.0, "lon": 0.0},
                 {"lat": 0.0, "lon": 0.0001},
                 {"lat": 0.0001, "lon": 0.0001},
                 {"lat": 0.0001, "lon": 0.0},
                 {"lat": 0.0, "lon": 0.0}
               ]]
             }
           ],
           "lines": [
             {
               "source": "arnis",
               "sourceId": "barrier-1",
               "kind": "BARRIER",
               "typeTag": "fence",
               "points": [
                 {"lat": 0.0, "lon": 0.0},
                 {"lat": 0.0001, "lon": 0.0001}
               ]
             }
           ],
           "points": [
             {
               "source": "arnis",
               "sourceId": "bench-1",
               "kind": "AMENITY",
               "typeTag": "bench",
               "lat": 0.0,
               "lon": 0.0
             }
           ]
         }
         """,
         StandardCharsets.UTF_8
      );
      TellusExternalFeatureSource source = new TellusExternalFeatureSource(featureFile, OverpassExternalFeatureSource.disabled());

      List<RoadFeature> roads = source.roadsForArea(-16, -16, 16, 16, 1.0, 0);
      List<OsmBuildingFeature> buildings = source.buildingsForArea(-16, -16, 16, 16, 1.0, 0);
      List<ExternalAreaFeature> areas = source.cityAreasForArea(-16, -16, 16, 16, 1.0, 0);
      List<ExternalLineFeature> lines = source.cityLinesForArea(-16, -16, 16, 16, 1.0, 0);
      List<ExternalPointFeature> points = source.cityPointsForArea(-16, -16, 16, 16, 1.0, 0);

      assertFalse(roads.isEmpty());
      assertFalse(buildings.isEmpty());
      assertFalse(areas.isEmpty());
      assertFalse(lines.isEmpty());
      assertFalse(points.isEmpty());
      assertEquals("arnis:building-1", buildings.get(0).buildingId());
   }
}
