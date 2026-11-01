/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.sql.dto;

import com.seibel.distanthorizons.core.util.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.AbstractPhantomArrayList;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.sql.dto.util.FullDataMinMaxPosUtil;
import com.seibel.distanthorizons.core.sql.dto.util.VarintUtil;
import com.seibel.distanthorizons.core.util.BoolUtil;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;

/** handles storing {@link FullDataSourceV2}'s in the database. */
public class FullDataSourceV2DTO 
		extends AbstractPhantomArrayList
		implements IBaseDTO<Long>, INetworkObject, AutoCloseable
{
	public static final boolean VALIDATE_INPUT_DATAPOINTS = true;
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("V2DTO");
	
	public static class DATA_FORMAT
	{
		public static final int V1_NO_ADJACENT_DATA = 1;
		/** Written by the previous Tellus height fork; its payload uses the V2 encoding. */
		public static final int V3_TELLUS_TALL_Y = 3;
		public static final int V2_LATEST = 2;
	}
	
	
	
	public long pos;
	
	/** only for the data array */
	public int dataChecksum;
	
	public ByteArrayList compressedDataByteArray;
	public ByteArrayList compressedNorthAdjDataByteArray;
	public ByteArrayList compressedSouthAdjDataByteArray;
	public ByteArrayList compressedEastAdjDataByteArray;
	public ByteArrayList compressedWestAdjDataByteArray;
	
	/** @see EDhApiWorldGenerationStep */
	public ByteArrayList compressedColumnGenStepByteArray;
	/** @see EDhApiWorldCompressionMode */
	public ByteArrayList compressedWorldCompressionModeByteArray;
	
	public ByteArrayList compressedMappingByteArray;
	
	public byte dataFormatVersion;
	public byte compressionModeValue;
	
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToParent;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToChildren;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean regenerate;
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public static FullDataSourceV2DTO CreateFromDataSource(FullDataSourceV2 dataSource, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		
		// populate arrays
		writeDataSourceDataArrayToBlobV2(dataSource.dataPoints, dto.compressedDataByteArray, null, compressionModeEnum);
		writeGenerationStepsToBlob(dataSource.columnGenerationSteps, dto.compressedColumnGenStepByteArray, compressionModeEnum);
		writeWorldCompressionModeToBlob(dataSource.columnWorldCompressionMode, dto.compressedWorldCompressionModeByteArray, compressionModeEnum);
		writeDataMappingToBlob(dataSource.mapping, dto.compressedMappingByteArray, compressionModeEnum);
		// adjacent full data
		writeDataSourceDataArrayToBlobV2(dataSource.dataPoints, dto.compressedNorthAdjDataByteArray, EDhDirection.NORTH, compressionModeEnum);
		writeDataSourceDataArrayToBlobV2(dataSource.dataPoints, dto.compressedSouthAdjDataByteArray, EDhDirection.SOUTH, compressionModeEnum);
		writeDataSourceDataArrayToBlobV2(dataSource.dataPoints, dto.compressedEastAdjDataByteArray, EDhDirection.EAST, compressionModeEnum);
		writeDataSourceDataArrayToBlobV2(dataSource.dataPoints, dto.compressedWestAdjDataByteArray, EDhDirection.WEST, compressionModeEnum);
		
		// populate individual variables
		{
			dto.pos = dataSource.getPos();
			// the mapping hash isn't included since it takes significantly longer to calculate and 
			// as of the time of this comment (2025-1-22) the checksum isn't used for anything so changing it shouldn't cause any issues
			dto.dataChecksum = dataSource.hashCode();
			dto.dataFormatVersion = DATA_FORMAT.V2_LATEST;
			dto.compressionModeValue = compressionModeEnum.value;
			dto.lastModifiedUnixDateTime = dataSource.lastModifiedUnixDateTime;
			dto.createdUnixDateTime = dataSource.createdUnixDateTime;
			dto.applyToParent = dataSource.applyToParent;
			dto.applyToChildren = dataSource.applyToChildren;
			dto.regenerate = dataSource.regenerateLeaf;
		}
		
		return dto;
	}
	
	/** Should only be used for subsequent decoding */
	public static FullDataSourceV2DTO CreateEmptyDataSourceForDecoding() { return new FullDataSourceV2DTO(); }
	private FullDataSourceV2DTO() 
	{
		super(ARRAY_LIST_POOL, 8, 0, 0, 0, 0);
		
		// Expected sizes here are 0 since we don't know how big these arrays need to be,
		// they depend on compression settings and world complexity.
		this.compressedDataByteArray = this.pooledArraysCheckout.getByteArray(0, 0);
		this.compressedColumnGenStepByteArray = this.pooledArraysCheckout.getByteArray(1, 0);
		this.compressedWorldCompressionModeByteArray = this.pooledArraysCheckout.getByteArray(2, 0);
		this.compressedMappingByteArray = this.pooledArraysCheckout.getByteArray(3, 0);
		
		this.compressedNorthAdjDataByteArray = this.pooledArraysCheckout.getByteArray(4, 0);
		this.compressedSouthAdjDataByteArray = this.pooledArraysCheckout.getByteArray(5, 0);
		this.compressedEastAdjDataByteArray = this.pooledArraysCheckout.getByteArray(6, 0);
		this.compressedWestAdjDataByteArray = this.pooledArraysCheckout.getByteArray(7, 0);
	}
	
	
	
	//========================//
	// data source population //
	//========================//
	
	public FullDataSourceV2 createDataSource(@NotNull ILevelWrapper levelWrapper, EDhDirection direction) throws IOException, InterruptedException, DataCorruptedException
	{
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(this.pos);
		try
		{	
			this.populateDataSource(dataSource, levelWrapper, direction, false);
		}
		catch (Exception e)
		{
			dataSource.close();
			throw e;
		}
		
		return dataSource;
	}
	
	/**
	 * May be missing one or more data fields. <br>
	 * Designed to be used without access to Minecraft. 
	 */
	public FullDataSourceV2 createUnitTestDataSource() throws IOException, InterruptedException, DataCorruptedException
	{ return this.createUnitTestDataSource(null); }
	/** 
	 * May be missing one or more data fields. <br>
	 * Designed to be used without access to Minecraft. 
	 */
	public FullDataSourceV2 createUnitTestDataSource(EDhDirection direction) throws IOException, InterruptedException, DataCorruptedException 
	{ return this.populateDataSource(FullDataSourceV2.createEmpty(this.pos), null, direction,true); }
	
	private FullDataSourceV2 populateDataSource(
			FullDataSourceV2 dataSource, ILevelWrapper levelWrapper,
			@Nullable EDhDirection direction,
			boolean unitTest) throws IOException, InterruptedException, DataCorruptedException
	{
		// format validation //
		
		if (this.dataFormatVersion != DATA_FORMAT.V1_NO_ADJACENT_DATA
			&& this.dataFormatVersion != DATA_FORMAT.V2_LATEST
			&& this.dataFormatVersion != DATA_FORMAT.V3_TELLUS_TALL_Y)
		{
			throw new IllegalStateException("Data source population only supports formats: ["+DATA_FORMAT.V1_NO_ADJACENT_DATA+","+DATA_FORMAT.V2_LATEST+","+DATA_FORMAT.V3_TELLUS_TALL_Y+"], data format found: ["+this.dataFormatVersion+"].");
		}
		
		if (direction != null
			&& this.dataFormatVersion == DATA_FORMAT.V1_NO_ADJACENT_DATA)
		{
			throw new IllegalStateException("Data format ["+this.dataFormatVersion+"] doesn't support adjacent data. Automatic conversion must be done.");
		}
		
		
		
		// compression //
		
		EDhApiDataCompressionMode compressionModeEnum;
		try
		{
			compressionModeEnum = EDhApiDataCompressionMode.getFromValue(this.compressionModeValue);
		}
		catch (IllegalArgumentException e)
		{
			// may happen if the compressor value was changed to an invalid option
			throw new DataCorruptedException(e);
		}
		
		
		
		// data //
		
		// clear any old data so we can start fresh
		for (int i = 0; i < FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH; i++)
		{
			@NotNull LongArrayList array = dataSource.dataPoints[i];
			array.clear();
			array.add(FullDataPointUtil.EMPTY_DATA_POINT);
		}
		
		if (direction == null)
		{
			readBlobToGenerationSteps(this.compressedColumnGenStepByteArray, dataSource.columnGenerationSteps, compressionModeEnum);
			readBlobToWorldCompressionMode(this.compressedWorldCompressionModeByteArray, dataSource.columnWorldCompressionMode, compressionModeEnum);
			
			if (this.dataFormatVersion == 1)
			{
				readBlobToDataSourceDataArrayV1(this.compressedDataByteArray, dataSource.dataPoints, compressionModeEnum);
			}
			else
			{
				// doesn't include adjacent (ie edge) data
				readBlobToDataSourceDataArrayV2(this.compressedDataByteArray, dataSource.dataPoints, null, compressionModeEnum);
				
				readBlobToDataSourceDataArrayV2(this.compressedNorthAdjDataByteArray, dataSource.dataPoints, EDhDirection.NORTH, compressionModeEnum);
				readBlobToDataSourceDataArrayV2(this.compressedSouthAdjDataByteArray, dataSource.dataPoints, EDhDirection.SOUTH, compressionModeEnum);
				readBlobToDataSourceDataArrayV2(this.compressedEastAdjDataByteArray, dataSource.dataPoints, EDhDirection.EAST, compressionModeEnum);
				readBlobToDataSourceDataArrayV2(this.compressedWestAdjDataByteArray, dataSource.dataPoints, EDhDirection.WEST, compressionModeEnum);
			}
		}
		else
		{
			// adjacent data is stored in the same byte array
			// as the normal data,
			// this is done so data sources down-stream
			// can all be handled identically regardless of
			// whether they're a full or partial data source
			readBlobToDataSourceDataArrayV2(this.compressedDataByteArray, dataSource.dataPoints, direction, compressionModeEnum);	
		}
		
		
		// mapping //
		
		dataSource.mapping.clear(dataSource.getPos());
		// should only be null when used in a unit test
		if (!unitTest)
		{
			if (levelWrapper == null)
			{
				throw new NullPointerException("No level wrapper present, unable to deserialize data map. This should only be used for unit tests.");
			}
			
			readBlobToDataMapping(dataSource.mapping, this.compressedMappingByteArray, dataSource.getPos(), levelWrapper,  compressionModeEnum);
		}
		
		
		
		// individual properties //
		
		dataSource.lastModifiedUnixDateTime = this.lastModifiedUnixDateTime;
		dataSource.createdUnixDateTime = this.createdUnixDateTime;
		
		dataSource.isEmpty = false;
		
		if (this.applyToParent != null)
		{
			dataSource.applyToParent = this.applyToParent;
		}
		if (this.applyToChildren != null)
		{
			dataSource.applyToChildren = this.applyToChildren;
		}
		if (this.regenerate != null)
		{
			dataSource.regenerateLeaf = this.regenerate;
		}
		
		return dataSource;
	}
	
	
	
	//=================//
	// (de)serializing //
	//=================//
	
	public static void writeDataSourceDataArrayToBlobV1(
			LongArrayList[] inputDataArray, ByteArrayList outputByteArray, 
			EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		try (DhDataOutputStream compressedOut = DhDataOutputStream.create(compressionModeEnum, outputByteArray))
		{
			// write the data
			int dataArrayLength = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
			for (int xz = 0; xz < dataArrayLength; xz++)
			{
				LongArrayList dataColumn = inputDataArray[xz];
				
				// write column length
				short columnLength = (dataColumn != null) ? (short) dataColumn.size() : 0;
				// a short is used instead of an int because at most we store 4096 vertical slices and a 
				// short fits that with less wasted spaces vs an int (short has max value of 32,767 vs int's max of 2 billion)
				compressedOut.writeShort(columnLength);
				
				// write column data (will be skipped if no data was present)
				for (int y = 0; y < columnLength; y++)
				{
					try
					{
						compressedOut.writeLong(FullDataPointUtil.toLegacyDataPoint(dataColumn.getLong(y)));
					}
					catch (DataCorruptedException e)
					{
						throw new IOException(e);
					}
				}
			}
		}
	}
	private static void readBlobToDataSourceDataArrayV1(
			ByteArrayList inputCompressedDataByteArray, LongArrayList[] outputDataLongArray, 
			EDhApiDataCompressionMode compressionModeEnum) throws IOException, DataCorruptedException
	{
		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1);
			DhDataInputStream compressedIn = DhDataInputStream.create(inputCompressedDataByteArray, compressionModeEnum, checkout))
		{
			// read the data
			int dataArrayLength = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
			for (int xz = 0; xz < dataArrayLength; xz++)
			{
				// read the column length
				short dataColumnLength = compressedIn.readShort(); // separate variables are used for debugging and in case validation wants to be added later 
				if (dataColumnLength < 0)
				{
					throw new DataCorruptedException("Read DataSource Blob data at index [" + xz + "], column length [" + dataColumnLength + "] should be greater than zero.");
				}
				
				LongArrayList dataColumn = outputDataLongArray[xz];
				ListUtil.clearAndSetSize(dataColumn, dataColumnLength);
				
				// read column data (will be skipped if no data was present)
				for (int y = 0; y < dataColumnLength; y++)
				{
					long dataPoint = FullDataPointUtil.fromLegacyDataPoint(compressedIn.readLong());
					if (VALIDATE_INPUT_DATAPOINTS)
					{
						FullDataPointUtil.validateDatapoint(dataPoint);
					}
					dataColumn.set(y, dataPoint);
				}
			}
		}
	}
	
	private static void writeDataSourceDataArrayToBlobV2(
			LongArrayList[] inputDataArray, ByteArrayList outputByteArray,
			@Nullable EDhDirection direction, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		int minX, maxX, minZ, maxZ;
		if (direction != null)
		{
			long encodedMinMaxPos = FullDataMinMaxPosUtil.getEncodedMinMaxPos(direction);
			minX = FullDataMinMaxPosUtil.getAdjMinX(encodedMinMaxPos);
			maxX = FullDataMinMaxPosUtil.getAdjMaxX(encodedMinMaxPos);
			minZ = FullDataMinMaxPosUtil.getAdjMinZ(encodedMinMaxPos);
			maxZ = FullDataMinMaxPosUtil.getAdjMaxZ(encodedMinMaxPos);
		}
		else
		{
			// skip the border data so we don't duplicate the adjacent data
			minX = 1;
			maxX = FullDataSourceV2.WIDTH-1;
			minZ = 1;
			maxZ = FullDataSourceV2.WIDTH-1;
		}
		
		try (DhDataOutputStream compressedOut = DhDataOutputStream.create(compressionModeEnum, outputByteArray))
		{
			// this method would be simpler if we allocated a bunch of temporary arrays,
			// but we're trying to avoid garbage.
			
			// 1. column lengths
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x,z);
					
					LongArrayList col = inputDataArray[index];
					int size = (col != null) ? col.size() : 0;
					VarintUtil.writeVarint(compressedOut, size);
				}
			}
			
			// 2. column ids, with "is lit" and "is discontinuous" bits
			int previousBottomY = 0;
			
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					
					LongArrayList col = inputDataArray[index];
					int size = col != null ? col.size() : 0;
					for (int y = 0; y < size; y++)
					{
						long data = col.getLong(y);
						
						int id = FullDataPointUtil.getId(data);
						int height = FullDataPointUtil.getHeight(data);
						int bottomY = FullDataPointUtil.getBottomY(data);
						
						boolean hasLight = (FullDataPointUtil.getBlockLight(data) | FullDataPointUtil.getSkyLight(data)) != LodUtil.MIN_MC_LIGHT;
						
						// all datapoints are contiguous, with no gaps
						// so having both height and bottomY is redundant. We could store the prediction
						// in an array, but it's much cheaper to just recompute it later.
						int expectedBottomY = previousBottomY - height;
						boolean hasDiscontinuity = bottomY != expectedBottomY;
						previousBottomY = bottomY;
						
						VarintUtil.writeVarint(compressedOut, (id << 2) | (hasLight ? 2 : 0) | (hasDiscontinuity ? 1 : 0));
					}
				}
			}
			
			// 3. heights
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					
					LongArrayList col = inputDataArray[index];
					int size = (col != null) ? col.size() : 0;
					for (int y = 0; y < size; y++)
					{
						long data = col.getLong(y);
						VarintUtil.writeVarint(compressedOut, FullDataPointUtil.getHeight(data));
					}
				}
			}
			
			// 4. bottomY (only the mis-predicted ones)
			previousBottomY = 0;
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					
					LongArrayList col = inputDataArray[index];
					int size = (col != null) ? col.size() : 0;
					for (int y = 0; y < size; y++)
					{
						long data = col.getLong(y);
						
						int height = FullDataPointUtil.getHeight(data);
						int bottomY = FullDataPointUtil.getBottomY(data);
						
						int expectedBottomY = previousBottomY - height;
						if (bottomY != expectedBottomY)
						{
							VarintUtil.writeVarint(compressedOut, VarintUtil.zigzagEncode(bottomY - expectedBottomY));
						}
						previousBottomY = bottomY;
					}
				}
			}
			
			// 5. packed Light (only lit sections)
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					
					LongArrayList col = inputDataArray[index];
					int size = (col != null) ? col.size() : 0;
					for (int y = 0; y < size; y++)
					{
						long data = col.getLong(y);
						int blockLight = FullDataPointUtil.getBlockLight(data);
						int skyLight = FullDataPointUtil.getSkyLight(data);
						byte packedLight = (byte) ((blockLight << 4) | skyLight);
						if (packedLight != 0)
						{
							compressedOut.writeByte(packedLight);
						}
					}
				}
			}
		}
	}
	private static void readBlobToDataSourceDataArrayV2(
			ByteArrayList inputCompressedDataByteArray,
			LongArrayList[] outputDataLongArray,
			@Nullable EDhDirection direction, EDhApiDataCompressionMode compressionModeEnum)
			throws IOException, DataCorruptedException
	{
		int minX, maxX, minZ, maxZ;
		if (direction != null)
		{
			long encodedMinMaxPos = FullDataMinMaxPosUtil.getEncodedMinMaxPos(direction);
			minX = FullDataMinMaxPosUtil.getAdjMinX(encodedMinMaxPos);
			maxX = FullDataMinMaxPosUtil.getAdjMaxX(encodedMinMaxPos);
			minZ = FullDataMinMaxPosUtil.getAdjMinZ(encodedMinMaxPos);
			maxZ = FullDataMinMaxPosUtil.getAdjMaxZ(encodedMinMaxPos);
		}
		else
		{
			// skip the border data so we don't duplicate the adjacent data
			minX = 1;
			maxX = FullDataSourceV2.WIDTH-1;
			minZ = 1;
			maxZ = FullDataSourceV2.WIDTH-1;
		}
		
		
		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1);
			DhDataInputStream compressedIn = DhDataInputStream.create(inputCompressedDataByteArray, compressionModeEnum, checkout))
		{
			// 1. column counts, preallocate
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					
					int count = VarintUtil.readVarint(compressedIn);
					ListUtil.clearAndSetSize(outputDataLongArray[index], count);
				}
			}
			
			// 2. ids and flags for min_y and light
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					LongArrayList col = outputDataLongArray[index];
					
					for (int i = 0; i < col.size(); i++)
					{
						int encodedId = VarintUtil.readVarint(compressedIn);
						col.set(i, FullDataPointUtil.encode(encodedId >> 2, 1, encodedId & 1, (byte) (encodedId & 2), (byte) 0));
					}
				}
			}
			
			// 3. height
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					LongArrayList col = outputDataLongArray[index];
					
					for (int i = 0; i < col.size(); i++)
					{
						int height = VarintUtil.readVarint(compressedIn);
						long data = col.getLong(i);
						col.set(i, FullDataPointUtil.setHeight(data, height));
					}
				}
			}
			
			// 4. bottomY
			int previousBottomY = 0;
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					LongArrayList col = outputDataLongArray[index];
					
					for (int i = 0; i < col.size(); i++)
					{
						long data = col.getLong(i);
						int error = 0;
						if (FullDataPointUtil.getBottomY(data) != 0)
						{
							error = VarintUtil.zigzagDecode(VarintUtil.readVarint(compressedIn));
						}
						int bottomY = previousBottomY - FullDataPointUtil.getHeight(data) + error;
						col.set(i, FullDataPointUtil.setBottomY(data, bottomY));
						previousBottomY = bottomY;
					}
				}
			}
			
			// 5. lights
			for (int x = minX; x < maxX; x++)
			{
				for (int z = minZ; z < maxZ; z++)
				{
					int index = FullDataSourceV2.relativePosToIndex(x, z);
					LongArrayList col = outputDataLongArray[index];
					
					for (int i = 0; i < col.size(); i++)
					{
						long data = col.getLong(i);
						boolean hasLight = FullDataPointUtil.getBlockLight(data) != 0;
						byte skyLight = 0;
						byte blockLight = 0;
						if (hasLight)
						{
							byte packedLight = compressedIn.readByte();
							skyLight = (byte) (packedLight & 0xF);
							blockLight = (byte) (packedLight >> 4);
						}
						
						col.set(i, FullDataPointUtil.setSkyLight(
								FullDataPointUtil.setBlockLight(data, blockLight),
								skyLight));
					}
				}
			}
			
			if (FullDataPointUtil.RUN_VALIDATION)
			{
				// These points all bypassed validation because of using setters.
				for (int x = minX; x < maxX; x++)
				{
					for (int z = minZ; z < maxZ; z++)
					{
						int index = FullDataSourceV2.relativePosToIndex(x, z);
						LongArrayList col = outputDataLongArray[index];
						
						for (int i = 0; i < col.size(); i++)
						{
							FullDataPointUtil.validateDatapoint(col.getLong(i));
						}
					}
				}
			}
		}
		catch (EOFException e)
		{
			throw new DataCorruptedException(e);
		}
	}
	
	
	private static void writeGenerationStepsToBlob(ByteArrayList inputColumnGenStepByteArray, ByteArrayList outputByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		try (DhDataOutputStream compressedOut = DhDataOutputStream.create(compressionModeEnum, outputByteArray))
		{
			for (int i = 0; i < inputColumnGenStepByteArray.size(); i++)
			{
				compressedOut.writeByte(inputColumnGenStepByteArray.getByte(i));
			}
		}
	}
	private static void readBlobToGenerationSteps(ByteArrayList inputCompressedDataByteArray, ByteArrayList outputByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException, DataCorruptedException
	{
		try(PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1);
			DhDataInputStream compressedIn = DhDataInputStream.create(inputCompressedDataByteArray, compressionModeEnum, checkout))
		{
			compressedIn.readFully(outputByteArray.elements(), 0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
		}
		catch (EOFException e)
		{
			throw new DataCorruptedException(e);
		}
	}
	
	
	private static void writeWorldCompressionModeToBlob(ByteArrayList inputWorldCompressionModeByteArray, ByteArrayList outputByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		try (DhDataOutputStream compressedOut = DhDataOutputStream.create(compressionModeEnum, outputByteArray))
		{
			for (int i = 0; i < inputWorldCompressionModeByteArray.size(); i++)
			{
				compressedOut.write(inputWorldCompressionModeByteArray.getByte(i));
			}
		}
	}
	private static void readBlobToWorldCompressionMode(ByteArrayList inputCompressedDataByteArray, ByteArrayList outputByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException, DataCorruptedException
	{
		try(PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1);
			DhDataInputStream compressedIn = DhDataInputStream.create(inputCompressedDataByteArray, compressionModeEnum, checkout))
		{
			compressedIn.readFully(outputByteArray.elements(), 0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
		}
		catch (EOFException e)
		{
			throw new DataCorruptedException(e);
		}
	}
	
	
	private static void writeDataMappingToBlob(FullDataPointIdMap mapping, ByteArrayList outputByteArray, EDhApiDataCompressionMode compressionModeEnum) throws IOException
	{
		try(DhDataOutputStream compressedOut = DhDataOutputStream.create(compressionModeEnum, outputByteArray))
		{
			mapping.serialize(compressedOut);
		}
	}
	private static void readBlobToDataMapping(FullDataPointIdMap mapping, ByteArrayList inputCompressedDataByteArray, long pos, @NotNull ILevelWrapper levelWrapper, EDhApiDataCompressionMode compressionModeEnum) throws IOException, InterruptedException, DataCorruptedException
	{
		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1);
			DhDataInputStream compressedIn = DhDataInputStream.create(inputCompressedDataByteArray, compressionModeEnum, checkout))
		{
			FullDataPointIdMap.deserialize(mapping, compressedIn, pos, levelWrapper);
		}
	}
	
	
	
	//============//
	// networking //
	//============//
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeLong(this.pos);
		out.writeInt(this.dataChecksum);
		
		// data
		out.writeInt(this.compressedDataByteArray.size());
		out.writeBytes(this.compressedDataByteArray.elements(), 0, this.compressedDataByteArray.size());
		
		// adj data
		out.writeInt(this.compressedNorthAdjDataByteArray.size());
		out.writeBytes(this.compressedNorthAdjDataByteArray.elements(), 0, this.compressedNorthAdjDataByteArray.size());
		out.writeInt(this.compressedSouthAdjDataByteArray.size());
		out.writeBytes(this.compressedSouthAdjDataByteArray.elements(), 0, this.compressedSouthAdjDataByteArray.size());
		out.writeInt(this.compressedEastAdjDataByteArray.size());
		out.writeBytes(this.compressedEastAdjDataByteArray.elements(), 0, this.compressedEastAdjDataByteArray.size());
		out.writeInt(this.compressedWestAdjDataByteArray.size());
		out.writeBytes(this.compressedWestAdjDataByteArray.elements(), 0, this.compressedWestAdjDataByteArray.size());
		
		// world gen
		out.writeInt(this.compressedColumnGenStepByteArray.size());
		out.writeBytes(this.compressedColumnGenStepByteArray.elements(), 0, this.compressedColumnGenStepByteArray.size());
		out.writeInt(this.compressedWorldCompressionModeByteArray.size());
		out.writeBytes(this.compressedWorldCompressionModeByteArray.elements(), 0, this.compressedWorldCompressionModeByteArray.size());
		
		// compression type
		out.writeInt(this.compressedMappingByteArray.size());
		out.writeBytes(this.compressedMappingByteArray.elements(), 0, this.compressedMappingByteArray.size());
		
		out.writeByte(this.dataFormatVersion);
		out.writeByte(this.compressionModeValue);
		
		out.writeBoolean(BoolUtil.falseIfNull(this.applyToParent));
		out.writeBoolean(BoolUtil.falseIfNull(this.applyToChildren));
		out.writeBoolean(BoolUtil.falseIfNull(this.regenerate));
		
		out.writeLong(this.lastModifiedUnixDateTime);
		out.writeLong(this.createdUnixDateTime);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.pos = in.readLong();
		this.dataChecksum = in.readInt();
		
		// data
		this.compressedDataByteArray.size(in.readInt());
		in.readBytes(this.compressedDataByteArray.elements(), 0, this.compressedDataByteArray.size());
		
		// adj data
		this.compressedNorthAdjDataByteArray.size(in.readInt());
		in.readBytes(this.compressedNorthAdjDataByteArray.elements(), 0, this.compressedNorthAdjDataByteArray.size());
		this.compressedSouthAdjDataByteArray.size(in.readInt());
		in.readBytes(this.compressedSouthAdjDataByteArray.elements(), 0, this.compressedSouthAdjDataByteArray.size());
		this.compressedEastAdjDataByteArray.size(in.readInt());
		in.readBytes(this.compressedEastAdjDataByteArray.elements(), 0, this.compressedEastAdjDataByteArray.size());
		this.compressedWestAdjDataByteArray.size(in.readInt());
		in.readBytes(this.compressedWestAdjDataByteArray.elements(), 0, this.compressedWestAdjDataByteArray.size());
		
		// world gen
		this.compressedColumnGenStepByteArray.size(in.readInt());
		in.readBytes(this.compressedColumnGenStepByteArray.elements(), 0, this.compressedColumnGenStepByteArray.size());
		this.compressedWorldCompressionModeByteArray.size(in.readInt());
		in.readBytes(this.compressedWorldCompressionModeByteArray.elements(), 0, this.compressedWorldCompressionModeByteArray.size());
		
		// compression type
		this.compressedMappingByteArray.size(in.readInt());
		in.readBytes(this.compressedMappingByteArray.elements(), 0, this.compressedMappingByteArray.size());
		
		this.dataFormatVersion = in.readByte();
		this.compressionModeValue = in.readByte();
		
		this.applyToParent = in.readBoolean();
		this.applyToChildren = in.readBoolean();
		this.regenerate = in.readBoolean();
		
		this.lastModifiedUnixDateTime = in.readLong();
		this.createdUnixDateTime = in.readLong();
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public Long getKey() { return this.pos; }
	@Override
	public String getKeyDisplayString() { return DhSectionPos.toString(this.pos); }
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("pos", DhSectionPos.toString(this.pos))
				.add("dataChecksum", this.dataChecksum)
				.add("compressedDataByteArray length", this.compressedDataByteArray.size())
				.add("compressedColumnGenStepByteArray length", this.compressedColumnGenStepByteArray.size())
				.add("compressedWorldCompressionModeByteArray length", this.compressedWorldCompressionModeByteArray.size())
				.add("compressedMappingByteArray length", this.compressedMappingByteArray.size())
				.add("dataFormatVersion", this.dataFormatVersion)
				.add("compressionModeValue", this.compressionModeValue)
				.add("applyToParent", this.applyToParent)
				.add("applyToChildren", this.applyToChildren)
				.add("regenerate", this.regenerate)
				.add("lastModifiedUnixDateTime", this.lastModifiedUnixDateTime)
				.add("createdUnixDateTime", this.createdUnixDateTime)
				.toString();
	}
	
	
	
}
