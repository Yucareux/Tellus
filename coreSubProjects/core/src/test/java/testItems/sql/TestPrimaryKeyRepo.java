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
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class TestPrimaryKeyRepo extends AbstractDhRepo<Integer, TestSingleKeyDto>
{
	
	public TestPrimaryKeyRepo(String databaseType, File databaseFile) throws SQLException, IOException
	{
		super(databaseType, databaseFile, TestSingleKeyDto.class);
		
		// note: this should only ever be done with the test repo.
		// All long term tables should be created using a sql Script.
		String createTableSql = 
				"CREATE TABLE IF NOT EXISTS "+this.getTableName()+"(\n" +
				"Id INT NOT NULL PRIMARY KEY\n" +
				"\n" +
				",Value TEXT NULL\n" +
				",LongValue BIGINT NULL\n" +
				",ByteValue TINYINT NULL\n" +
				");";
		try (PreparedStatement createTableStatement = this.createPreparedStatement(createTableSql);
			ResultSet result = this.query(createTableStatement))
		{
			
		}
	}
	
	
	
	@Override
	public String getTableName() { return "Test"; }
	@Override
	protected String CreateParameterizedWhereString() { return "Id = ?"; }
	
	@Override
	protected int setPreparedStatementWhereClause(PreparedStatement statement, int index, Integer id) throws SQLException
	{
		statement.setInt(index++, id);
		return index;
	}
	
	
	@Override
	@Nullable
	public TestSingleKeyDto convertResultSetToDto(ResultSet result) throws ClassCastException, SQLException
	{
		int id = result.getInt("Id");
		String value = result.getString("Value");
		long longValue = result.getLong("LongValue");
		byte byteValue = result.getByte("ByteValue");
		
		return new TestSingleKeyDto(id, value, longValue, byteValue);
	}
	
	@Override
	public PreparedStatement createUpsertStatement(TestSingleKeyDto dto) throws SQLException
	{
		String sql = 
			"INSERT INTO "+this.getTableName()+" \n" +
			"	(Id, Value, LongValue, ByteValue) \n" +
			"VALUES(?,?,?,?) \n" +
			"ON CONFLICT(Id) DO UPDATE SET\n" +
			"     Value = excluded.Value \n" +
			"    ,LongValue = excluded.LongValue \n" +
			"    ,ByteValue = excluded.ByteValue \n" +
			"";
		PreparedStatement statement = this.createPreparedStatement(sql);
		
		int i = 1; // post-increment for the win!
		statement.setObject(i++, dto.id);
		
		statement.setObject(i++, dto.value);
		statement.setObject(i++, dto.longValue);
		statement.setObject(i++, dto.byteValue);
		
		return statement;
	}
	
	
	
}
