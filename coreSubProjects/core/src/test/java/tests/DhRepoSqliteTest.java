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

package tests;

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.DatabaseUpdater;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.phantoms.AutoClosableTrackingWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import testItems.sql.TestCompoundKeyRepo;
import testItems.sql.TestCompoundKeyDto;
import testItems.sql.TestPrimaryKeyRepo;
import testItems.sql.TestSingleKeyDto;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/**
 * Validates {@link AbstractDhRepo} is set up correctly.
 */
public class DhRepoSqliteTest
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static String DATABASE_TYPE = "jdbc:sqlite";
	public static String DB_FILE_NAME = "test.sqlite";
	
	
	
	@BeforeClass
	public static void testSetup()
	{
		File dbFile = new File(DB_FILE_NAME);
		if (dbFile.exists())
		{
			Assert.assertTrue("unable to delete old test DB File.", dbFile.delete());
		}
	}
	
	
	
	@Test
	public void testPrimaryKeyRepo()
	{
		TestPrimaryKeyRepo primaryKeyRepo = null;
		try
		{
			primaryKeyRepo = new TestPrimaryKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			
			
			
			//==========================//
			// Auto update script tests //
			//==========================//
			
			// check that the schema table is created
			try(PreparedStatement statement = primaryKeyRepo.createPreparedStatement(
					"SELECT name FROM sqlite_master WHERE type='table' AND name='"+DatabaseUpdater.SCHEMA_TABLE_NAME+"';");
				ResultSet autoUpdateTablePresentResult = primaryKeyRepo.query(statement))
			{
				if (autoUpdateTablePresentResult == null
						|| !autoUpdateTablePresentResult.next()
						|| autoUpdateTablePresentResult.getString("name") == null)
				{
					Assert.fail("Auto DB update table missing.");
				}
			}
			
			
			// check that the update scripts aren't run multiple times
			TestPrimaryKeyRepo altDataRepoOne = new TestPrimaryKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			TestPrimaryKeyRepo altDataRepoTwo = new TestPrimaryKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			
			
			
			//===========//
			// DTO tests //
			//===========//
			
			// insert
			TestSingleKeyDto insertDto = new TestSingleKeyDto(0, "a", 0L, (byte) 0);
			primaryKeyRepo.save(insertDto);
			
			// get
			TestSingleKeyDto getDto = primaryKeyRepo.getByKey(0);
			Assert.assertNotNull("get failed, null returned", getDto);
			Assert.assertEquals("get/insert failed, not equal", insertDto, getDto);
			
			// exists - DTO present
			Assert.assertTrue("DTO exists failed", primaryKeyRepo.exists(insertDto));
			Assert.assertTrue("DTO exists failed", primaryKeyRepo.existsWithKey(insertDto.getKey()));
			
			
			// update
			TestSingleKeyDto updateMetaFile = new TestSingleKeyDto(0, "b", Long.MAX_VALUE, Byte.MAX_VALUE);
			primaryKeyRepo.save(updateMetaFile);
			
			// get
			getDto = primaryKeyRepo.getByKey(0);
			Assert.assertNotNull("get failed, null returned", getDto);
			Assert.assertEquals("get/insert failed, not equal", updateMetaFile, getDto);
			
			
			// delete
			primaryKeyRepo.delete(updateMetaFile);
			
			// get
			getDto = primaryKeyRepo.getByKey(0);
			Assert.assertNull("delete failed, not null returned", getDto);
			
			// exists - DTO absent
			Assert.assertFalse("DTO exists failed", primaryKeyRepo.exists(insertDto));
			Assert.assertFalse("DTO exists failed", primaryKeyRepo.existsWithKey(insertDto.getKey()));
			
		}
		catch (SQLException | IOException e)
		{
			Assert.fail(e.getMessage());
		}
		finally
		{
			if (primaryKeyRepo != null)
			{
				primaryKeyRepo.close();
			}
		}
	}
	
	@Test
	public void testCompoundKeyRepo()
	{
		TestCompoundKeyRepo compoundKeyRepo = null;
		try
		{
			compoundKeyRepo = new TestCompoundKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			
			
			
			//===========//
			// DTO tests //
			//===========//
			
			// insert
			TestCompoundKeyDto insertDto = new TestCompoundKeyDto(new DhChunkPos(1, 2), "a");
			compoundKeyRepo.save(insertDto);
			
			// get
			TestCompoundKeyDto getDto = compoundKeyRepo.getByKey(new DhChunkPos(1, 2));
			Assert.assertNotNull("get failed, null returned", getDto);
			Assert.assertEquals("get/insert failed, not equal", insertDto, getDto);
			
			// exists - DTO present
			Assert.assertTrue("DTO exists failed", compoundKeyRepo.exists(insertDto));
			Assert.assertTrue("DTO exists failed", compoundKeyRepo.existsWithKey(insertDto.getKey()));
			
			
			// update
			TestCompoundKeyDto updateMetaFile = new TestCompoundKeyDto(new DhChunkPos(1, 2), "b");
			compoundKeyRepo.save(updateMetaFile);
			
			// get
			getDto = compoundKeyRepo.getByKey(new DhChunkPos(1, 2));
			Assert.assertNotNull("get failed, null returned", getDto);
			Assert.assertEquals("get/insert failed, not equal", updateMetaFile, getDto);
			
			
			// delete
			compoundKeyRepo.delete(updateMetaFile);
			
			// get
			getDto = compoundKeyRepo.getByKey(new DhChunkPos(1, 2));
			Assert.assertNull("delete failed, not null returned", getDto);
			
			// exists - DTO absent
			Assert.assertFalse("DTO exists failed", compoundKeyRepo.exists(insertDto));
			Assert.assertFalse("DTO exists failed", compoundKeyRepo.existsWithKey(insertDto.getKey()));
			
		}
		catch (SQLException | IOException e)
		{
			Assert.fail(e.getMessage());
		}
		finally
		{
			if (compoundKeyRepo != null)
			{
				compoundKeyRepo.close();
			}
		}
	}
	
	/** 
	 * leak detection is done to make sure {@link ResultSet} and {@link PreparedStatement}'s
	 * are properly cleaned up.
	 */
	@Test
	public void testRepoLeakDetection()
	{
		if (!AutoClosableTrackingWrapper.TRACK_WRAPPERS)
		{
			System.out.println("Skipping repo leak detection unit test. Leak tracking is disabled.");
			return;
		}
		
		TestPrimaryKeyRepo primaryKeyRepo = null;
		try
		{
			primaryKeyRepo = new TestPrimaryKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			
			int insertCount = 10;
			int readCount = 10;
			
			
			
			Assert.assertEquals(0, primaryKeyRepo.openClosables.size());
			
			
			//=============================//
			// correctly closed statements //
			//=============================//
			
			{
				// insert
				for (int i = 0; i < insertCount; i++)
				{
					TestSingleKeyDto insertDto = new TestSingleKeyDto(i, "a", 0L, (byte) 0);
					
					try (PreparedStatement statement = primaryKeyRepo.createUpsertStatement(insertDto))
					{
						primaryKeyRepo.query(statement);
						
						if (i % 1_000 == 0)
						{
							System.out.println(i + " / " + insertCount);
						}
					}
				}
				
				Assert.assertEquals("Insert leaks", 0, primaryKeyRepo.openClosables.size());
				
				
				
				// read
				TestSingleKeyDto expectedReadDto = new TestSingleKeyDto(1, "a", 0L, (byte) 0);
				for (int i = 0; i < readCount; i++)
				{
					try (PreparedStatement statement = primaryKeyRepo.createSelectStatementByKey(1);
						ResultSet resultSet = primaryKeyRepo.query(statement))
					{
						TestSingleKeyDto readDto = primaryKeyRepo.convertResultSetToDto(resultSet);
						Assert.assertEquals(expectedReadDto.id, readDto.id);
						
						if (i % 1_000 == 0)
						{
							System.out.println(i + " / " + readCount);
						}
					}
				}
				
				Assert.assertEquals("read leaks", 0, primaryKeyRepo.openClosables.size());
			}
			
			
			
			//===================//
			// leaked statements //
			//===================//
			
			// don't run these tests in release, leaks aren't tracked and the test will fail
			if (AutoClosableTrackingWrapper.TRACK_WRAPPERS)
			{	
				// nuke the DB so we can insert without worries
				primaryKeyRepo.deleteAll();
				
				// insert
				for (int i = 0; i < insertCount; i++)
				{
					TestSingleKeyDto insertDto = new TestSingleKeyDto(i, "a", 0L, (byte) 0);
					PreparedStatement statement = primaryKeyRepo.createUpsertStatement(insertDto);
					primaryKeyRepo.query(statement);
					
					if (i % 1_000 == 0)
					{
						System.out.println(i + " / " + insertCount);
					}
				}
				
				Assert.assertNotEquals(0, primaryKeyRepo.openClosables.size());
				primaryKeyRepo.openClosables.clear();
				
				
				
				// read
				for (int i = 0; i < readCount; i++)
				{
					PreparedStatement statement = primaryKeyRepo.createSelectStatementByKey(1);
					ResultSet resultSet = primaryKeyRepo.query(statement);
					
					TestSingleKeyDto readDto = primaryKeyRepo.convertResultSetToDto(resultSet);
					Assert.assertEquals(1, readDto.id);
					
					if (i % 1_000 == 0)
					{
						System.out.println(i + " / " + readCount);
					}
				}
				
				Assert.assertNotEquals(0, primaryKeyRepo.openClosables.size());
			}
		}
		catch (SQLException | IOException e)
		{
			Assert.fail(e.getMessage());
		}
		finally
		{
			if (primaryKeyRepo != null)
			{
				primaryKeyRepo.close();
			}
		}
	}
	
	
	// can be uncommented to compare different update methods performance
	//@Test
	public void testBulkUpdatePerformance()
	{
		TestPrimaryKeyRepo primaryKeyRepo = null;
		try
		{
			primaryKeyRepo = new TestPrimaryKeyRepo(DATABASE_TYPE, new File(DB_FILE_NAME));
			final TestPrimaryKeyRepo finalRepoRef = primaryKeyRepo;
			
			
			long startMs = System.currentTimeMillis();
			
			// run two threads that try to update
			// the same DTO to test locks
			CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> bulkSameDtoUpdate(finalRepoRef, 0, 2_000));
			CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> bulkSameDtoUpdate(finalRepoRef, 2_000, 4_000));
			
			CompletableFuture.allOf(f1, f2).join();
			
			long endMs = System.currentTimeMillis();
			System.out.println("Bulk update took ["+(endMs - startMs)+"] ms");
		}
		catch (SQLException | IOException e)
		{
			Assert.fail(e.getMessage());
		}
		finally
		{
			if (primaryKeyRepo != null)
			{
				primaryKeyRepo.close();
			}
		}
	}
	private static void bulkSameDtoUpdate(TestPrimaryKeyRepo repo, int startIndex, int endIndex)
	{
		TestSingleKeyDto dto = new TestSingleKeyDto(0, "a", 0L, (byte) 0);
		for (int i = startIndex; i < endIndex; i++)
		{
			dto.longValue = i;
			repo.save(dto);
		}
	}
	
	
	
	
}
