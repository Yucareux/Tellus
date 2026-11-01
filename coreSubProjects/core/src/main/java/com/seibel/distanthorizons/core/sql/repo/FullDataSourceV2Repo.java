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

package com.seibel.distanthorizons.core.sql.repo;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.DbConnectionClosedException;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.BoolUtil;
import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class FullDataSourceV2Repo extends AbstractDhRepo<Long, FullDataSourceV2DTO>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataSourceV2Repo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, FullDataSourceV2DTO.class);
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override 
	public String getTableName() { return "FullData"; }
	
	@Override
	protected String CreateParameterizedWhereString() { return "DetailLevel = ? AND PosX = ? AND PosZ = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, Long pos) throws SQLException
	{
		int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
		
		statement.setInt(index++, detailLevel);
		statement.setInt(index++, DhSectionPos.getX(pos));
		statement.setInt(index++, DhSectionPos.getZ(pos));
		
		return index;
	}
	
	
	
	@Override @Nullable
	public FullDataSourceV2DTO convertResultSetToDto(ResultSet resultSet) throws ClassCastException, IOException, SQLException
	{ return this.convertResultSetToDto(resultSet, true); }
	
	public FullDataSourceV2DTO convertResultSetToDto(ResultSet resultSet, boolean includeAdjacent) throws ClassCastException, IOException, SQLException
	{
		//======================//
		// get statement values //
		//======================//
		
		byte detailLevel = resultSet.getByte("DetailLevel");
		byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		int posX = resultSet.getInt("PosX");
		int posZ = resultSet.getInt("PosZ");
		long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
		
		int dataChecksum = resultSet.getInt("DataChecksum");
		
		
		byte dataFormatVersion = resultSet.getByte("DataFormatVersion");
		byte compressionModeValue = resultSet.getByte("CompressionMode");
		
		// while these values can be null in the DB, null would just equate to false
		boolean applyToParent = (resultSet.getInt("ApplyToParent")) == 1;
		boolean applyToChildren = (resultSet.getInt("ApplyToChildren")) == 1;
		boolean regenerate = (resultSet.getInt("Regenerate")) == 1;
		
		long lastModifiedUnixDateTime = resultSet.getLong("LastModifiedUnixDateTime");
		long createdUnixDateTime = resultSet.getLong("CreatedUnixDateTime");
		
		
		
		//===================//
		// set DTO variables //
		//===================//
		
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		
		// set pooled arrays
		dto.compressedDataByteArray = putAllBytes(resultSet.getBinaryStream("Data"), dto.compressedDataByteArray);
		dto.compressedColumnGenStepByteArray = putAllBytes(resultSet.getBinaryStream("ColumnGenerationStep"), dto.compressedColumnGenStepByteArray);
		dto.compressedWorldCompressionModeByteArray = putAllBytes(resultSet.getBinaryStream("ColumnWorldCompressionMode"), dto.compressedWorldCompressionModeByteArray);
		dto.compressedMappingByteArray = putAllBytes(resultSet.getBinaryStream("Mapping"), dto.compressedMappingByteArray);
		
		// adjacent full data
		if (includeAdjacent)
		{
			dto.compressedNorthAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("NorthAdjData"), dto.compressedNorthAdjDataByteArray);
			dto.compressedSouthAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("SouthAdjData"), dto.compressedSouthAdjDataByteArray);
			dto.compressedEastAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("EastAdjData"), dto.compressedEastAdjDataByteArray);
			dto.compressedWestAdjDataByteArray = putAllBytes(resultSet.getBinaryStream("WestAdjData"), dto.compressedWestAdjDataByteArray);
		}
		
		// set individual variables
		{
			dto.pos = pos;
			dto.dataChecksum = dataChecksum;
			dto.dataFormatVersion = dataFormatVersion;
			dto.compressionModeValue = compressionModeValue;
			dto.lastModifiedUnixDateTime = lastModifiedUnixDateTime;
			dto.createdUnixDateTime = createdUnixDateTime;
			dto.applyToParent = applyToParent;
			dto.applyToChildren = applyToChildren;
			dto.regenerate = regenerate;
		}
		return dto;
	}
	
	@Nullable
	public FullDataSourceV2DTO convertResultSetToAdjDto(long pos, ResultSet resultSet) throws ClassCastException, IOException, SQLException
	{
		//======================//
		// get statement values //
		//======================//
		
		int dataChecksum = resultSet.getInt("DataChecksum");
		
		
		byte dataFormatVersion = resultSet.getByte("DataFormatVersion");
		byte compressionModeValue = resultSet.getByte("CompressionMode");
		
		// while these values can be null in the DB, null would just equate to false
		boolean applyToParent = (resultSet.getInt("ApplyToParent")) == 1;
		boolean applyToChildren = (resultSet.getInt("ApplyToChildren")) == 1;
		boolean regenerate = (resultSet.getInt("Regenerate")) == 1;
		
		long lastModifiedUnixDateTime = resultSet.getLong("LastModifiedUnixDateTime");
		long createdUnixDateTime = resultSet.getLong("CreatedUnixDateTime");
		
		
		
		//===================//
		// set DTO variables //
		//===================//
		
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		// set pooled arrays
		dto.compressedDataByteArray = putAllBytes(resultSet.getBinaryStream("AdjData"), dto.compressedDataByteArray);
		dto.compressedColumnGenStepByteArray = putAllBytes(resultSet.getBinaryStream("ColumnGenerationStep"), dto.compressedColumnGenStepByteArray);
		dto.compressedWorldCompressionModeByteArray = putAllBytes(resultSet.getBinaryStream("ColumnWorldCompressionMode"), dto.compressedWorldCompressionModeByteArray);
		dto.compressedMappingByteArray = putAllBytes(resultSet.getBinaryStream("Mapping"), dto.compressedMappingByteArray);
		
		// set individual variables
		{
			dto.pos = pos;
			dto.dataChecksum = dataChecksum;
			dto.dataFormatVersion = dataFormatVersion;
			dto.compressionModeValue = compressionModeValue;
			dto.lastModifiedUnixDateTime = lastModifiedUnixDateTime;
			dto.createdUnixDateTime = createdUnixDateTime;
			dto.applyToParent = applyToParent;
			dto.applyToChildren = applyToChildren;
			dto.regenerate = regenerate;
		}
		return dto;
	}
	
	@Override
	public PreparedStatement createUpsertStatement(FullDataSourceV2DTO dto) throws SQLException
	{
		// Dynamic string so we can update one, both, or neither
		// of the applyTo... flags on conflict.
		// This is necessary to prevent concurrent modifications when
		// update propagation is run.
		String upsertSqlTemplate = (
			"INSERT INTO " + this.getTableName() + " (\n" +
			"   DetailLevel, PosX, PosZ, \n" +
			"   MinY, DataChecksum, \n" +
			"   Data, ColumnGenerationStep, ColumnWorldCompressionMode, Mapping, \n" +
			"   NorthAdjData, SouthAdjData, EastAdjData, WestAdjData, \n" +
			"   DataFormatVersion, CompressionMode, ApplyToParent, ApplyToChildren, Regenerate, \n" +
			"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
			"VALUES( \n" +
			"    ?, ?, ?, \n" +
			"    ?, ?, \n" +
			"    ?, ?, ?, ?, \n" +
			"    ?, ?, ?, ?, \n" +
			"    ?, ?, ?, ?, ?, \n" +
			"    ?, ? \n" +
			") \n" +
			"ON CONFLICT(DetailLevel, PosX, PosZ) DO UPDATE SET \n" +
			"    DataChecksum = excluded.DataChecksum \n" +
			
			"   ,Data = excluded.Data \n" +
			"   ,ColumnGenerationStep = excluded.ColumnGenerationStep \n" +
			"   ,ColumnWorldCompressionMode = excluded.ColumnWorldCompressionMode \n" +
			"   ,Mapping = excluded.Mapping \n" +
			"   ,NorthAdjData = excluded.NorthAdjData, SouthAdjData = excluded.SouthAdjData \n" +
			"   ,EastAdjData = excluded.EastAdjData, WestAdjData = excluded.WestAdjData \n" +
			
			"   ,DataFormatVersion = excluded.DataFormatVersion \n" +
			"   ,CompressionMode = excluded.CompressionMode \n" +
			// only update these values if they're present
			(dto.applyToParent != null ? "   ,ApplyToParent = excluded.ApplyToParent \n" : "") +
			(dto.applyToChildren != null ? "   ,ApplyToChildren = excluded.ApplyToChildren \n" : "") +
			(dto.regenerate != null ? "   ,Regenerate = excluded.Regenerate \n" : "") +
			
			"   ,LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime \n"
			// intern should help reduce memory overhead due to this string being dynamic
		).intern();
		
		
		PreparedStatement statement = this.createPreparedStatement(upsertSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		
		
		int i = 1;
		statement.setInt(i++, DhSectionPos.getDetailLevel(dto.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		statement.setInt(i++, DhSectionPos.getX(dto.pos));
		statement.setInt(i++, DhSectionPos.getZ(dto.pos));
		
		statement.setInt(i++, 0); // deprecated MinY column
		// MinY is deprecated due to a bug introduced sometime before 2.3.6 where was always set to "0"
		// and due to being unused wasn't noticed until 2.3.7-dev (2025-11-15)
		
		statement.setInt(i++, dto.dataChecksum);
		
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedDataByteArray.elements()), dto.compressedDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedColumnGenStepByteArray.elements()), dto.compressedColumnGenStepByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWorldCompressionModeByteArray.elements()), dto.compressedWorldCompressionModeByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedMappingByteArray.elements()), dto.compressedMappingByteArray.size());
		// adjacent full data
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedNorthAdjDataByteArray.elements()), dto.compressedNorthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedSouthAdjDataByteArray.elements()), dto.compressedSouthAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedEastAdjDataByteArray.elements()), dto.compressedEastAdjDataByteArray.size());
		statement.setBinaryStream(i++, new ByteArrayInputStream(dto.compressedWestAdjDataByteArray.elements()), dto.compressedWestAdjDataByteArray.size());
		
		statement.setByte(i++, dto.dataFormatVersion);
		statement.setByte(i++, dto.compressionModeValue);
		// if nothing is present assume we don't need/want to propagate updates
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.applyToParent));
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.applyToChildren));
		statement.setBoolean(i++, BoolUtil.falseIfNull(dto.regenerate));
		
		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, System.currentTimeMillis()); // created unix time (only used if this is a fresh insert)
		
		return statement;
	}
	
	//endregion
	
	
	
	//=================//
	// partial selects //
	//=================//
	//region
	
	private final String getAdjForDirectionSqlTemplate =
			"SELECT \n" +
					"   DataChecksum, \n" +
					"   ColumnGenerationStep, ColumnWorldCompressionMode, Mapping, \n" +
					"   DataFormatVersion, CompressionMode, ApplyToParent, ApplyToChildren, Regenerate, \n" +
					"   LastModifiedUnixDateTime, CreatedUnixDateTime, \n" +
					"   DIRECTION_ENUM as AdjData \n" +
					"FROM "+this.getTableName() + "\n" +
					"   WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?; \n";
	private final String getAdjForNorthDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "NorthAdjData");
	private final String getAdjForSouthDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "SouthAdjData");
	private final String getAdjForEastDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "EastAdjData");
	private final String getAdjForWestDirTemplate = this.getAdjForDirectionSqlTemplate.replace("DIRECTION_ENUM", "WestAdjData");
	
	public FullDataSourceV2DTO getAdjByPosAndDirection(long pos, EDhDirection direction)
	{
		// parameters don't work in the select, doing so causes
		// JDBC to return the wrong binary data,
		// so we need to hard code the direction enum
		String sql;
		switch (direction)
		{
			case NORTH:
				sql = this.getAdjForNorthDirTemplate;
				break;
			case SOUTH:
				sql = this.getAdjForSouthDirTemplate;
				break;
			case EAST:
				sql = this.getAdjForEastDirTemplate;
				break;
			case WEST:
				sql = this.getAdjForWestDirTemplate;
				break;
			
			default:
				throw new IllegalArgumentException();
		}
		try(PreparedStatement statement = this.createPreparedStatement(sql))
		{
			if (statement == null)
			{
				return null;
			}
			
			
			
			int i = 1;
			statement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			
			try(ResultSet resultSet = this.query(statement))
			{
				if (resultSet != null
					&& resultSet.next())
				{
					return this.convertResultSetToAdjDto(pos, resultSet);
				}
				else
				{
					return null;
				}
			}
		}
		catch (SQLException | IOException e)
		{
			if (e instanceof SQLException
					&& DbConnectionClosedException.isClosedException((SQLException)e))
			{
				//LOGGER.warn("Attempted to get ["+this.dtoClass.getSimpleName()+"] with primary key ["+primaryKey+"] on closed repo ["+this.connectionString+"].");	
			}
			else
			{
				LOGGER.warn("Unexpected issue deserializing DTO ["+this.dtoClass.getSimpleName()+"] with pos ["+DhSectionPos.toString(pos)+"] and direction ["+direction+"]. Error: ["+e.getMessage()+"].", e);
			}
			return null;
		}
	}
	
	//endregion
	
	
	
	//=========//
	// updates //
	//=========//
	//region
	
	/** should be be very similar to {@link FullDataSourceV2Repo#setApplyToChildrenSql} */
	private final String setApplyToParentSql = 
			"UPDATE "+this.getTableName()+" \n" +
			"SET ApplyToParent = ? \n" +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	public void setApplyToParent(long pos, boolean applyToParent)
	{ this.setApplyToFlag(pos, applyToParent, this.setApplyToParentSql); }
	
	/** should be be very similar to {@link FullDataSourceV2Repo#setApplyToParentSql} */
	private final String setApplyToChildrenSql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET ApplyToChildren = ? \n" +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	public void setApplyToChild(long pos, boolean applyToChild)
	{ this.setApplyToFlag(pos, applyToChild, this.setApplyToChildrenSql); }
	
	/** should be be very similar to {@link FullDataSourceV2Repo#setApplyToParentSql} */
	private final String setRegenerateToChildrenSql =
			"UPDATE "+this.getTableName()+" \n" +
			"SET Regenerate = ? \n" +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	public void setRegenerate(long pos, boolean regenerate)
	{ this.setApplyToFlag(pos, regenerate, this.setRegenerateToChildrenSql); }
	
	private void setApplyToFlag(long pos, boolean applyFlag, String sql)
	{
		try (PreparedStatement statement = this.createPreparedStatement(sql))
		{
			if (statement == null)
			{
				return;
			}
			
			
			int i = 1;
			statement.setBoolean(i++, applyFlag);
			
			int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
			statement.setInt(i++, detailLevel);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			try (ResultSet result = this.query(statement))
			{
				
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	
	/** should be be very similar to {@link FullDataSourceV2Repo#getChildPositionsToUpdateSql} */
	private final String getParentPositionsToUpdateSql =
			"SELECT DetailLevel, PosX, PosZ, " +
			"   abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance " +
			"FROM " + this.getTableName() + " " +
			"WHERE ApplyToParent = 1 " +
			// sorting by detail level first will reduce the total number of updates required
			// but makes it feel less responsive since distant updates won't be seen until everything 
			// underneith is done
			"ORDER BY Distance ASC " + // DetailLevel ASC,
			"LIMIT ?; ";
	public LongArrayList getParentPositionsToUpdate(int targetBlockPosX, int targetBlockPosZ, int returnCount)
	{ return this.getPositionsToUpdate(targetBlockPosX, targetBlockPosZ, returnCount, this.getParentPositionsToUpdateSql); }
	
	/** should be be very similar to {@link FullDataSourceV2Repo#getParentPositionsToUpdateSql} */
	private final String getChildPositionsToUpdateSql =
			"SELECT DetailLevel, PosX, PosZ, " +
			"   abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance " +
			"FROM " + this.getTableName() + " " +
			"WHERE ApplyToChildren = 1 " +
			"ORDER BY Distance ASC " + // DetailLevel ASC, 
			"LIMIT ?; ";
	public LongArrayList getChildPositionsToUpdate(int targetBlockPosX, int targetBlockPosZ, int returnCount)
	{ return this.getPositionsToUpdate(targetBlockPosX, targetBlockPosZ, returnCount, this.getChildPositionsToUpdateSql); }
	
	private LongArrayList getPositionsToUpdate(int targetBlockPosX, int targetBlockPosZ, int returnCount, String sql)
	{
		LongArrayList list = new LongArrayList();
		
		try (PreparedStatement statement = this.createPreparedStatement(sql))
		{
			if (statement == null)
			{
				return list;
			}
			
			
			int i = 1;
			statement.setInt(i++, targetBlockPosX);
			statement.setInt(i++, targetBlockPosZ);
			
			statement.setInt(i++, returnCount);
			
			try (ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = result.getByte("DetailLevel");
					byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					
					long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
					list.add(pos);
				}
			}
			
			return list;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	/** should be be very similar to {@link FullDataSourceV2Repo#getParentPositionsToUpdateSql} */
	private final String getRegenPositionsToUpdateSql =
		"SELECT DetailLevel, PosX, PosZ, " +
		"   abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance " +
		"FROM " + this.getTableName() + " " +
		"WHERE Regenerate = 1 " +
		"   AND Distance <= ? " +
		"ORDER BY Distance ASC " +
		"LIMIT ?; ";
	public LongArrayList getChildPositionsToRegen(int targetBlockPosX, int targetBlockPosZ, int maxBlockDistanceFromTarget, int returnCount)
	{
		LongArrayList list = new LongArrayList();
		
		try (PreparedStatement statement = this.createPreparedStatement(this.getRegenPositionsToUpdateSql))
		{
			if (statement == null)
			{
				return list;
			}
			
			
			int i = 1;
			statement.setInt(i++, targetBlockPosX);
			statement.setInt(i++, targetBlockPosZ);
			
			if (maxBlockDistanceFromTarget < 0)
			{
				statement.setLong(i++, Long.MAX_VALUE); // get everything
			}
			else
			{
				statement.setInt(i++, maxBlockDistanceFromTarget);
			}
			
			statement.setInt(i++, returnCount);
			
			try (ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = result.getByte("DetailLevel");
					byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					
					long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
					list.add(pos);
				}
			}
			
			return list;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	private final String getColumnGenerationStepSql =
			"select ColumnGenerationStep, CompressionMode " +
			"from "+this.getTableName()+" " +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	/** @return null if nothing exists for this position */
	public void getColumnGenerationStepForPos(long pos, ByteArrayList outputByteArray)
	{
		try (PreparedStatement statement = this.createPreparedStatement(this.getColumnGenerationStepSql))
		{
			if (statement == null)
			{
				return;
			}
			
			
			int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
			
			
			
			int i = 1;
			statement.setInt(i++, detailLevel);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			
			try (ResultSet result = this.query(statement))
			{
				if (result == null || !result.next())
				{
					return;
				}
				
				
				byte compressionModeEnumValue = result.getByte("CompressionMode");
				EDhApiDataCompressionMode compressionModeEnum = EDhApiDataCompressionMode.getFromValue(compressionModeEnumValue);
				
				// decompress the data
				try
				{
					ByteArrayList byteArrayList = new ByteArrayList();
					putAllBytes(result.getBinaryStream("ColumnGenerationStep"), byteArrayList);
					try(PhantomArrayListCheckout checkout = FullDataSourceV2DTO.ARRAY_LIST_POOL.checkoutByteArrays(1);
						DhDataInputStream compressedIn = DhDataInputStream.create(byteArrayList, compressionModeEnum, checkout))
					{
						putAllBytes(compressedIn, outputByteArray);
					}
				}
				catch (IOException e)
				{
					LOGGER.warn("Decompression issue when getting column gen steps for pos: [" + DhSectionPos.toString(pos) + "], deleting corrupted data.", e);
					
					this.deleteWithKey(pos);
					ListUtil.clearAndSetSize(outputByteArray, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
				}
			}
		}
		catch (SQLException e)
		{
			// done to handle resultSet.get() methods which can throw closed exceptions
			if (DbConnectionClosedException.isClosedException(e))
			{
				return;
			}
			
			throw new RuntimeException(e);
		}
	}
	
	/** 
	 * Uses the same math as {@link DataSourceRetrievalTask#DataSourceRetrievalTask(long, byte)} 
	 * to determine chunk counts.
	 */
	private final String getRegenChunkCountLimitedSql =
		"SELECT SUM(power(2, RegenList.SectionDetailLevel + 6)) as ChunkCount \n" +
		"FROM \n" +
		"( \n" +
		"   select DetailLevel as SectionDetailLevel, \n" +
		"       abs((PosX << (6 + DetailLevel)) - ?) + abs((PosZ << (6 + DetailLevel)) - ?) AS Distance \n" +
		"   FROM "+this.getTableName()+" \n" +
		"   WHERE Regenerate = 1 \n" +
		"       AND Distance <= ? \n" +
		") as RegenList;";
	public long getRegenChunkCount(int targetBlockPosX, int targetBlockPosZ, int maxBlockDistanceFromTarget)
	{
		try (PreparedStatement statement = this.createPreparedStatement(this.getRegenChunkCountLimitedSql))
		{
			if (statement == null)
			{
				return 0L;
			}
			
			int i = 1;
			statement.setInt(i++, targetBlockPosX);
			statement.setInt(i++, targetBlockPosZ);
			
			if (maxBlockDistanceFromTarget < 0)
			{
				statement.setLong(i++, Long.MAX_VALUE); // get everything
			}
			else
			{
				statement.setInt(i++, maxBlockDistanceFromTarget);
			}
			
			try (ResultSet result = this.query(statement))
			{
				if (result == null || !result.next())
				{
					return 0L;
				}
				
				
				long chunkCount = result.getLong("ChunkCount");
				return chunkCount;
			}
		}
		catch (SQLException e)
		{
			// done to handle resultSet.get() methods which can throw closed exceptions
			if (DbConnectionClosedException.isClosedException(e))
			{
				return 0L;
			}
			
			throw new RuntimeException(e);
		}
	}
	
	
	//endregion
	
	
	
	//=============//
	// multiplayer //
	//=============//
	//region
	
	private final String getTimestampForPosSql =
			"SELECT LastModifiedUnixDateTime " +
			"FROM " + this.getTableName() + " " +
			"WHERE DetailLevel = ? " +
			"AND PosX = ? " +
			"AND PosZ = ?;";
	@Nullable
	public Long getTimestampForPos(long pos)
	{
		try(PreparedStatement preparedStatement = this.createPreparedStatement(this.getTimestampForPosSql))
		{
			if (preparedStatement == null)
			{
				return null;
			}
			
			
			int i = 1;
			preparedStatement.setInt(i++, DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			preparedStatement.setInt(i++, DhSectionPos.getX(pos));
			preparedStatement.setInt(i++, DhSectionPos.getZ(pos));
			
			try (ResultSet result = this.query(preparedStatement))
			{
				if (result == null || !result.next())
				{
					return null;
				}
				
				return result.getLong("LastModifiedUnixDateTime");
			}
		}
		catch (DbConnectionClosedException e)
		{
			return null;
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private final String getTimestampForRangeSql =
			"SELECT PosX, PosZ, LastModifiedUnixDateTime " +
			"FROM " + this.getTableName() + " " +
			"WHERE DetailLevel = ? " +
			"AND PosX BETWEEN ? AND ? " +
			"AND PosZ BETWEEN ? AND ?;";
	public Map<Long, Long> getTimestampsForRange(byte detailLevel, int startPosX, int startPosZ, int endPosX, int endPosZ)
	{
		try(PreparedStatement preparedStatement = this.createPreparedStatement(this.getTimestampForRangeSql))
		{
			if (preparedStatement == null)
			{
				return new HashMap<>();
			}
			
			
			int i = 1;
			preparedStatement.setInt(i++, detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
			preparedStatement.setInt(i++, startPosX);
			preparedStatement.setInt(i++, endPosX - 1);
			preparedStatement.setInt(i++, startPosZ);
			preparedStatement.setInt(i++, endPosZ - 1);
			
			
			try (ResultSet result = this.query(preparedStatement))
			{
				HashMap<Long, Long> returnMap = new HashMap<>();
				while (result != null && result.next())
				{
					long key = DhSectionPos.encode(detailLevel, result.getInt("PosX"), result.getInt("PosZ"));
					long value = result.getLong("LastModifiedUnixDateTime");
					
					returnMap.put(key, value);
				}
				
				return returnMap;
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	//endregion
	
	
	
	//===================//
	// compression tests //
	//===================//
	//region
	
	private final String getAllPositionsSql = 
			"select DetailLevel, PosX, PosZ " +
			"from "+this.getTableName()+"; ";
	/** @return every position in this database */
	public LongArrayList getAllPositions()
	{
		LongArrayList list = new LongArrayList();
		
		try (PreparedStatement statement = this.createPreparedStatement(this.getAllPositionsSql))
		{
			if (statement == null)
			{
				return list;
			}
			
			
			try(ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					byte detailLevel = result.getByte("DetailLevel");
					byte sectionDetailLevel = (byte) (detailLevel + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
					int posX = result.getInt("PosX");
					int posZ = result.getInt("PosZ");
					
					long pos = DhSectionPos.encode(sectionDetailLevel, posX, posZ);
					list.add(pos);
				}
				
				return list;
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	private final String getDataSizeInBytesSql =
			"select LENGTH(Data) as dataSize " +
			"from "+this.getTableName()+" " +
			"WHERE DetailLevel = ? AND PosX = ? AND PosZ = ?";
	/** 
	 * @return the size of the full data at the given position 
	 *          (doesn't include the size of the mapping or any other column)
	 */
	public long getDataSizeInBytes(long pos)
	{
		int detailLevel = DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		
		try (PreparedStatement statement = this.createPreparedStatement(this.getDataSizeInBytesSql))
		{
			if (statement == null)
			{
				return 0L;
			}
		
			int i = 1;
			statement.setInt(i++, detailLevel);
			statement.setInt(i++, DhSectionPos.getX(pos));
			statement.setInt(i++, DhSectionPos.getZ(pos));
			
			
			try (ResultSet result = this.query(statement))
			{
				if (result == null || !result.next())
				{
					return 0L;
				}
				
				return result.getLong("dataSize");
			}
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private final String getTotalDataSizeInBytesSql =
			"select SUM(LENGTH(Data)) as dataSize " +
			"from "+this.getTableName()+"; ";
	/** @return the total size in bytes of the full data for this entire database */
	public long getTotalDataSizeInBytes()
	{
		try (PreparedStatement statement = this.createPreparedStatement(this.getTotalDataSizeInBytesSql);
			ResultSet result = this.query(statement))
		{
			if (result == null || !result.next())
			{
				return 0;
			}
			
			return result.getLong("dataSize");
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static ByteArrayList putAllBytes(@Nullable InputStream inputStream, @Nullable ByteArrayList existingArrayList) throws IOException
	{
		if (existingArrayList == null)
		{
			// inputStream.available() can throw a null pointer due to a bug with LZMA stream so we have to estimate the array size
			existingArrayList = new ByteArrayList(64);
		}
		else
		{
			existingArrayList.clear();
		}
		
		try
		{
			if (inputStream != null)
			{
				int nextByte = inputStream.read();
				while (nextByte != -1)
				{
					existingArrayList.add((byte) nextByte);
					nextByte = inputStream.read();
				}
			}
		}
		catch (EOFException ignore) { /* shouldn't happen, but just in case */ }
		
		return existingArrayList;
	}
	
	//endregion
	
	
	
}
