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

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.sql.DatabaseUpdater;
import com.seibel.distanthorizons.core.sql.DbConnectionClosedException;
import com.seibel.distanthorizons.core.sql.DbCorruptedException;
import com.seibel.distanthorizons.core.sql.dto.IBaseDTO;
import com.seibel.distanthorizons.core.sql.repo.phantoms.AutoClosableTrackingWrapper;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles interfacing with SQL databases.
 * 
 * @param <TDTO> DTO stands for "Data Transfer Object" 
 */
public abstract class AbstractDhRepo<TKey, TDTO extends IBaseDTO<TKey>> implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static final String DEFAULT_DATABASE_TYPE = "jdbc:sqlite";
	/** 
	 * a value of 0 is supposed to mean there is no timeout
	 * but this doesn't appear to be the case for SQLite,
	 * so we're just setting a large value and calling it a day.
	 */
	public static final int TIMEOUT_SECONDS = 600;
	
	private static final ConcurrentHashMap<AbstractDhRepo<?, ?>, String> ACTIVE_CONNECTION_STRINGS_BY_REPO = new ConcurrentHashMap<>();
	private static final Set<String> CORRUPTED_DB_PATHS = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	/** 
	 * Should only be used for performance testing. <br>
	 * Nothing is saved to the disk (duh).
	 */
	private static final boolean USE_MEMORY_DATABASE = false;
	
	
	
	private final String connectionString;
	private final String getConnectionString() { return this.connectionString; }
	private final ConcurrentHashMap<Thread, Connection> connectionByThread = new ConcurrentHashMap<>();
	
	public final String databaseType;
	public final File databaseFile;
	
	public final Set<AutoClosableTrackingWrapper> openClosables = ConcurrentHashMap.newKeySet();
	
	public final Class<? extends TDTO> dtoClass;
	
	private final AtomicBoolean databaseCorruptedRef = new AtomicBoolean(false);
	private final AtomicBoolean repoClosedRef = new AtomicBoolean(false);
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	/** @throws SQLException if the repo is unable to access the database or has trouble updating said database. */
	public AbstractDhRepo(String databaseType, File databaseFile, Class<? extends TDTO> dtoClass) throws SQLException, IOException
	{
		this.databaseType = databaseType;
		this.databaseFile = databaseFile;
		this.dtoClass = dtoClass;
		
		
		try
		{
			// needed by Forge to load the Java database connection
			Class.forName("org.sqlite.JDBC");	
		}
		catch (ClassNotFoundException e)
		{
			throw new RuntimeException(e);
		}
		
		
		
		//==========================//
		// database file validation //
		//==========================//

		if (USE_MEMORY_DATABASE)
		{
			this.connectionString = "jdbc:sqlite:file:"+this.databaseFile.getPath()+"?mode=memory&cache=shared";
		}
		else
		{
			// check that the database file exists
			if (!databaseFile.exists())
			{
				// check that the parent folder exists
				File parentFolder = databaseFile.getParentFile();
				if (parentFolder != null && !parentFolder.exists())
				{
					if (!parentFolder.mkdirs())
					{
						throw new IOException("Unable to create the necessary parent folders for the database file at location [" + databaseFile.getPath() + "].");
					}
				}
				
				if (!databaseFile.exists())
				{
					try
					{
						boolean fileCreated = databaseFile.createNewFile();
					}
					catch (IOException e)
					{
						throw new IOException("Unable to create database file at location [" + databaseFile.getPath() + "] due to error: [" + e.getMessage() + "]", e);
					}
				}
			}
			
			if (!databaseFile.exists())
			{
				String databaseFilePath = databaseFile.getPath();
				
				String windowsLongFileWarning = "";
				// windows has issues at 260 characters, but checking a few characters shorter should make sure we catch this issue
				if (databaseFilePath.length() > 250
					&& EPlatform.get() == EPlatform.WINDOWS)
				{
					// print a message to chat for people who don't know how to access the log
					String message =
						MinecraftTextFormat.DARK_RED + "Distant Horizons: File Path Length Issue." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
							"A file path was [" + databaseFilePath.length() + "] characters long. \n" +
							"Windows only supports file paths up to 260 chars normally. \n" +
							"Please enable long file paths in Windows. \n";
					ClientApi.INSTANCE.queueSlowChatMessage(message);
					
					// add additional info to the log
					windowsLongFileWarning = "Potential fix: enable long file paths in Windows.";
				}
				
				throw new IOException("Unable to create database file at location [" + databaseFile.getPath() + "], please make sure the folder and file has the correct permissions. " + windowsLongFileWarning);
			}
			if (!databaseFile.canRead())
			{
				throw new IOException("Unable to read database file at location [" + databaseFile.getPath() + "], please make sure the folder and file has the correct permissions.");
			}
			if (!databaseFile.canWrite())
			{
				throw new IOException("Unable to write database file at location [" + databaseFile.getPath() + "], please make sure the folder and file aren't set to read-only.");
			}
			
			this.connectionString = this.databaseType+":"+this.databaseFile.getPath();
		}
		
		
		
		ACTIVE_CONNECTION_STRINGS_BY_REPO.put(this, this.connectionString);
		
		DatabaseUpdater.runAutoUpdateScripts(this);
	}
	
	//endregion
	
	
	
	//==================//
	// abstract methods //
	//==================//
	//region
	
	public abstract String getTableName();
	
	@Nullable
	public abstract TDTO convertResultSetToDto(ResultSet resultSet) throws ClassCastException, IOException, SQLException;
	
	/** should not start with WHERE */
	protected abstract String CreateParameterizedWhereString();
	
	protected void setPreparedStatementWhereClause(PreparedStatement statement, TKey key) throws SQLException { this.setPreparedStatementWhereClause(statement, 1, key); }
	protected abstract int setPreparedStatementWhereClause(PreparedStatement statement, int parameterIndex, TKey key) throws SQLException;
	
	/**
	 * upsert = update/insert <br>
	 * This is slightly faster than checking if the DTO exists
	 * and then doing one or the other.
	 */
	@Nullable
	public abstract PreparedStatement createUpsertStatement(TDTO dto) throws SQLException;
	
	//endregion
	
	
	
	//===============//
	// high level DB //
	//===============//
	//region
	
	public TDTO getByKey(TKey primaryKey)
	{
		try(PreparedStatement statement = this.createSelectStatementByKey(primaryKey);
			ResultSet resultSet = this.query(statement))
		{
			if (resultSet != null && resultSet.next())
			{
				return this.convertResultSetToDto(resultSet);
			}
			else 
			{
				return null;
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
				LOGGER.warn("Unexpected issue deserializing DTO ["+this.dtoClass.getSimpleName()+"] with primary key ["+primaryKey+"]. Error: ["+e.getMessage()+"].", e);	
			}
			return null;
		}
	}
	
	
	public void save(TDTO dto)
	{
		try(PreparedStatement statement = this.createUpsertStatement(dto);
			ResultSet result = this.query(statement))
		{
		}
		catch (DbConnectionClosedException ignored)
		{
			//LOGGER.warn("Attempted to insert ["+this.dtoClass.getSimpleName()+"] with primary key ["+(dto != null ? dto.getKeyDisplayString() : "NULL")+"] on closed repo ["+this.connectionString+"].");
		}
		catch (SQLException e)
		{
			String message = "Unexpected DTO save error: ["+e.getMessage()+"].";
			LOGGER.error(message);
			throw new RuntimeException(message, e);
		}
	}
	
	
	public void delete(TDTO dto) { this.deleteWithKey(dto.getKey()); }
	public void deleteWithKey(TKey key) 
	{
		try (PreparedStatement statement = this.createDeleteStatementByKey(key);
			ResultSet result = this.query(statement))
		{
			
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
		//finally
		//{
		//	this.tryTriggerWalFlush();
		//}
	}
	
	/** With great power comes great responsibility... */
	public void deleteAll() 
	{ 
		String sql = "DELETE FROM " + this.getTableName();
		try (PreparedStatement statement = this.createPreparedStatement(sql);
			ResultSet result = this.query(statement))
		{
			
		}
		catch (SQLException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	
	public boolean exists(TDTO dto) { return this.existsWithKey(dto.getKey()); }
	public boolean existsWithKey(TKey key) 
	{
		try
		{
			try (PreparedStatement statement = this.createExistsStatementByKey(key);
				ResultSet result = this.query(statement))
			{
				return result != null && result.getInt("existingCount") != 0;
			}
		}
		catch (SQLException e)
		{
			return false;
		}
	}
	
	//endregion
	
	
	
	//==============//
	// low level DB //
	//==============//
	//region
	
	/** 
	 * This can only run 1 command at a time. <br><br>
	 * 
	 * Note: {@link AbstractDhRepo#query(PreparedStatement)} with a {@link PreparedStatement}
	 * should be used if the query will be run often.
	 * This reduces GC pressure due to the {@link String} and {@link Map} allocation cost.
	 */
	@Nullable
	public Map<String, Object> queryDictionaryFirst(String sql) 
	{
		try
		{
			List<Map<String, Object>> objectList = this.queryDictionary(sql);
			return !objectList.isEmpty() ? objectList.get(0) : null;
		}
		catch (DbConnectionClosedException e)
		{
			return null;
		}
	}
	/**
	 * This can only run 1 command at a time. <br><br>
	 * 
	 * Note: {@link AbstractDhRepo#query(PreparedStatement)} with a {@link PreparedStatement}
	 * should be used if the query will be run often.
	 * This reduces GC pressure due to the {@link String} and {@link Map} allocation cost.
	 */
	private List<Map<String, Object>> queryDictionary(String sql) throws RuntimeException, DbConnectionClosedException
	{
		Connection connection = this.getConnection();
		if (connection == null)
		{
			return new ArrayList<>();
		}
		
		try (Statement statement = connection.createStatement())
		{
			statement.setQueryTimeout(TIMEOUT_SECONDS);
			
			// Note: this can only handle 1 command at a time
			boolean resultSetPresent = statement.execute(sql);
			try (ResultSet resultSet = AutoClosableTrackingWrapper.wrap(ResultSet.class, statement.getResultSet(), this.openClosables))
			{
				return this.convertResultSetToDictionaryList(resultSet, resultSetPresent);
			}
		}
		catch(SQLException e)
		{
			// SQL exceptions generally only happen when something is wrong with 
			// the database or the query and should cause the system to blow up to notify the developer
			
			if (DbConnectionClosedException.isClosedException(e))
			{
				return new ArrayList<>();
			}
			else
			{
				String message = "Unexpected Query error: [" + e.getMessage() + "], for script: [" + sql + "].";
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			}
		}
	}
	
	
	
	/** 
	 * Warning: both the returned {@link ResultSet} and incoming {@link PreparedStatement} 
	 * must be wrapped in a try-resource block to prevent memory
	 * leaks and issues with the DB becoming locked.
	 */
	@Nullable
	public ResultSet query(@Nullable PreparedStatement statement) throws RuntimeException
	{
		// if the DB is corrupted act as if it's closed
		// that should prevent further harm
		if (this.databaseCorruptedRef.get())
		{
			return null;
		}
		
		// This is done so we don't have to add "if null" checks everywhere.
		// Normally this should only happen once the DB has been closed.
		if (statement == null)
		{
			return null;
		}
		
		// don't let new queries start once the repo has been shut down
		if (this.repoClosedRef.get())
		{
			return null;
		}
		
		
		
		try
		{
			statement.setQueryTimeout(TIMEOUT_SECONDS);
			
			// Note: this can only handle 1 command at a time
			boolean resultSetPresent = statement.execute();
			if (resultSetPresent)
			{
				ResultSet resultSet = statement.getResultSet(); 
				return AutoClosableTrackingWrapper.wrap(ResultSet.class, resultSet, this.openClosables);
			}
			else
			{
				return null;
			}
		}
		catch(SQLException e)
		{
			// SQL exceptions generally only happen when something is wrong with 
			// the database or the query and should cause the system to blow up to notify the developer
			
			if (DbConnectionClosedException.isClosedException(e))
			{
				return null;
			}
			else if (DbCorruptedException.isCorruptedException(e))
			{
				// error may trigger on multiple threads at once
				synchronized (this)
				{
					// only trigger this error once per database
					if (!this.databaseCorruptedRef.getAndSet(true)
						// multiple repos may be open for the same path (client and server levels in singleplayer)
						&& CORRUPTED_DB_PATHS.add(this.databaseFile.getPath()))
					{
						LOGGER.error("DH database file at [" + this.databaseFile.getPath() + "] is corrupted. \n" +
							"All operations to this DB are disabled, DH may behave strangely if you continue playing. \n" +
							"Please leave the world and delete the corrupted database file to fix. \n" +
							"Error: [" + e.getMessage() + "]", e);
						
						ClientApi.INSTANCE.queueSlowChatMessage(
							MinecraftTextFormat.DARK_RED + MinecraftTextFormat.BOLD + "DH database is corrupted." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								"DH will behave strangely if your continue playing. \n" +
								"Please leave the world and delete the corrupted database file at: \n" +
								MinecraftTextFormat.YELLOW + this.databaseFile.getPath() + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
								"to resolve the issue. \n"
						);
					}
				}
				
				return null;
			}
			else
			{
				String message = "Unexpected Query error: [" + e.getMessage() + "], for prepared statement: [" + statement + "].";
				LOGGER.error(message, e);
				throw new RuntimeException(message, e);
			}
		}
	}
	
	
	
	/** 
	 * @return Null if the database was closed
	 * @throws RuntimeException if there was a problem with the given SQL string
	 */
	@Nullable
	public PreparedStatement createPreparedStatement(String sql) throws RuntimeException
	{
		if (this.repoClosedRef.get())
		{
			return null;
		}
		
		try
		{
			PreparedStatement statement = this.getConnection().prepareStatement(sql);
			statement.setQueryTimeout(TIMEOUT_SECONDS);
			return AutoClosableTrackingWrapper.wrap(PreparedStatement.class, statement, this.openClosables);
		}
		catch(SQLException e)
		{
			if (DbConnectionClosedException.isClosedException(e))
			{
				return null;
			}
			else
			{
				// SQL exceptions generally only happen when something is wrong with 
				// the database or the query and should cause the system to blow up to notify the developer
				
				String message = "Unexpected error: [" + e.getMessage() + "], preparing statement: [" + sql + "].";
				LOGGER.error(message);
				throw new RuntimeException(message, e);
			}
		}
	}
	
	//endregion
	
	
	
	//=============//
	// connections //
	//=============//
	//region
	
	/** Will be null if the repo is closed */
	@Nullable
	public Connection getConnection() 
	{
		// don't let new connections/queries happen once the repo has been closed 
		if (this.repoClosedRef.get())
		{
			return null;
		}
		
		Thread thread = Thread.currentThread();
		return this.connectionByThread.computeIfAbsent(thread, (Thread newThread) ->
		{
			try
			{
				Connection connection = DriverManager.getConnection(this.getConnectionString());
				
				try (Statement statement = connection.createStatement())
				{
					statement.setQueryTimeout(TIMEOUT_SECONDS);
					
					// the synchronization mode must be set for each connection
					int syncMode = Config.Common.LodBuilding.databaseSyncMode.get().value;
					statement.execute("PRAGMA synchronous = "+syncMode+";");
				}
				
				return connection;
			}
			catch (SQLException e)
			{
				LOGGER.error("Failed to get connection for thread ["+newThread.getName()+"] with connection string ["+this.getConnectionString()+"].", e);
				throw new RuntimeException(e);
			}
		});
	}
	
	//endregion
	
	
	
	//====================//
	// connection closing //
	//====================//
	//region
	
	@Override
	public void close()
	{
		this.repoClosedRef.set(true);
		
		try
		{
			// close this repo's connections
			Enumeration<Thread> threads = this.connectionByThread.keys();
			while (threads.hasMoreElements())
			{
				Thread thread = threads.nextElement();
				Connection connection = this.connectionByThread.remove(thread);
				if (connection == null)
				{
					// shouldn't happen, but just in case
					continue;
				}
				
				
				// wait a few moments for any last queries to finish
				int waitCount = 0;
				while (this.openClosables.size() != 0
					// wait up to 1 second for in-progress queries to finish (hopefully they'll finish in much less time then that)
					&& waitCount < 10)
				{
					waitCount++;
					try { Thread.sleep(100); } catch (Exception ignore) { }
				}
				
				
				// log any leaked objects
				int openClosableCount = this.openClosables.size();
				if (openClosableCount != 0)
				{
					LOGGER.warn("[" + openClosableCount + "] objects not closed for repo [" + this.getClass().getSimpleName() + "]-[" + this.getTableName() + "] with connection: [" + this.connectionString + "]. A memory leak may be present and closing this connection may take longer than normal.");
					
					// header
					StringBuilder stringBuilder = new StringBuilder();
					stringBuilder.append("Unclosed objects: \n");
					
					// leaked objects
					HashMap<String, AtomicInteger> unclosedObjectCountsByString = this.getUnclosedObjectStringsAndCounts();
					for (String objString : unclosedObjectCountsByString.keySet())
					{
						AtomicInteger countRef = unclosedObjectCountsByString.get(objString);
						if (countRef != null)
						{
							stringBuilder.append("[" + countRef.get() + "] - [" + objString + "] \n");
						}
					}
					
					LOGGER.warn(stringBuilder.toString());
				}
				
				try
				{
					// don't try closing an already closed connection
					if (!connection.isClosed())
					{
						LOGGER.debug("Closing database connection: [" + this.connectionString + "]-[" + thread.getName() + "]...");
						connection.close();
						LOGGER.debug("Finished closing database connection: [" + this.connectionString + "]-[" + thread.getName() + "].");
					}
				}
				catch (SQLException e)
				{
					// connection close failed.
					LOGGER.error("Unable to close the connection [" + this.connectionString + "], error: [" + e.getMessage() + "]");
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected issue closing repo with connection [" + this.connectionString + "], error: [" + e.getMessage() + "]");
			throw new RuntimeException(e);
		}
		
		// mark this repo as deactivated
		ACTIVE_CONNECTION_STRINGS_BY_REPO.remove(this);
	}
	
	/** can be used to make sure everything is closed when the world closes */
	public static void closeAllConnections()
	{
		LOGGER.info("Closing all ["+ACTIVE_CONNECTION_STRINGS_BY_REPO.size()+"] databases...");
		
		Enumeration<AbstractDhRepo<?, ?>> repos = ACTIVE_CONNECTION_STRINGS_BY_REPO.keys();
		while (repos.hasMoreElements())
		{
			AbstractDhRepo<?, ?> repo = repos.nextElement();
			ACTIVE_CONNECTION_STRINGS_BY_REPO.remove(repo);
			
			try
			{
				repo.close();
			}
			catch(Exception e)
			{
				// connection close failed.
				LOGGER.error("Unable to close the repo ["+repo.connectionString+"], error: ["+e.getMessage()+"]");
			}
		}
		
		// should be empty, but just in case.
		ACTIVE_CONNECTION_STRINGS_BY_REPO.clear();
		
		// clear the errors so they can be re-fired if needed
		CORRUPTED_DB_PATHS.clear();
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private List<Map<String, Object>> convertResultSetToDictionaryList(ResultSet resultSet, boolean resultSetPresent) throws SQLException
	{
		if (resultSetPresent)
		{
			List<Map<String, Object>> resultList = convertResultSetToDictionaryList(resultSet);
			resultSet.close();
			return resultList;
		}
		else
		{
			if (resultSet != null)
			{
				resultSet.close();
			}
			
			return new ArrayList<>();
		}
	}
	private static List<Map<String, Object>> convertResultSetToDictionaryList(ResultSet resultSet) throws SQLException
	{
		List<Map<String, Object>> list = new ArrayList<>();
		
		ResultSetMetaData resultMetaData = resultSet.getMetaData();
		int resultColumnCount = resultMetaData.getColumnCount();
		
		while (resultSet.next())
		{
			HashMap<String, Object> object = new HashMap<>();
			for (int columnIndex = 1; columnIndex <= resultColumnCount; columnIndex++) // column indices start at 1
			{
				String columnName = resultMetaData.getColumnName(columnIndex);
				if (columnName == null || columnName.isEmpty())
				{
					throw new RuntimeException("SQL result set is missing a column name for column ["+resultMetaData.getTableName(columnIndex)+"."+columnIndex+"].");
				}
				
				
				// some values need explicit conversion
				// Example: Long values that are within the bounds of an int would automatically be incorrectly returned as "Integer" objects
				String columnType = resultMetaData.getColumnTypeName(columnIndex).toUpperCase();
				Object columnValue;
				switch (columnType)
				{
					case "BIGINT":
						columnValue = resultSet.getLong(columnIndex);
						break;
					case "SMALLINT":
						columnValue = resultSet.getShort(columnIndex);
						break;
					case "TINYINT":
						columnValue = resultSet.getByte(columnIndex);
						break;
					default:
						columnValue = resultSet.getObject(columnIndex);
						break;
				}
				
				
				object.put(columnName, columnValue);
			}
			
			list.add(object);
		}
		
		return list;
	}
	
	/** used for logging leaked objects */
	public HashMap<String, AtomicInteger> getUnclosedObjectStringsAndCounts()
	{
		HashMap<String, AtomicInteger> closableCountsByToString = new HashMap<>();
		
		for (AutoClosableTrackingWrapper closableWrapper : this.openClosables)
		{
			// custom to-strings for better merging
			String str = closableWrapper.wrappedClosable.getClass().getSimpleName();
			if (closableWrapper.wrappedClosable instanceof ResultSet)
			{
				str += " @ " + closableWrapper.wrappedClosable.toString();
			}
			else if (closableWrapper.wrappedClosable instanceof PreparedStatement)
			{
				String sql = closableWrapper.wrappedClosable.toString();
				int parametersIndex = sql.indexOf("\n parameters="); // remove Sqlite parameters so queries aren't separated by properties
				if (parametersIndex != -1)
				{
					sql = sql.substring(0, parametersIndex);
				}
				str += " @ " + sql;
			}
			else
			{
				str += " @ " + closableWrapper.wrappedClosable.toString();
			}
			
			
			closableCountsByToString.compute(str, (stringVal, countRef) ->
			{
				if (countRef == null)
				{
					countRef = new AtomicInteger(0);
				}
				countRef.incrementAndGet();
				return countRef;
			});
		}
		
		return closableCountsByToString;
	}
	
	private String selectSqlTemplate = null;
	public PreparedStatement createSelectStatementByKey(TKey key) throws SQLException
	{
		// create shared template string
		if (this.selectSqlTemplate == null)
		{
			this.selectSqlTemplate = "SELECT * FROM "+this.getTableName() + " WHERE " + this.CreateParameterizedWhereString();
		}
		
		PreparedStatement statement = this.createPreparedStatement(this.selectSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		this.setPreparedStatementWhereClause(statement, key);
		
		return statement;
	}
	
	private String existsSqlTemplate = null;
	public PreparedStatement createExistsStatementByKey(TKey key) throws SQLException
	{
		// create shared template string
		if (this.existsSqlTemplate == null)
		{
			this.existsSqlTemplate = "SELECT EXISTS(SELECT 1 FROM "+this.getTableName()+" WHERE "+this.CreateParameterizedWhereString()+") as 'existingCount'";
		}
		
		PreparedStatement statement = this.createPreparedStatement(this.existsSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		this.setPreparedStatementWhereClause(statement, key);
		
		return statement;
	}
	
	private String deleteSqlTemplate = null;
	public PreparedStatement createDeleteStatementByKey(TKey key) throws SQLException
	{
		// create shared template string
		if (this.deleteSqlTemplate == null)
		{
			this.deleteSqlTemplate = "DELETE FROM "+this.getTableName()+" WHERE " + this.CreateParameterizedWhereString();
		}
		
		PreparedStatement statement = this.createPreparedStatement(this.deleteSqlTemplate);
		if (statement == null)
		{
			return null;
		}
		this.setPreparedStatementWhereClause(statement, key);
		
		return statement;
	}
	
	//endregion
	
	
	
}
