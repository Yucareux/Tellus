package com.yucareux.tellus.world.data.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class JsonExternalFeatureSourceTest {
   @Test
   void readsAndFiltersExternalFeatures() throws Exception {
      JsonExternalFeatureSource source = JsonExternalFeatureSource.fromReader(new StringReader("""
         {
           "roads": [
             {
               "source": "arnis",
               "sourceId": "road-1",
               "roadClass": "MAIN",
               "mode": "BRIDGE",
               "bridgeLevel": 2,
               "highwayTag": "primary",
               "points": [
                 {"lat": 35.0, "lon": 139.0},
                 {"lat": 35.001, "lon": 139.001}
               ],
               "tags": {"surface": "asphalt"}
             }
           ],
           "buildings": [
             {
               "source": "arnis",
               "sourceId": "building-1",
               "kind": "FOOTPRINT",
               "heightMeters": 8.0,
               "floorCount": 2,
               "rings": [[
                 {"lat": 35.0, "lon": 139.0},
                 {"lat": 35.0, "lon": 139.001},
                 {"lat": 35.001, "lon": 139.001},
                 {"lat": 35.001, "lon": 139.0},
                 {"lat": 35.0, "lon": 139.0}
               ]],
               "tags": {"building": "house"}
             }
           ],
           "areas": [
             {
               "source": "arnis",
               "sourceId": "parking-1",
               "kind": "PARKING",
               "typeTag": "parking",
               "rings": [[
                 {"lat": 35.0, "lon": 139.0},
                 {"lat": 35.0, "lon": 139.001},
                 {"lat": 35.001, "lon": 139.001},
                 {"lat": 35.001, "lon": 139.0},
                 {"lat": 35.0, "lon": 139.0}
               ]],
               "tags": {"amenity": "parking"}
             }
           ],
           "lines": [
             {
               "source": "arnis",
               "sourceId": "barrier-1",
               "kind": "BARRIER",
               "typeTag": "fence",
               "points": [
                 {"lat": 35.0, "lon": 139.0},
                 {"lat": 35.001, "lon": 139.001}
               ],
               "tags": {"barrier": "fence"}
             }
           ],
           "points": [
             {
               "source": "arnis",
               "sourceId": "signal-1",
               "kind": "TRAFFIC_SIGNAL",
               "typeTag": "traffic_signals",
               "lat": 35.0,
               "lon": 139.0,
               "tags": {"highway": "traffic_signals"}
             }
           ]
         }
         """));

      GeoBounds matchingBounds = new GeoBounds(34.999, 138.999, 35.002, 139.002);
      ExternalRoadFeature road = source.roadsForBounds(matchingBounds).get(0);
      ExternalBuildingFeature building = source.buildingsForBounds(matchingBounds).get(0);
      ExternalAreaFeature area = source.areasForBounds(matchingBounds).get(0);
      ExternalLineFeature line = source.linesForBounds(matchingBounds).get(0);
      ExternalPointFeature point = source.pointsForBounds(matchingBounds).get(0);

      assertEquals(RoadClass.MAIN, road.roadClass());
      assertEquals(RoadMode.BRIDGE, road.mode());
      assertEquals("asphalt", road.tags().get("surface"));
      assertEquals(ExternalBuildingKind.FOOTPRINT, building.kind());
      assertEquals(2, building.floorCount());
      assertEquals("house", building.tags().get("building"));
      assertEquals(ExternalAreaKind.PARKING, area.kind());
      assertEquals("parking", area.typeTag());
      assertEquals(ExternalLineKind.BARRIER, line.kind());
      assertEquals("fence", line.typeTag());
      assertEquals(ExternalPointKind.TRAFFIC_SIGNAL, point.kind());
      assertEquals("traffic_signals", point.typeTag());
      assertTrue(source.roadsForBounds(new GeoBounds(0.0, 0.0, 1.0, 1.0)).isEmpty());
      assertTrue(source.buildingsForBounds(new GeoBounds(0.0, 0.0, 1.0, 1.0)).isEmpty());
      assertTrue(source.areasForBounds(new GeoBounds(0.0, 0.0, 1.0, 1.0)).isEmpty());
      assertTrue(source.linesForBounds(new GeoBounds(0.0, 0.0, 1.0, 1.0)).isEmpty());
      assertTrue(source.pointsForBounds(new GeoBounds(0.0, 0.0, 1.0, 1.0)).isEmpty());
   }
}
