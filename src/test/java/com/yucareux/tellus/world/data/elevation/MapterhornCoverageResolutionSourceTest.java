package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import io.github.sebasbaumh.mapbox.vectortile.VectorTile.Tile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapterhornCoverageResolutionSourceTest {
   private static final String ZURICH_TILE_BASE64 =
      "GoUBCghjb3ZlcmFnZRIYCAoSAgAAGAMiDgl/fxqAQgAAgEL/QQAPEhgIChICAAEYAyIOCX9/GoBCAACAQv9BAA8S"
         + "GAgKEgIAAhgDIg4Jf38agEIAAIBC/0EADxoGc291cmNlIgYKBGNoemgiBwoFZ2xvMzAiDQoLc3dpc3NhbHRpM2QogCB4Ag==";

   @Test
   void bundledCatalogContainsCurrentMapterhornSourceResolutions() throws IOException {
      try (InputStream input = MapterhornCoverageResolutionSourceTest.class.getResourceAsStream(
         "/tellus/elevation/mapterhorn_source_resolutions.json"
      )) {
         MapterhornCoverageResolutionSource.SourceCatalog catalog = MapterhornCoverageResolutionSource.readSourceCatalog(input);

         assertEquals("0.0.11", catalog.version());
         assertEquals(134, catalog.sourceResolutions().size());
         assertEquals(0.25, catalog.sourceResolutions().get("chzh"));
         assertEquals(1.0, catalog.sourceResolutions().get("at1"));
         assertEquals(30.0, catalog.sourceResolutions().get("glo30"));
      }
   }

   @Test
   void coverageTileChoosesBestNativeResolutionFromOverlappingSources() throws IOException {
      byte[] payload = coverageTile("glo30", "swissalti3d", "chzh");
      Map<String, Double> resolutions = Map.of("glo30", 30.0, "swissalti3d", 0.5, "chzh", 0.25);

      MapterhornCoverageResolutionSource.CoverageTile tile = MapterhornCoverageResolutionSource.decodeCoverageTile(
         payload, resolutions
      );

      assertTrue(tile.available());
      assertEquals(0.25, tile.sample(0.5, 0.5));
   }

   @Test
   void officialZurichCoverageTileResolvesQuarterMeterSource() throws IOException {
      byte[] payload = Base64.getDecoder().decode(ZURICH_TILE_BASE64);
      Map<String, Double> resolutions = Map.of("glo30", 30.0, "swissalti3d", 0.5, "chzh", 0.25);

      MapterhornCoverageResolutionSource.CoverageTile tile = MapterhornCoverageResolutionSource.decodeCoverageTile(
         payload, resolutions
      );
      MapterhornCoverageResolutionSource.TilePosition position = MapterhornCoverageResolutionSource.tilePosition(
         8.5417, 47.3769, 14
      );

      assertEquals(new MapterhornCoverageResolutionSource.TileKey(14, 8580, 5737), position.key());
      assertEquals(0.25, tile.sample(position.localXFraction(), position.localYFraction()));
   }

   @Test
   void localCoverageCacheDrivesBlockCoordinateLookup(@TempDir Path gameDir) throws IOException {
      Map<String, Double> resolutions = Map.of("glo30", 30.0, "swissalti3d", 0.5, "chzh", 0.25);
      MapterhornCoverageResolutionSource.SourceCatalog catalog = new MapterhornCoverageResolutionSource.SourceCatalog(
         "test", resolutions
      );
      Path tilePath = gameDir.resolve("tellus/cache/elevation-mapterhorn-coverage/test/14/8580_5737.mvt");
      Files.createDirectories(tilePath.getParent());
      Files.write(tilePath, Base64.getDecoder().decode(ZURICH_TILE_BASE64));
      MapterhornCoverageResolutionSource source = new MapterhornCoverageResolutionSource(catalog, gameDir);
      double worldScale = 1.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      double blockX = projection.lonToBlockX(8.5417);
      double blockZ = projection.latToBlockZ(47.3769);

      assertEquals(0.25, source.lookupResolutionMetersLocalOnly(blockX, blockZ, projection, 2.0));
   }

   @Test
   void coverageZoomTracksPreviewResolutionAndStaysBounded() {
      assertEquals(14, MapterhornCoverageResolutionSource.coverageZoom(2.0));
      assertEquals(12, MapterhornCoverageResolutionSource.coverageZoom(20.0));
      assertEquals(9, MapterhornCoverageResolutionSource.coverageZoom(200.0));
      assertEquals(0, MapterhornCoverageResolutionSource.coverageZoom(Double.NaN));
   }

   private static byte[] coverageTile(String... sources) {
      Tile.Layer.Builder layer = Tile.Layer.newBuilder()
         .setVersion(2)
         .setName("coverage")
         .setExtent(4096)
         .addKeys("source");
      List<Integer> geometry = List.of(
         command(1, 1), zigZag(0), zigZag(0),
         command(2, 3), zigZag(4096), zigZag(0), zigZag(0), zigZag(4096), zigZag(-4096), zigZag(0),
         command(7, 1)
      );
      for (int i = 0; i < sources.length; i++) {
         layer.addValues(Tile.Value.newBuilder().setStringValue(sources[i]));
         layer.addFeatures(
            Tile.Feature.newBuilder()
               .setType(Tile.GeomType.POLYGON)
               .addAllTags(List.of(0, i))
               .addAllGeometry(geometry)
         );
      }
      return Tile.newBuilder().addLayers(layer).build().toByteArray();
   }

   private static int command(int id, int count) {
      return count << 3 | id;
   }

   private static int zigZag(int value) {
      return value << 1 ^ value >> 31;
   }
}
