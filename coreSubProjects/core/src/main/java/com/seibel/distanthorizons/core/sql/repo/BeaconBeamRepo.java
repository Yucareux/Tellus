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

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.DbConnectionClosedException;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class BeaconBeamRepo extends AbstractDhRepo<DhBlockPos, BeaconBeamDTO>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconBeamRepo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, BeaconBeamDTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "BeaconBeam"; }
	
	@Override
	protected String CreateParameterizedWhereString() { return "BlockPosX = ? AND BlockPosY = ? AND BlockPosZ = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, DhBlockPos pos) throws SQLException
	{
		statement.setInt(index++, pos.getX());
		statement.setInt(index++, pos.getY());
		statement.setInt(index++, pos.getZ());
		return index;
	}
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override 
	@Nullable
	public BeaconBeamDTO convertResultSetToDto(ResultSet resultSet) throws ClassCastException, SQLException
	{
		int posX = resultSet.getInt("BlockPosX");
		int posY = resultSet.getInt("BlockPosY");
		int posZ = resultSet.getInt("BlockPosZ");
		
		int red = resultSet.getInt("ColorR");
		int green = resultSet.getInt("ColorG");
		int blue = resultSet.getInt("ColorB");
		
		
		BeaconBeamDTO dto = new BeaconBeamDTO(new DhBlockPos(posX, posY, posZ), new Color(red, green, blue));
		return dto;
	}
	
	private final String upsertSqlTemplate =
		"INSERT INTO "+this.getTableName() + " (\n" +
		"   BlockPosX, BlockPosY, BlockPosZ, \n" +
		"   ColorR, ColorG, ColorB, \n" +
		"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
		"VALUES( \n" +
		"    ?, ?, ?, \n" +
		"    ?, ?, ?, \n" +
		"    ?, ? \n" +
		") \n" +
		"ON CONFLICT(BlockPosX, BlockPosY, BlockPosZ) DO UPDATE SET\n" +
		"    ColorR = excluded.ColorR \n" +
		"   ,ColorG = excluded.ColorG \n" +
		"   ,ColorB = excluded.ColorB \n" +
		"   ,LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime";
	@Override 
	public @Nullable PreparedStatement createUpsertStatement(BeaconBeamDTO dto) throws SQLException
	{
		PreparedStatement statement = this.createPreparedStatement(this.upsertSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		
		
		int i = 1;
		statement.setInt(i++, dto.blockPos.getX());
		statement.setInt(i++, dto.blockPos.getY());
		statement.setInt(i++, dto.blockPos.getZ());
		
		statement.setInt(i++, dto.color.getRed());
		statement.setInt(i++, dto.color.getGreen());
		statement.setInt(i++, dto.color.getBlue());
		
		statement.setLong(i++, System.currentTimeMillis()); // last modified unix time
		statement.setLong(i++, System.currentTimeMillis()); // created unix time
		
		return statement;
	}
	
	
	
	//====================//
	// additional methods //
	//====================//
	
	public List<BeaconBeamDTO> getAllBeamsForPos(DhChunkPos chunkPos)
	{
		int minBlockX = chunkPos.getMinBlockX();
		int minBlockZ = chunkPos.getMinBlockZ();
		int maxBlockX = minBlockX + LodUtil.CHUNK_WIDTH;
		int maxBlockZ = minBlockZ + LodUtil.CHUNK_WIDTH;
		
		return this.getAllBeamsInBlockPosRange(
				minBlockX, maxBlockX,
				minBlockZ, maxBlockZ
		);
	}
	
	public ArrayList<BeaconBeamDTO> getAllBeamsForPos(long pos)
	{
		int minBlockX = DhSectionPos.getMinCornerBlockX(pos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(pos);
		int maxBlockX = minBlockX + DhSectionPos.getBlockWidth(pos);
		int maxBlockZ = minBlockZ + DhSectionPos.getBlockWidth(pos);
		
		return this.getAllBeamsInBlockPosRange(
				minBlockX, maxBlockX,
				minBlockZ, maxBlockZ
			);
	}
	
	private final String getAllBeamsInRangeTemplate =
			"SELECT * " +
			"FROM "+this.getTableName()+" " +
			"WHERE " +
			"? <= BlockPosX AND BlockPosX <= ? AND " +
			"? <= BlockPosZ AND BlockPosZ <= ?";
	public ArrayList<BeaconBeamDTO> getAllBeamsInBlockPosRange(
			int minBlockX, int maxBlockX,
			int minBlockZ, int maxBlockZ
		)
	{
		ArrayList<BeaconBeamDTO> beamList = new ArrayList<>();
		
		try(PreparedStatement statement = this.createPreparedStatement(this.getAllBeamsInRangeTemplate))
		{
			if(statement == null)
			{
				return beamList;
			}
			
			int i = 1;
			statement.setInt(i++, minBlockX);
			statement.setInt(i++, maxBlockX);
			statement.setInt(i++, minBlockZ);
			statement.setInt(i++, maxBlockZ);
			
			
			try (ResultSet result = this.query(statement))
			{
				while (result != null && result.next())
				{
					beamList.add(this.convertResultSetToDto(result));
				}
			}
		}
		catch (Exception e)
		{
			// done to handle resultSet.get() methods which can throw closed exceptions
			if (e instanceof SQLException
				&& DbConnectionClosedException.isClosedException((SQLException)e))
			{
				return beamList;
			}
			
			throw new RuntimeException(e);
		}
		
		return beamList;
	}
	
	
}
