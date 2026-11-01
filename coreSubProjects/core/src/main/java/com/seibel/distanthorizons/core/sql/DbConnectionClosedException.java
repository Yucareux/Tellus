package com.seibel.distanthorizons.core.sql;

import java.sql.SQLException;

/** 
 * Used to simplify handling when a database has been closed
 * since Java doesn't have a specific exception to handle closed databases 
 */
public class DbConnectionClosedException extends SQLException
{
	public DbConnectionClosedException() { super("The database connection is closed."); }
	public DbConnectionClosedException(String message) { super(message); }
	public DbConnectionClosedException(String message, Throwable cause) { super(message, cause); }
	public DbConnectionClosedException(Throwable cause) { super(cause); }
	
	
	// helper methods //
	
	public static boolean isClosedException(Throwable e) 
	{
		if (!(e instanceof SQLException))
		{
			return false;
		}
		
		String message = e.getMessage().toLowerCase();
		return message.contains("connection closed")
				|| message.contains("pointer is closed")
				|| message.contains("stmt pointer is closed")
				|| message.contains("database has been closed");
	}
	
}
