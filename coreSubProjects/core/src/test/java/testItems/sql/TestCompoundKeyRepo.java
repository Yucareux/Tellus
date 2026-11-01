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

package testItems.sql;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class TestCompoundKeyRepo extends AbstractDhRepo<DhChunkPos, TestCompoundKeyDto>
{
	
	public TestCompoundKeyRepo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, TestCompoundKeyDto.class);
		
		// note: this should only ever be done with the test repo.
		// All long term tables should be created using a sql Script.
		String createTableSql = 
				"CREATE TABLE IF NOT EXISTS "+this.getTableName()+"(\n" +
				"XPos INT NOT NULL\n" +
				",ZPos INT NOT NULL\n" +
				"\n" +
				",Value TEXT NULL\n" +
				"\n" +
				",PRIMARY KEY (XPos, ZPos)" +
				");";
		try (PreparedStatement createTableStatement = this.createPreparedStatement(createTableSql);
			 ResultSet result = this.query(createTableStatement))
		{
			
		}
	}
	
	
	@Override
	public String getTableName() { return "TestCompound"; }
	@Override
	protected String CreateParameterizedWhereString() { return "XPos = ? AND ZPos = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, DhChunkPos pos) throws SQLException
	{
		statement.setInt(index++, pos.getX());
		statement.setInt(index++, pos.getZ());
		return index;
	}
	
	
	
	@Override
	@Nullable
	public TestCompoundKeyDto convertResultSetToDto(ResultSet result) throws ClassCastException, SQLException
	{
		int xPos = result.getInt("XPos");
		int zPos = result.getInt("ZPos");
		String value = result.getString("Value");
		
		return new TestCompoundKeyDto(new DhChunkPos(xPos, zPos), value);
	}
	
	@Override
	public PreparedStatement createUpsertStatement(TestCompoundKeyDto dto) throws SQLException
	{
		String sql = 
			"INSERT INTO "+this.getTableName()+" \n" +
				"(XPos, ZPos, Value) \n" +
			"VALUES(?,?,?) \n" +
			"ON CONFLICT(XPos, ZPos) DO UPDATE SET" +
			"   Value = excluded.Value"
			;
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1; // post-increment for the win!
		statement.setObject(i++, dto.id.getX());
		statement.setObject(i++, dto.id.getZ());
		
		statement.setObject(i++, dto.value);
		
		return statement;
	}
	
	
	
}
