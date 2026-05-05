package com.yucareux.tellus.world.data.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.yucareux.tellus.world.data.osm.OsmBuildingFeature;
import com.yucareux.tellus.world.data.osm.OsmBuildingKind;
import com.yucareux.tellus.world.data.osm.OsmBuildingMetadata;
import com.yucareux.tellus.world.data.osm.RoadClass;
import com.yucareux.tellus.world.data.osm.RoadFeature;
import com.yucareux.tellus.world.data.osm.RoadMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalFeatureAdaptersTest {
   @Test
   void convertsExternalRoadFeatureToTellusFeature() {
      ExternalRoadFeature external = new ExternalRoadFeature(
         "arnis",
         "road-1",
         RoadClass.MAIN,
         RoadMode.BRIDGE,
         1,
         "primary",
         List.of(new GeoPoint(35.0, 139.0), new GeoPoint(35.001, 139.001)),
         Map.of("lanes", "4", "sidewalk", "both", "surface", "asphalt")
      );

      RoadFeature converted = ExternalFeatureAdapters.toTellusRoad(external);

      assertNotEquals(0L, converted.wayId());
      assertEquals(RoadClass.MAIN, converted.roadClass());
      assertEquals(RoadMode.BRIDGE, converted.mode());
      assertEquals(1, converted.bridgeLevel());
      assertEquals("primary", converted.highwayTag());
      assertEquals(4, converted.laneCount());
      assertEquals("asphalt", converted.surfaceTag());
      assertEquals("both", converted.tag("sidewalk"));
      assertEquals(139.0, converted.lonAt(0));
      assertEquals(35.0, converted.latAt(0));
   }

   @Test
   void convertsTellusRoadFeature() {
      RoadFeature road = new RoadFeature(
         42L,
         RoadClass.MAIN,
         RoadMode.BRIDGE,
         2,
         "primary",
         new double[]{139.0, 139.001},
         new double[]{35.0, 35.001},
         Map.of("surface", "gravel")
      );

      ExternalRoadFeature converted = ExternalFeatureAdapters.fromTellusRoad(road);

      assertEquals(ExternalFeatureAdapters.TELLUS_OVERTURE_SOURCE, converted.source());
      assertEquals("42", converted.sourceId());
      assertEquals(RoadClass.MAIN, converted.roadClass());
      assertEquals(RoadMode.BRIDGE, converted.mode());
      assertEquals(2, converted.bridgeLevel());
      assertEquals("primary", converted.highwayTag());
      assertEquals(2, converted.points().size());
      assertEquals(new GeoPoint(35.0, 139.0), converted.points().get(0));
      assertEquals("gravel", converted.tags().get("surface"));
      assertEquals("MAIN", converted.tags().get("road_class"));
      assertEquals("BRIDGE", converted.tags().get("road_mode"));
   }

   @Test
   void convertsExternalBuildingFeatureToTellusFeature() {
      ExternalBuildingFeature external = new ExternalBuildingFeature(
         "arnis",
         "building-1",
         ExternalBuildingKind.FOOTPRINT,
         9.0,
         0.0,
         3,
         List.of(
            List.of(
               new GeoPoint(35.0, 139.0),
               new GeoPoint(35.0, 139.001),
               new GeoPoint(35.001, 139.001),
               new GeoPoint(35.001, 139.0),
               new GeoPoint(35.0, 139.0)
         )
         ),
         Map.of("building_class", "residential", "name", "Example", "building:material", "brick", "roof:colour", "red", "roof:levels", "2")
      );

      OsmBuildingFeature converted = ExternalFeatureAdapters.toTellusBuilding(external);

      assertNotEquals(0L, converted.featureId());
      assertEquals(OsmBuildingKind.FOOTPRINT, converted.kind());
      assertEquals("arnis:building-1", converted.buildingId());
      assertEquals(9.0, converted.heightMeters());
      assertEquals(3, converted.metadata().floorCount());
      assertEquals("residential", converted.metadata().buildingClass());
      assertEquals("Example", converted.metadata().name());
      assertEquals("brick", converted.metadata().wallMaterial());
      assertEquals("red", converted.metadata().roofColor());
      assertEquals(2, converted.metadata().roofLevels());
      assertEquals(139.0, converted.lonAt(0, 0));
      assertEquals(35.0, converted.latAt(0, 0));
   }

   @Test
   void convertsTellusBuildingFeature() {
      OsmBuildingMetadata metadata = new OsmBuildingMetadata(
         "residential",
         "house",
         "home",
         "Example",
         2,
         "gabled",
         1,
         4.5,
         "tile",
         "brick",
         "red",
         "white"
      );
      OsmBuildingFeature building = new OsmBuildingFeature(
         OsmBuildingKind.FOOTPRINT,
         7L,
         "building-7",
         true,
         metadata,
         8.0,
         0.0,
         new double[][]{{139.0, 139.001, 139.001, 139.0, 139.0}},
         new double[][]{{35.0, 35.0, 35.001, 35.001, 35.0}}
      );

      ExternalBuildingFeature converted = ExternalFeatureAdapters.fromTellusBuilding(building);

      assertEquals(ExternalFeatureAdapters.TELLUS_OVERTURE_SOURCE, converted.source());
      assertEquals("7", converted.sourceId());
      assertEquals(ExternalBuildingKind.FOOTPRINT, converted.kind());
      assertEquals(8.0, converted.heightMeters());
      assertEquals(2, converted.floorCount());
      assertEquals(new GeoPoint(35.0, 139.0), converted.outerRing().get(0));
      assertFalse(converted.tags().isEmpty());
      assertEquals("building-7", converted.tags().get("building_id"));
      assertEquals("gabled", converted.tags().get("roof_shape"));
      assertEquals("1", converted.tags().get("roof_levels"));
      assertEquals("4.5", converted.tags().get("roof_height"));
      assertEquals("tile", converted.tags().get("roof_material"));
      assertEquals("brick", converted.tags().get("wall_material"));
      assertEquals("red", converted.tags().get("roof_color"));
      assertEquals("white", converted.tags().get("wall_color"));
   }
}
