package com.seibel.distanthorizons.common.wrappers.level;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.wrappers.world.LegacyDimensionInfo;
#endif
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
#if MC_VER <= MC_1_12_2
import net.minecraft.client.multiplayer.WorldClient;
#else
import net.minecraft.client.multiplayer.ClientLevel;
#endif
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class KeyedClientLevelManager implements IKeyedClientLevelManager
{
	/** Stores the server-provided keys indexed by dimension name for persistence. */
	private final Map<String, KeyInfo> keysByDimensionName = new ConcurrentHashMap<>();
	
	/** Cache for already keyed level wrappers to maintain object identity. */
	private final Map<#if MC_VER > MC_1_12_2 ClientLevel #else WorldClient #endif, IServerKeyedClientLevel> 
		keyedLevelsCache = Collections.synchronizedMap(new WeakHashMap<>());
	
	/** Allows to keep level manager enabled between loading different keyed levels */
	private volatile boolean enabled = false;
	
	
	
	//======================//
	// level override logic //
	//======================//
	//region
	
	@Override
	@Nullable
	public IServerKeyedClientLevel getServerKeyedLevel(IClientLevelWrapper levelWrapper)
	{
		if (levelWrapper == null)
		{
			return null;
		}
		
		// We synchronize on the cache map to ensure atomicity of the lookup-and-populate sequence.
		// This prevents multiple threads from creating duplicate wrappers for the same level.
		synchronized (this.keyedLevelsCache)
		{
			#if MC_VER <= MC_1_12_2
			WorldClient level = (WorldClient) levelWrapper.getWrappedMcObject(); 
			#else
			ClientLevel level = (ClientLevel) levelWrapper.getWrappedMcObject();
			#endif
			
			// Check the cache first
			IServerKeyedClientLevel cached = this.keyedLevelsCache.get(level);
			if (cached != null)
			{
				return cached;
			}
			
			// Determine the dimension name for this level
			// We use bypassLevelKeyManager=true to avoid recursion back into this manager
			IClientLevelWrapper wrappedLevel = ClientLevelWrapper.getWrapper(level, true);
			if (wrappedLevel == null)
			{
				return null;
			}
			
			String dimensionName = wrappedLevel.getDimensionName();
			KeyInfo info = this.keysByDimensionName.get(dimensionName);
			if (info == null)
			{
				return null;
			}
			
			// Create and cache a new keyed wrapper
			IServerKeyedClientLevel keyedLevel = new ServerKeyedClientLevelWrapper(level, info.serverKey, info.levelKey);
			this.keyedLevelsCache.put(level, keyedLevel);
			return keyedLevel;
		}
	}
	
	@Override
	public IServerKeyedClientLevel setServerKeyedLevel(IClientLevelWrapper clientLevel, String dimensionResource, String serverKey, String levelKey)
	{
		this.keysByDimensionName.put(dimensionResource, new KeyInfo(serverKey, levelKey));
		this.enabled = true;
		
		synchronized (this.keyedLevelsCache)
		{
			this.keyedLevelsCache.keySet().removeIf(level -> 
			{
	            #if MC_VER <= MC_1_7_10
				String levelDim = LegacyDimensionInfo.fullName(level.provider.dimensionId);
	            #elif MC_VER <= MC_1_12_2
	            String levelDim = level.provider.getDimensionType().getName() + ":" + level.provider.getDimension();
	            #elif MC_VER <= MC_1_21_10
				String levelDim = level.dimension().location().toString();
	            #else
	            String levelDim = level.dimension().identifier().toString();
	            #endif
				
				return levelDim.equals(dimensionResource);
			});
		}
		
		if (clientLevel == null || !clientLevel.getDimensionName().equals(dimensionResource)) {
			return null;
		}
		
		return this.getServerKeyedLevel(clientLevel);
	}
	
	@Override
	public void clearKeyedLevel() 
	{ 
		synchronized (this.keyedLevelsCache)
		{
			this.keyedLevelsCache.clear(); 
			this.keysByDimensionName.clear();
		}
	}
	
	@Override
	public boolean isEnabled() { return this.enabled; }
	
	@Override
	public void disable() 
	{ 
		this.enabled = false; 
		this.clearKeyedLevel();
	}
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class KeyInfo
	{
		public final String serverKey;
		public final String levelKey;
		
		public KeyInfo(String serverKey, String levelKey)
		{
			this.serverKey = serverKey;
			this.levelKey = levelKey;
		}
	}
	
	//endregion
	
	
}
