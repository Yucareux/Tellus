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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.dto.ChunkHashDTO;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ChunkHashRepo extends AbstractDhRepo<DhChunkPos, ChunkHashDTO>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ChunkHashRepo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, ChunkHashDTO.class);
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override 
	public String getTableName() { return "ChunkHash"; }
	
	@Override
	protected String CreateParameterizedWhereString() { return "ChunkPosX = ? AND ChunkPosZ = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, DhChunkPos pos) throws SQLException
	{
		statement.setInt(index++, pos.getX());
		statement.setInt(index++, pos.getZ());
		return index;
	}
	
	
	
	//=======================//
	// repo required methods //
	//=======================//
	
	@Override
	@Nullable
	public ChunkHashDTO convertResultSetToDto(ResultSet resultSet) throws ClassCastException, SQLException
	{
		int posX = resultSet.getInt("ChunkPosX");
		int posZ = resultSet.getInt("ChunkPosZ");
		
		int chunkHash = resultSet.getInt("ChunkHash");
		
		
		ChunkHashDTO dto = new ChunkHashDTO(new DhChunkPos(posX, posZ), chunkHash);
		return dto;
	}
	
	private final String upsertSqlTemplate =
		"INSERT INTO "+this.getTableName() + " (\n" +
		"   ChunkPosX, ChunkPosZ, \n" +
		"   ChunkHash, \n" +
		"   LastModifiedUnixDateTime, CreatedUnixDateTime) \n" +
		"VALUES( \n" +
		"    ?, ?, \n" +
		"    ?, \n" +
		"    ?, ? \n" +
		") \n" +
		"ON CONFLICT(ChunkPosX, ChunkPosZ) DO UPDATE SET \n" +
		"    ChunkHash = excluded.ChunkHash \n" +
		"   ,LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime";
	@Override
	public PreparedStatement createUpsertStatement(ChunkHashDTO dto) throws SQLException
	{
		PreparedStatement statement = this.createPreparedStatement(this.upsertSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		
		
		int i = 1;
		statement.setObject(i++, dto.pos.getX());
		statement.setObject(i++, dto.pos.getZ());
		
		statement.setObject(i++, dto.chunkHash);
		
		statement.setObject(i++, System.currentTimeMillis()); // last modified unix time
		statement.setObject(i++, System.currentTimeMillis()); // created unix time
		
		return statement;
	}
	
	
	
}
