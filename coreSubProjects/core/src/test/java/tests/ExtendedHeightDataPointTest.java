/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

public class ExtendedHeightDataPointTest
{
	private static final int TALL_BOTTOM_Y = 9_000;
	private static final int TALL_TOP_Y = 9_168;

	@Test
	public void fullDataPointRoundTripsExtendedCoordinates() throws DataCorruptedException
	{
		int id = 0x0ABCDEF;
		long dataPoint = FullDataPointUtil.encode(
			id, TALL_TOP_Y - TALL_BOTTOM_Y, TALL_BOTTOM_Y, (byte) 13, (byte) 7);

		Assert.assertEquals(id, FullDataPointUtil.getId(dataPoint));
		Assert.assertEquals(TALL_TOP_Y - TALL_BOTTOM_Y, FullDataPointUtil.getHeight(dataPoint));
		Assert.assertEquals(TALL_BOTTOM_Y, FullDataPointUtil.getBottomY(dataPoint));
		Assert.assertEquals(13, FullDataPointUtil.getBlockLight(dataPoint));
		Assert.assertEquals(7, FullDataPointUtil.getSkyLight(dataPoint));

		long boundary = FullDataPointUtil.encode(
			(int) FullDataPointUtil.ID_MASK,
			1,
			RenderDataPointUtil.MAX_WORLD_Y_SIZE - 1,
			(byte) 15,
			(byte) 15);
		Assert.assertEquals((int) FullDataPointUtil.ID_MASK, FullDataPointUtil.getId(boundary));
		Assert.assertEquals(RenderDataPointUtil.MAX_WORLD_Y_SIZE - 1, FullDataPointUtil.getBottomY(boundary));
	}

	@Test
	public void renderDataPointRoundTripsExtendedCoordinatesAndPreservesFields()
	{
		long dataPoint = RenderDataPointUtil.createDataPoint(
			TALL_TOP_Y, TALL_BOTTOM_Y, 0xFF_FF_7F_AC, 15, 12, 15);

		Assert.assertEquals(TALL_TOP_Y, RenderDataPointUtil.getYMax(dataPoint));
		Assert.assertEquals(TALL_BOTTOM_Y, RenderDataPointUtil.getYMin(dataPoint));
		Assert.assertEquals(15, RenderDataPointUtil.getLightSky(dataPoint));
		Assert.assertEquals(12, RenderDataPointUtil.getLightBlock(dataPoint));
		Assert.assertEquals(15, RenderDataPointUtil.getBlockMaterialId(dataPoint));
		Assert.assertEquals(0xFF_FF_7E_AE, RenderDataPointUtil.getColor(dataPoint));

		long shifted = RenderDataPointUtil.setYMax(RenderDataPointUtil.setYMin(dataPoint, TALL_BOTTOM_Y + 100), TALL_TOP_Y + 100);
		Assert.assertEquals(TALL_TOP_Y + 100, RenderDataPointUtil.getYMax(shifted));
		Assert.assertEquals(TALL_BOTTOM_Y + 100, RenderDataPointUtil.getYMin(shifted));
		Assert.assertEquals(RenderDataPointUtil.getColor(dataPoint), RenderDataPointUtil.getColor(shifted));

		long recolored = RenderDataPointUtil.setBlue(
			RenderDataPointUtil.setGreen(RenderDataPointUtil.setRed(dataPoint, 0), 0), 0);
		Assert.assertEquals(0xFF_00_00_00, RenderDataPointUtil.getColor(recolored));
		Assert.assertEquals(TALL_TOP_Y, RenderDataPointUtil.getYMax(recolored));
		Assert.assertEquals(TALL_BOTTOM_Y, RenderDataPointUtil.getYMin(recolored));
	}

	@Test
	public void stockPackedFullDataConvertsToExtendedLayout() throws DataCorruptedException
	{
		int id = 123_456;
		int height = 123;
		int bottomY = 3_500;
		byte blockLight = 11;
		byte skyLight = 6;
		long stockDataPoint = (long) id
			| (long) height << 32
			| (long) bottomY << 44
			| (long) skyLight << 56
			| (long) blockLight << 60;

		long converted = FullDataPointUtil.fromLegacyDataPoint(stockDataPoint);
		Assert.assertEquals(id, FullDataPointUtil.getId(converted));
		Assert.assertEquals(height, FullDataPointUtil.getHeight(converted));
		Assert.assertEquals(bottomY, FullDataPointUtil.getBottomY(converted));
		Assert.assertEquals(blockLight, FullDataPointUtil.getBlockLight(converted));
		Assert.assertEquals(skyLight, FullDataPointUtil.getSkyLight(converted));
		Assert.assertEquals(stockDataPoint, FullDataPointUtil.toLegacyDataPoint(converted));
	}

	@Test(expected = DataCorruptedException.class)
	public void extendedCoordinateCannotBeWrittenAsStockPackedFullData() throws DataCorruptedException
	{
		long dataPoint = FullDataPointUtil.encode(1, 1, TALL_BOTTOM_Y, (byte) 0, (byte) 15);
		FullDataPointUtil.toLegacyDataPoint(dataPoint);
	}

	@Test
	public void v2StorageRoundTripsExtendedCoordinatesAndReadsPriorForkMarker()
		throws DataCorruptedException, IOException, InterruptedException
	{
		long pos = DhSectionPos.encode((byte) 6, 0, 0);
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int id = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("tall"), new TestBlockStateWrapper("tall"));
		long expected = FullDataPointUtil.encode(
			id, TALL_TOP_Y - TALL_BOTTOM_Y, TALL_BOTTOM_Y, (byte) 4, (byte) 15);

		LongArrayList[] columns = emptyColumns();
		int targetIndex = FullDataSourceV2.relativePosToIndex(10, 10);
		columns[targetIndex].add(expected);
		byte[] generationSteps = new byte[columns.length];
		Arrays.fill(generationSteps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compressionModes = new byte[columns.length];
		Arrays.fill(compressionModes, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);

		try (
			FullDataSourceV2 source = FullDataSourceV2.createWithData(
				pos, mapping, columns, generationSteps, compressionModes);
			FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(
				source, EDhApiDataCompressionMode.UNCOMPRESSED)
		)
		{
			Assert.assertEquals(FullDataSourceV2DTO.DATA_FORMAT.V2_LATEST, dto.dataFormatVersion);
			dto.dataFormatVersion = FullDataSourceV2DTO.DATA_FORMAT.V3_TELLUS_TALL_Y;
			try (FullDataSourceV2 decoded = dto.createUnitTestDataSource())
			{
				Assert.assertEquals(expected, decoded.dataPoints[targetIndex].getLong(0));
			}
		}
	}

	private static LongArrayList[] emptyColumns()
	{
		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		for (int i = 0; i < columns.length; i++)
		{
			columns[i] = new LongArrayList();
		}
		return columns;
	}
}
