package com.seibel.distanthorizons.core.api.external.methods.data;

import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.SoftReference;
import java.util.concurrent.locks.ReentrantLock;

public class DhApiTerrainDataCache implements IDhApiTerrainDataCache
{
	private final ReentrantLock modificationLock = new ReentrantLock();
	private final Long2ReferenceOpenHashMap<SoftReference<FullDataSourceV2>> posToFullDataRef = new Long2ReferenceOpenHashMap<>();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	//==================//
	// internal methods //
	//==================//
	//region
	
	public void add(long pos, FullDataSourceV2 dataSource)
	{
		try
		{
			modificationLock.lock();
			this.posToFullDataRef.put(pos, new SoftReference<>(dataSource));
		}
		finally
		{
			modificationLock.unlock();
		}
	}
	
	@Nullable
	public FullDataSourceV2 get(long pos)
	{
		try
		{
			modificationLock.lock();
			
			SoftReference<FullDataSourceV2> ref = this.posToFullDataRef.get(pos);
			if (ref != null)
			{
				return ref.get();
			}
			else
			{
				return null;
			}
		}
		finally
		{
			modificationLock.unlock();
		}
	}
	
	//endregion
	
	
	
	//=============//
	// API methods //
	//=============//
	//region
	
	@Override 
	public void clear()
	{
		try
		{
			modificationLock.lock();
			
			LongSet keySet = this.posToFullDataRef.keySet();
			for (long pos : keySet)
			{
				// we can't call remove() here because that can cause inconsistent issues
				SoftReference<FullDataSourceV2> dataRef = this.posToFullDataRef.get(pos);
				if (dataRef != null)
				{
					FullDataSourceV2 dataSource = dataRef.get();
					if (dataSource != null)
					{
						try
						{
							dataSource.close();
						}
						catch (Exception e)
						{
							LOGGER.warn("Unable to close data source, error: [" + e.getMessage() + "].", e);
						}
					}
				}
			}
			
			// clearing can only be done after all the data sources have been closed
			this.posToFullDataRef.clear();
		}
		finally
		{
			modificationLock.unlock();
		}
	}
	
	//endregion
	
	
	
	//================//
 	// base overrides //
	//================//
	//region
	
	@Override 
	public void close() { this.clear(); }
	
	@Override 
	public String toString() { return "Size: " + this.posToFullDataRef.size(); }
	
	//endregion
	
	
	
}
