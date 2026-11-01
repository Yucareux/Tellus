package com.seibel.distanthorizons.core.multiplayer.config;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.util.MoreObjects;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.network.INetworkObject;
import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SessionConfig implements INetworkObject
{
	private static final LinkedHashMap<String, Entry> CONFIG_ENTRIES = new LinkedHashMap<>();
	
	
	private final HashMap<String, Object> values = new HashMap<>();
	public SessionConfig constrainingConfig;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	static
	{
		// Note: config values are transmitted in the insertion order
		
		registerConfigEntry(Config.Common.WorldGenerator.generatorPlan, (clientPlan, serverPlan) -> {
			if (clientPlan == EDhApiGeneratorPlan.DISABLED || serverPlan == EDhApiGeneratorPlan.DISABLED)
			{
				return EDhApiGeneratorPlan.DISABLED;
			}
			
			// Find a plan that both client and server accept; if nothing found, server overrides the client.
			boolean surfaceGenEnabled = clientPlan.surfaceGenEnabled && serverPlan.surfaceGenEnabled;
			boolean chunkGenEnabled = clientPlan.chunkGenEnabled && serverPlan.chunkGenEnabled;
			for (EDhApiGeneratorPlan plan : EDhApiGeneratorPlan.values())
			{
				if (plan.generationEnabled
					&& plan.surfaceGenEnabled == surfaceGenEnabled
					&& plan.chunkGenEnabled == chunkGenEnabled)
				{
					return plan;
				}
			}
			
			return serverPlan;
		});
		
		registerConfigEntry(Config.Server.maxGenerationRequestDistance, Math::min);
		registerConfigEntry(Config.Common.WorldGenerator.generationCenterChunkX, (clientVal, serverVal) -> serverVal);
		registerConfigEntry(Config.Common.WorldGenerator.generationCenterChunkZ, (clientVal, serverVal) -> serverVal);
		registerConfigEntry(Config.Common.WorldGenerator.generationMaxChunkRadius, (clientVal, serverVal) -> serverVal);
		registerConfigEntry(Config.Server.generationRequestRateLimit, Math::min);
		
		registerConfigEntry(Config.Server.enableRealTimeUpdates, Boolean::logicalAnd);
		registerConfigEntry(Config.Server.realTimeUpdateDistanceRadiusInChunks, Math::min);
		
		registerConfigEntry(Config.Server.synchronizeOnLoad, Boolean::logicalAnd);
		registerConfigEntry(Config.Server.maxSyncOnLoadRequestDistance, Math::min);
		registerConfigEntry(Config.Server.syncOnLoadRateLimit, Math::min);
		
		registerConfigEntry(Config.Server.playerBandwidthLimit, (clientVal, serverVal) -> {
			if (clientVal == 0 && serverVal == 0)
			{
				return 0;
			}
			
			return Math.min(
				(clientVal > 0) ? clientVal : Integer.MAX_VALUE,
				(serverVal > 0) ? serverVal : Integer.MAX_VALUE
			);
		});
	}
	
	public SessionConfig() {}
	
	
	
	//===============//
	// public values //
	//===============//
	
	public EDhApiGeneratorPlan getGeneratorPlan() { return this.getValue(Config.Common.WorldGenerator.generatorPlan); }
	public int getMaxGenerationRequestDistance() { return this.getValue(Config.Server.maxGenerationRequestDistance); }
	public Integer getGenerationCenterChunkX() { return this.getValue(Config.Common.WorldGenerator.generationCenterChunkX); }
	public Integer getGenerationCenterChunkZ() { return this.getValue(Config.Common.WorldGenerator.generationCenterChunkZ); }
	public Integer getGenerationMaxChunkRadius() { return this.getValue(Config.Common.WorldGenerator.generationMaxChunkRadius); }
	public int getGenerationRequestRateLimit() { return this.getValue(Config.Server.generationRequestRateLimit); }
	
	public boolean isRealTimeUpdatesEnabled() { return this.getValue(Config.Server.enableRealTimeUpdates); }
	public int getMaxUpdateDistanceRadius() { return this.getValue(Config.Server.realTimeUpdateDistanceRadiusInChunks); }
	
	public boolean getSynchronizeOnLoad() { return this.getValue(Config.Server.synchronizeOnLoad); }
	public int getMaxSyncOnLoadDistance() { return this.getValue(Config.Server.maxSyncOnLoadRequestDistance); }
	public int getSyncOnLoginRateLimit() { return this.getValue(Config.Server.syncOnLoadRateLimit); }
	
	public int getPlayerBandwidthLimit() { return this.getValue(Config.Server.playerBandwidthLimit); }
	
	
	
	//====================//
	// entry registration //
	//====================//
	
	private static <T> void registerConfigEntry(ConfigEntry<T> configEntry, BinaryOperator<T> valueConstrainer)
	{
		String commandName = configEntry.getChatCommandName();
		if (commandName == null)
		{
			throw new NullPointerException("Config ["+configEntry.name+"] doesn't have a chat command defined.");
		}
		
		registerConfigEntry(
			commandName,
			new Entry(
				configEntry::get,
				runnable -> new ConfigChangeListener<>(configEntry, ignored -> runnable.run()),
				valueConstrainer
			)
		);
	}
	
	private static void registerConfigEntry(@NotNull String key, Entry entry)
	{
		if (CONFIG_ENTRIES.containsKey(key))
		{
			throw new IllegalArgumentException("Attempted to register config entry with duplicate key: " + key);
		}
		
		CONFIG_ENTRIES.put(key, entry);
	}
	
	
	
	//==================//
	// internal getters //
	//==================//
	
	private <T> T getValue(ConfigEntry<T> configEntry) { return this.getValue(configEntry.getChatCommandName()); }
	@SuppressWarnings("unchecked")
	private <T> T getValue(String name)
	{
		Entry entry = CONFIG_ENTRIES.get(name);
		
		T value = (T) this.values.get(name);
		if (value == null)
		{
			value = (T) entry.valueSupplier.get();
		}
		
		return (this.constrainingConfig != null
				? (T) entry.valueConstrainer.apply(this.constrainingConfig.getValue(name), value)
				: value);
	}
	
	public <T> void constrainValue(ConfigEntry<T> configEntry, T value) { this.constrainValue(configEntry.getChatCommandName(), value); }
	private void constrainValue(String name, Object value)
	{
		Entry entry = CONFIG_ENTRIES.get(name);
		this.values.put(name, entry.valueConstrainer.apply(this.getValue(name), value));
	}
	
	private Map<String, ?> getValues()
	{
		return CONFIG_ENTRIES.keySet().stream().collect(Collectors.toMap(
				Function.identity(),
				this::getValue,
				(x, y) -> x,
				LinkedHashMap::new
		));
	}
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf outBuffer) { this.writeFixedLengthCollection(outBuffer, this.getValues().values()); }
	
	@Override
	public void decode(ByteBuf inBuffer)
	{
		for (String key : CONFIG_ENTRIES.keySet())
		{
			Object currentValue = this.getValue(key);
			Object newValue = Codec.getCodec(currentValue.getClass()).decode.apply(currentValue, inBuffer);
			this.values.put(key, newValue);
		}
	}
	
	
	
	//=========//
 	// logging //
 	//=========//
	
	/** 
	 * example: "common.playerBandwidthLimit:[497], " <br>
	 * Useful to see what was changed when receiving a new config from the server.
	 *
	 * @param includeAllValues whether all values should be included, even unchanged values
	 */
	public String getDifferencesAsString(SessionConfig that, boolean includeAllValues)
	{
		StringBuilder stringBuilder = new StringBuilder();
		
		for (String key : CONFIG_ENTRIES.keySet())
		{
			Object thisValue = this.getValue(key);
			Object thatValue = that.getValue(key);
			
			if (includeAllValues || !Objects.equals(thisValue, thatValue))
			{
				stringBuilder.append(key+":["+thatValue+"], ");
			}
		}
		
		return stringBuilder.toString();
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("values", this.getValues())
				.toString();
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class Entry
	{
		public final Supplier<Object> valueSupplier;
		public final Function<Runnable, Closeable> changeListenerFactory;
		public final BinaryOperator<Object> valueConstrainer;
		
		@SuppressWarnings("unchecked")
		private <T> Entry(Supplier<Object> valueSupplier, Function<Runnable, Closeable> changeListenerFactory, BinaryOperator<T> valueConstrainer)
		{
			this.valueSupplier = valueSupplier;
			this.changeListenerFactory = changeListenerFactory;
			this.valueConstrainer = (BinaryOperator<Object>) valueConstrainer;
		}
		
	}
	
	/** fires if any config value was changed */
	public static class AnyChangeListener implements Closeable
	{
		private final ArrayList<Closeable> changeListeners;
		
		public AnyChangeListener(Runnable runnable)
		{
			try
			{
				int size = CONFIG_ENTRIES.size();
				this.changeListeners = new ArrayList<>(size);
				for (Entry entry : CONFIG_ENTRIES.values())
				{
					this.changeListeners.add(entry.changeListenerFactory.apply(runnable));
				}
			}
			catch (Throwable e)
			{
				throw e;
			}
		}
		
		@Override
		public void close()
		{
			for (Closeable changeListener : this.changeListeners)
			{
				try
				{
					changeListener.close();
				}
				catch (Exception ignored)
				{
				}
			}
			this.changeListeners.clear();
		}
		
	}
	
}
