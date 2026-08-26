package com.yucareux.tellus.world.data.cover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.WorldProjection;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class WorldCoverCogSourceTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void addressesOfficialThreeDegreeTilesAcrossNegativeCoordinates() {
      assertEquals(
         "N36W120",
         WorldCoverCogSource.SourceTileKey.forLonLat(-119.5332, 37.7459).code()
      );
      assertEquals(
         "S03W003",
         WorldCoverCogSource.SourceTileKey.forLonLat(-0.1, -0.1).code()
      );
      assertEquals(
         "N00E177",
         WorldCoverCogSource.SourceTileKey.forLonLat(180.0, 0.0).code()
      );
      assertEquals(
         "S60E000",
         WorldCoverCogSource.SourceTileKey.forLonLat(0.0, -60.0).code()
      );
      assertNull(WorldCoverCogSource.SourceTileKey.forLonLat(0.0, 84.0));
      assertNull(WorldCoverCogSource.SourceTileKey.forLonLat(0.0, -60.0001));
   }

   @Test
   void selectsTheFinestOverviewNoCoarserThanTheRequestedSample() {
      assertEquals(1, WorldCoverCogSource.selectOverviewFactor(1.0));
      assertEquals(1, WorldCoverCogSource.selectOverviewFactor(19.999));
      assertEquals(2, WorldCoverCogSource.selectOverviewFactor(20.0));
      assertEquals(4, WorldCoverCogSource.selectOverviewFactor(40.0));
      assertEquals(8, WorldCoverCogSource.selectOverviewFactor(80.0));
      assertEquals(16, WorldCoverCogSource.selectOverviewFactor(160.0));
      assertEquals(32, WorldCoverCogSource.selectOverviewFactor(320.0));
      assertEquals(64, WorldCoverCogSource.selectOverviewFactor(640.0));
      assertEquals(64, WorldCoverCogSource.selectOverviewFactor(10_000.0));
   }

   @Test
   void parsesClassicTiffIfdChainIncludingInlineSingleBlockRanges() throws Exception {
      byte[] cog = syntheticCog();
      WorldCoverCogSource.CogMetadata metadata = WorldCoverCogSource.CogMetadata.read(
         (offset, length) -> range(cog, offset, length)
      );

      assertFalse(metadata.missing());
      assertEquals(2, metadata.levels().size());
      WorldCoverCogSource.CogLevel nativeLevel = metadata.levelForFactor(1);
      assertEquals(4, nativeLevel.width());
      assertEquals(4, nativeLevel.height());
      assertEquals(4, nativeLevel.tileOffsets().length);
      assertEquals(4, nativeLevel.tileByteCounts().length);
      WorldCoverCogSource.CogLevel overview = metadata.levelForFactor(2);
      assertEquals(2, overview.width());
      assertEquals(1, overview.tileOffsets().length);
      assertTrue(overview.tileOffsets()[0] > nativeLevel.tileOffsets()[0]);
   }

   @Test
   void preloadEnumerationUsesTheSameScaleAwareCogBlocksAsSampling() {
      WorldCoverCogSource source = new WorldCoverCogSource(
         URI.create("https://example.test/worldcover/"),
         this.temporaryDirectory,
         uri -> (offset, length) -> {
            throw new IOException("enumeration must not access the network");
         }
      );
      double worldScale = 1.0;
      WorldProjection projection = WorldProjection.global(worldScale);
      double lon = -119.5332;
      double lat = 37.7459;
      double blockX = projection.lonToBlockX(lon);
      double blockZ = projection.latToBlockZ(lat);

      var nativeBlocks = source.areaBlockKeys(blockX, blockZ, blockX, blockZ, projection, 1.0);
      var coarseBlocks = source.areaBlockKeys(blockX, blockZ, blockX, blockZ, projection, 640.0);

      assertEquals(1, nativeBlocks.size());
      assertEquals("N36W120", nativeBlocks.get(0).tileKey().code());
      assertEquals(1, nativeBlocks.get(0).overviewFactor());
      assertEquals(1, coarseBlocks.size());
      assertEquals(64, coarseBlocks.get(0).overviewFactor());
      assertTrue(source.fullyCoversArea(blockX, blockZ, blockX, blockZ, projection));

      double partiallyCoveredMinBlockZ = projection.latToBlockZ(-70.0);
      double partiallyCoveredMaxBlockZ = projection.latToBlockZ(-50.0);
      assertFalse(
         source.fullyCoversArea(blockX, partiallyCoveredMinBlockZ, blockX, partiallyCoveredMaxBlockZ, projection)
      );
      assertFalse(
         source.areaBlockKeys(
            blockX,
            partiallyCoveredMinBlockZ,
            blockX,
            partiallyCoveredMaxBlockZ,
            projection,
            640.0
         ).isEmpty()
      );
   }

   @Test
   void samplesNativeAndOverviewPixelsAndReopensCompressedBlocksOffline() throws Exception {
      byte[] cog = syntheticCog();
      URI baseUri = URI.create("https://example.test/worldcover/");
      WorldCoverCogSource online = new WorldCoverCogSource(
         baseUri,
         this.temporaryDirectory,
         uri -> (offset, length) -> range(cog, offset, length)
      );

      WorldCoverCogSource.Sample nativeSample = online.sample(
         -119.0, 38.0, 1.0, WorldCoverCogSource.LookupMode.BLOCKING
      );
      WorldCoverCogSource.Sample overviewSample = online.sample(
         -119.0, 38.0, 20.0, WorldCoverCogSource.LookupMode.BLOCKING
      );

      assertTrue(nativeSample.available());
      assertEquals(40, nativeSample.coverClass());
      assertTrue(overviewSample.available());
      assertEquals(10, overviewSample.coverClass());

      WorldCoverCogSource offline = new WorldCoverCogSource(
         baseUri,
         this.temporaryDirectory,
         uri -> (offset, length) -> {
            throw new IOException("network must not be used");
         }
      );
      WorldCoverCogSource.Sample diskSample = offline.sample(
         -119.0, 38.0, 1.0, WorldCoverCogSource.LookupMode.LOCAL_ONLY
      );
      assertTrue(diskSample.available());
      assertEquals(40, diskSample.coverClass());

      WorldCoverCogSource coldMemoryOnly = new WorldCoverCogSource(
         baseUri,
         this.temporaryDirectory.resolve("cold"),
         uri -> (offset, length) -> {
            throw new IOException("network must not be used");
         }
      );
      assertFalse(
         coldMemoryOnly.sample(-119.0, 38.0, 1.0, WorldCoverCogSource.LookupMode.MEMORY_ONLY).available()
      );
   }

   @Test
   @EnabledIfEnvironmentVariable(named = "TELLUS_LIVE_WORLDCOVER_TEST", matches = "true")
   void readsTheOfficialNativeHalfDomePixelThroughHttpRanges() {
      WorldCoverCogSource source = new WorldCoverCogSource(
         URI.create("https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/"),
         this.temporaryDirectory.resolve("live"),
         uri -> new WorldCoverCogSource.HttpRangeAccess(uri, 30_000, 60_000)
      );

      WorldCoverCogSource.Sample sample = source.sample(
         -119.5332, 37.7459, 1.0, WorldCoverCogSource.LookupMode.BLOCKING
      );

      assertTrue(sample.available());
      assertEquals(60, sample.coverClass());
      for (double resolution : new double[]{20.0, 40.0, 80.0, 160.0, 320.0, 640.0}) {
         WorldCoverCogSource.Sample overview = source.sample(
            -119.5332, 37.7459, resolution, WorldCoverCogSource.LookupMode.BLOCKING
         );
         assertTrue(overview.available(), "overview unavailable at " + resolution + " m");
         assertTrue(isWorldCoverClass(overview.coverClass()), "invalid overview class " + overview.coverClass());
      }

      WorldCoverCogSource offline = new WorldCoverCogSource(
         URI.create("https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/"),
         this.temporaryDirectory.resolve("live"),
         uri -> (offset, length) -> {
            throw new IOException("live WorldCover blocks must reopen from disk");
         }
      );
      WorldCoverCogSource.Sample cached = offline.sample(
         -119.5332, 37.7459, 1.0, WorldCoverCogSource.LookupMode.LOCAL_ONLY
      );
      assertTrue(cached.available());
      assertEquals(60, cached.coverClass());
   }

   private static byte[] syntheticCog() throws IOException {
      byte[][] nativeBlocks = new byte[][]{
         deflate(10, 20, 30, 40),
         deflate(50, 60, 70, 80),
         deflate(90, 95, 100, 10),
         deflate(20, 30, 40, 50)
      };
      byte[] overviewBlock = deflate(10, 50, 90, 100);
      int entryCount = 13;
      int ifdSize = 2 + entryCount * 12 + 4;
      int nativeIfdOffset = 8;
      int overviewIfdOffset = nativeIfdOffset + ifdSize;
      int nativeOffsetsArrayOffset = overviewIfdOffset + ifdSize;
      int nativeCountsArrayOffset = nativeOffsetsArrayOffset + 4 * Integer.BYTES;
      int payloadOffset = nativeCountsArrayOffset + 4 * Integer.BYTES;
      int[] nativeOffsets = new int[nativeBlocks.length];
      int cursor = payloadOffset;
      for (int i = 0; i < nativeBlocks.length; i++) {
         nativeOffsets[i] = cursor;
         cursor += nativeBlocks[i].length;
      }
      int overviewOffset = cursor;
      cursor += overviewBlock.length;

      ByteBuffer output = ByteBuffer.allocate(Math.max(64 * 1024, cursor)).order(ByteOrder.LITTLE_ENDIAN);
      output.put((byte)'I').put((byte)'I').putShort((short)42).putInt(nativeIfdOffset);
      output.position(nativeIfdOffset);
      writeIfd(
         output,
         4,
         4,
         2,
         2,
         nativeOffsets.length,
         nativeOffsetsArrayOffset,
         nativeCountsArrayOffset,
         overviewIfdOffset
      );
      output.position(overviewIfdOffset);
      writeIfd(output, 2, 2, 2, 2, 1, overviewOffset, overviewBlock.length, 0);
      output.position(nativeOffsetsArrayOffset);
      for (int offset : nativeOffsets) {
         output.putInt(offset);
      }
      output.position(nativeCountsArrayOffset);
      for (byte[] block : nativeBlocks) {
         output.putInt(block.length);
      }
      for (int i = 0; i < nativeBlocks.length; i++) {
         output.position(nativeOffsets[i]);
         output.put(nativeBlocks[i]);
      }
      output.position(overviewOffset);
      output.put(overviewBlock);
      return output.array();
   }

   private static void writeIfd(
      ByteBuffer output,
      int width,
      int height,
      int tileWidth,
      int tileHeight,
      int tileCount,
      int tileOffsetsValue,
      int tileByteCountsValue,
      int nextIfdOffset
   ) {
      output.putShort((short)13);
      writeLongTag(output, 256, 1, width);
      writeLongTag(output, 257, 1, height);
      writeShortTag(output, 258, 8);
      writeShortTag(output, 259, 8);
      writeShortTag(output, 262, 1);
      writeShortTag(output, 277, 1);
      writeShortTag(output, 284, 1);
      writeShortTag(output, 317, 1);
      writeLongTag(output, 322, 1, tileWidth);
      writeLongTag(output, 323, 1, tileHeight);
      writeLongTag(output, 324, tileCount, tileOffsetsValue);
      writeLongTag(output, 325, tileCount, tileByteCountsValue);
      writeShortTag(output, 339, 1);
      output.putInt(nextIfdOffset);
   }

   private static void writeShortTag(ByteBuffer output, int tag, int value) {
      output.putShort((short)tag);
      output.putShort((short)3);
      output.putInt(1);
      output.putShort((short)value);
      output.putShort((short)0);
   }

   private static void writeLongTag(ByteBuffer output, int tag, int count, int valueOrOffset) {
      output.putShort((short)tag);
      output.putShort((short)4);
      output.putInt(count);
      output.putInt(valueOrOffset);
   }

   private static byte[] deflate(int... values) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
         for (int value : values) {
            output.write(value);
         }
      }
      return bytes.toByteArray();
   }

   private static byte[] range(byte[] source, long offset, int length) throws IOException {
      if (offset < 0L || offset > Integer.MAX_VALUE || length < 0 || offset + length > source.length) {
         throw new EOFException("Synthetic COG range lies outside data");
      }
      return Arrays.copyOfRange(source, (int)offset, (int)offset + length);
   }

   private static boolean isWorldCoverClass(int value) {
      return value == 0
         || value == 10
         || value == 20
         || value == 30
         || value == 40
         || value == 50
         || value == 60
         || value == 70
         || value == 80
         || value == 90
         || value == 95
         || value == 100;
   }
}
