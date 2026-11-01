package com.seibel.distanthorizons.common.wrappers.world;

import com.seibel.distanthorizons.api.enums.config.EDhApiLodShading;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper;
import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import com.seibel.distanthorizons.common.wrappers.level.KeyedClientLevelManager;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.level.*;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;
#elif MC_VER <= MC_1_12_2
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.block.state.IBlockState;
#else
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
#endif
import com.seibel.distanthorizons.core.logging.DhLogger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER < MC_1_21_3
import net.minecraft.world.phys.Vec3;
#else
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
#endif

#if MC_VER <= MC_1_21_10
#else
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector4f;
import org.joml.Vector4fc;
#endif


public class ClientLevelWrapper implements IClientLevelWrapper
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	/**
	 * weak references are to prevent rare issues
	 * where, upon world closure, some levels aren't shutdown/removed properly
	 * and/or for servers were the level object isn't consistent
	 */
	private static final Map<
		#if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif, 
		WeakReference<ClientLevelWrapper>> LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());
	private static final KeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = (KeyedClientLevelManager) SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);
	
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MINECRAFT = Minecraft.getMinecraft();
	#else
	private static final Minecraft MINECRAFT = Minecraft.getInstance();
	#endif
	
	private static final ThreadLocal<DhBlockPosMutable> MUTABLE_BLOCK_POS_THREAD_LOCAL = ThreadLocal.withInitial(DhBlockPosMutable::new);
	
	private static final Timer CLIENT_CLEANUP_TIMER = TimerUtil.CreateTimer("ClientLevelTickCleanup");
	private static final TimerTask CLIENT_CLEANUP_TASK = TimerUtil.createTimerTask(ClientLevelWrapper::tickCleanup);
	
	/** how long in milliseconds can a level be unused before it's automatically unloaded */
	private static final long INACTIVE_TIME_BEFORE_UNLOADED_IN_MS = 30 * 1000;
	
	
	
	#if MC_VER <= MC_1_12_2
	private final WorldClient level;
	private final ConcurrentHashMap<IBlockState, ClientBlockStateColorCache> blockColorCacheByBlockState = new ConcurrentHashMap<>();
	#else
	private final ClientLevel level;
	private final ConcurrentHashMap<BlockState, ClientBlockStateColorCache> blockColorCacheByBlockState = new ConcurrentHashMap<>();
	#endif


	/** cached method reference to reduce GC overhead */
	private final Function<
		#if MC_VER <= MC_1_12_2 IBlockState
		#else BlockState
		#endif,
		ClientBlockStateColorCache> createCachedBlockColorCacheFunc
			= (blockState) -> new ClientBlockStateColorCache(blockState, this);
	
	
	private boolean cloudColorFailLogged = false;
	
	private volatile IDhLevel dhLevel;
	private volatile long lastAccessTime = System.currentTimeMillis();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	protected ClientLevelWrapper(#if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif level) { this.level = level; }
	
	//endregion
	
	
	
	//======================//
	// inactivity unloading //
	//======================//
	//region
	
	@Override
	public synchronized void markAccessed() { this.lastAccessTime = System.currentTimeMillis(); }
	public synchronized long getLastAccessTime() { return this.lastAccessTime; }
	
	static
	{
		// fire 20 times per second (i.e. 50ms interval)
		CLIENT_CLEANUP_TIMER.scheduleAtFixedRate(CLIENT_CLEANUP_TASK, 0, 1000 / 20);
	}
	
	public static void tickCleanup()
	{
		#if MC_VER <= MC_1_7_10
		WorldClient clientLevel = MINECRAFT.theWorld;
		#elif MC_VER <= MC_1_12_2
		WorldClient clientLevel = MINECRAFT.world;
		#else
		ClientLevel clientLevel = MINECRAFT.level;
		#endif
		
		if (clientLevel == null) 
		{
			return; 
		}
		
		
		
		long currentTime = System.currentTimeMillis();
		
		ArrayList<ClientLevelWrapper> levelsToUnload = new ArrayList<>();
		synchronized(LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL) // should only be run on one thread at a time, but just in case
		{
			for (WeakReference<ClientLevelWrapper> ref : LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.values())
			{
				ClientLevelWrapper levelWrapper = ref.get();
				if (levelWrapper != null 
					&& levelWrapper.level != clientLevel)
				{
					// We use the synchronized getter to prevent race conditions with markAccessed()
					long inactiveTimeMs = currentTime - levelWrapper.getLastAccessTime();
					if (inactiveTimeMs > INACTIVE_TIME_BEFORE_UNLOADED_IN_MS)
					{
						levelsToUnload.add(levelWrapper);
					}
				}
			}
		}
		
		for (ClientLevelWrapper wrapper : levelsToUnload)
		{
			// Re-verify all conditions inside a synchronized block on the wrapper 
			// to ensure atomicity with respect to markAccessed()
			synchronized(wrapper)
			{
				long inactiveTimeMs = currentTime - wrapper.getLastAccessTime();
				if (wrapper.level != clientLevel
					&& inactiveTimeMs > INACTIVE_TIME_BEFORE_UNLOADED_IN_MS)
				{
					LOGGER.debug("Unloading level [" + wrapper.getDhIdentifier() + "] due to inactivity");
					wrapper.tryUnloadFromWorld();
				}
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// level handling //
	//================//
	//region
	
	@Override
	public void setDhLevel(IDhLevel dhLevel) { this.dhLevel = dhLevel; }
	@Override
	public IDhLevel getDhLevel() { return this.dhLevel; }
	
	/** 
	 * can be used when speed is important and the same level is likely to be passed in,
	 * IE rendering.
	 */
	@Nullable
	public static IClientLevelWrapper getWrapperIfDifferent(
		@Nullable IClientLevelWrapper levelWrapper, 
		@NotNull #if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif level)
	{
		if (KEYED_CLIENT_LEVEL_MANAGER.isEnabled())
		{
			IServerKeyedClientLevel keyedLevel = KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel(getWrapper(level, true));
			if (keyedLevel != levelWrapper)
			{
				return getWrapper(level);
			}
		}
		
		ClientLevelWrapper clientLevelWrapper = (ClientLevelWrapper)levelWrapper;
		if (clientLevelWrapper == null
			|| clientLevelWrapper.level != level)
		{
			return getWrapper(level);
		}
		
		return clientLevelWrapper;
	}
	
	@Nullable
	public static IClientLevelWrapper getWrapper(
		@NotNull #if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif level) 
	{ return getWrapper(level, false); }
	
	@Nullable
	public static IClientLevelWrapper getWrapper(
		@Nullable #if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif level, boolean bypassLevelKeyManager)
	{
		if (!bypassLevelKeyManager)
		{
			if (level == null)
			{
				return null;
			}
			
			// used if the client is connected to a server that defines the currently loaded level
			IServerKeyedClientLevel overrideLevel = KEYED_CLIENT_LEVEL_MANAGER.getServerKeyedLevel(getWrapper(level, true));
			if (overrideLevel != null)
			{
				// if the currently loaded level wrapper doesn't match what's incoming, unload it
				WeakReference<ClientLevelWrapper> levelRef = LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.get(level);
				if (levelRef != null 
					&& levelRef.get() != overrideLevel)
				{
					ClientLevelWrapper levelWrapper = levelRef.get();
					if (levelWrapper != null)
					{
						levelWrapper.tryUnloadFromWorld();
					}
					levelRef = null;
				}
				
				if (levelRef == null 
					&& overrideLevel instanceof ClientLevelWrapper)
				{
					LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.put(level, new WeakReference<>((ClientLevelWrapper) overrideLevel));
				}
				
				return overrideLevel;
			}
		}
		
		
		WeakReference<ClientLevelWrapper> levelRef = LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.get(level);
		if (levelRef != null)
		{
			ClientLevelWrapper levelWrapper = levelRef.get();
			if (levelWrapper != null)
			{
				return levelWrapper;
			}
		}
		
		
		return LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.compute(level, (newLevel, newLevelRef) ->
		{
			if (newLevelRef != null)
			{
				ClientLevelWrapper oldLevelWrapper = newLevelRef.get();
				if (oldLevelWrapper != null)
				{
					return newLevelRef;
				}
			}
			
			return new WeakReference<>(new ClientLevelWrapper(newLevel));
		}).get();
	}
	
	@Nullable
	@Override
	public IServerLevelWrapper tryGetServerSideWrapper()
	{
		try
		{
			// this method only makes sense if we are running a single-player server
			if (MINECRAFT.#if MC_VER <= MC_1_12_2 getIntegratedServer() #else getSingleplayerServer() #endif == null)
			{
				return null;
			}
			
			#if MC_VER <= MC_1_7_10
			WorldServer[] serverLevels = MINECRAFT.getIntegratedServer().worldServers;
			#elif MC_VER <= MC_1_12_2
			WorldServer[] serverLevels = MINECRAFT.getIntegratedServer().worlds;
			#else
			Iterable<ServerLevel> serverLevels = MINECRAFT.getSingleplayerServer().getAllLevels();
			#endif

			// attempt to find the server level with the same dimension type
			// Note: this assumes only one level per dimension type, multiverse servers may not behave correctly
			ServerLevelWrapper foundLevelWrapper = null;
			for (#if MC_VER <= MC_1_12_2 WorldServer #else ServerLevel #endif serverLevel : serverLevels)
			{
				#if MC_VER <= MC_1_7_10
				if (serverLevel.provider.dimensionId == this.level.provider.dimensionId)
				#elif MC_VER <= MC_1_12_2
				if (serverLevel.provider.getDimension() == this.level.provider.getDimension())
				#else
				if (serverLevel.dimension() == this.level.dimension())
				#endif
				{
					foundLevelWrapper = ServerLevelWrapper.getWrapper(serverLevel);
					break;
				}
			}
			
			return foundLevelWrapper;
		}
		catch (Exception e)
		{
			LOGGER.error("Failed to get server side wrapper for client level: " + this.level);
			return null;
		}
	}
	
	//endregion
	
	
	
	//====================//
	// base level methods //
	//====================//
	//region
	
	@Override
	public int getBlockColor(
		DhBlockPos blockWorldPos, IBiomeWrapper biome, FullDataSourceV2 fullDataSource, IBlockStateWrapper blockWrapper, 
		boolean allowApiOverride)
	{
		ClientBlockStateColorCache blockColorCache = this.blockColorCacheByBlockState.get(((BlockStateWrapper) blockWrapper).blockState);
		if (blockColorCache == null)
		{
			blockColorCache = this.blockColorCacheByBlockState.computeIfAbsent(
					((BlockStateWrapper) blockWrapper).blockState,
					this.createCachedBlockColorCacheFunc);
		}
		
		return blockColorCache.getColor((BiomeWrapper) biome, fullDataSource, blockWorldPos, allowApiOverride);
	}
	
	@Override
	public int getDirtBlockColor()
	{
		BlockStateWrapper dirtBlockWrapper = BlockStateWrapper.getDirtBlockStateWrapper(this);
		return this.getBlockColor(DhBlockPos.ZERO, BiomeWrapper.EMPTY_WRAPPER, null, dirtBlockWrapper);
	}
	
	@Override 
	public void clearBlockColorCache() 
	{
		this.blockColorCacheByBlockState.clear();
	}
	
	private IDimensionTypeWrapper dimensionTypeWrapper = null;
	@Override
	public IDimensionTypeWrapper getDimensionType()
	{
		// cached since dimensionType() is a dictionary lookup that allocates objects
		// and this call is used in a high traffic location
		if (this.dimensionTypeWrapper != null)
		{
			return this.dimensionTypeWrapper;
		}
		
		#if MC_VER <= MC_1_7_10
		this.dimensionTypeWrapper = DimensionTypeWrapper.getDimensionTypeWrapper(this.level.provider.dimensionId);
		#elif MC_VER <= MC_1_12_2
		this.dimensionTypeWrapper = DimensionTypeWrapper.getDimensionTypeWrapper(this.level.provider.getDimensionType());
		#elif MC_VER <= MC_1_21_10
		this.dimensionTypeWrapper = DimensionTypeWrapper.getDimensionTypeWrapper(this.level.dimensionType());
		#else
		this.dimensionTypeWrapper = DimensionTypeWrapper.getDimensionTypeWrapper(this.level.dimensionType(), this.getDimensionName());
		#endif
		return this.dimensionTypeWrapper;
	}

	private String dimensionName = null;
	@Override
	public String getDimensionName()
	{
		// cached since toString() allocates a new string each time
		// and this call is used in a high traffic location
		if (this.dimensionName != null)
		{
			return this.dimensionName;
		}

		#if MC_VER <= MC_1_7_10
		this.dimensionName = LegacyDimensionInfo.fullName(this.level.provider.dimensionId);
		#elif MC_VER <= MC_1_12_2
		this.dimensionName = this.level.provider.getDimensionType().getName() + ":" + this.level.provider.getDimension();
		#elif MC_VER <= MC_1_21_10
		this.dimensionName = this.level.dimension().location().toString();
		#else
		this.dimensionName = this.level.dimension().identifier().toString();
		#endif
		return this.dimensionName;
	}
	
	@Override
	public long getHashedSeed()
	{
		#if MC_VER <= MC_1_12_2
		return this.level.getSeed();
		#else
		return this.level.getBiomeManager().biomeZoomSeed;
		#endif
	}
	
	@Override
	public String getDhIdentifier() { return this.getHashedSeedEncoded() + "@" + this.getDimensionName(); }
	
	@Override
	public EDhApiLevelType getLevelType() { return EDhApiLevelType.CLIENT_LEVEL; }
	
	public #if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif getLevel() { return this.level; }
	
	private Boolean dimHasCeiling = null;
	@Override
	public boolean hasCeiling() 
	{
		// cached since dimensionType() is a dictionary lookup that allocates objects
		// and this call is used in a high traffic location
		if (this.dimHasCeiling != null)
		{
			return this.dimHasCeiling;
		}
		
		#if MC_VER <= MC_1_7_10
		this.dimHasCeiling = this.level.provider.isHellWorld;
		#elif MC_VER <= MC_1_12_2
		// 1.12.2 has no hasCeiling() - only the nether has a ceiling in vanilla
		this.dimHasCeiling = this.level.provider.isNether();
		#else
		this.dimHasCeiling = this.level.dimensionType().hasCeiling();
		#endif
		return this.dimHasCeiling;
	}

	private Boolean dimHasSkyLight = null;
	@Override
	public boolean hasSkyLight()
	{
		// cached since dimensionType() is a dictionary lookup that allocates objects
		// and this call is used in a high traffic location
		if (this.dimHasSkyLight != null)
		{
			return this.dimHasSkyLight;
		}

		#if MC_VER <= MC_1_7_10
		this.dimHasSkyLight = !this.level.provider.hasNoSky;
		#elif MC_VER <= MC_1_12_2
		this.dimHasSkyLight = this.level.provider.hasSkyLight();
		#else
		this.dimHasSkyLight = this.level.dimensionType().hasSkyLight();
		#endif
		return this.dimHasSkyLight;
	}
	
	private Integer dimMaxHeight = null;
	@Override
	public int getMaxHeight() 
	{
		// cached since getHeight() is a dictionary lookup that allocates objects
		// and this call is used in a high traffic location
		if (this.dimMaxHeight != null)
		{
			return this.dimMaxHeight;
		}
		
		this.dimMaxHeight = this.level.getHeight();
		return this.dimMaxHeight;
	}
	
	private Integer dimMinHeight = null;
	@Override
	public int getMinHeight()
	{
		// cached since getMinY() is a dictionary lookup that allocates objects
		// and this call is used in a high traffic location
		if (this.dimMinHeight != null)
		{
			return this.dimMinHeight;
		}
		
		
        #if MC_VER < MC_1_17_1
        this.dimMinHeight = 0;
		#elif MC_VER < MC_1_21_3
		this.dimMinHeight = this.level.getMinBuildHeight();
        #else
		this.dimMinHeight = this.level.getMinY();
        #endif
		return this.dimMinHeight;
	}
	
	@Override
	public #if MC_VER <= MC_1_12_2 WorldClient #else ClientLevel #endif getWrappedMcObject() { return this.level; }
	
	private void tryUnloadFromWorld()
	{
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world == null
			|| !world.unloadLevel(this))
		{
			this.onUnload();
		}
	}
	@Override
	public void onUnload() 
	{ 
		LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL.remove(this.level);
		this.dhLevel = null;
	}
	
	@Override
	public File getDhSaveFolder()
	{
		if (this.dhLevel == null)
		{
			return null;
		}
		
		return this.dhLevel.getSaveStructure().getSaveFolder(this);
	}
	
	@Override
	public DhApiResult<Color> getBlockColorPreApi(
		IDhApiBlockStateWrapper blockStateWrapper,
		IDhApiBiomeWrapper biomeWrapper,
		int blockWorldPosX, int blockWorldPosY, int blockWorldPosZ,
		IDhApiFullDataSource dataSource)
	{
		// cast to core objects //
		//region
		
		if(!(blockStateWrapper instanceof IBlockStateWrapper))
		{
			return DhApiResult.createFail("Unable to cast ["+blockStateWrapper.getClass()+"] to ["+IBlockStateWrapper.class+"]");
		}
		IBlockStateWrapper coreBlockStateWrapper = (IBlockStateWrapper)blockStateWrapper;
		
		if(!(biomeWrapper instanceof IBiomeWrapper))
		{
			return DhApiResult.createFail("Unable to cast ["+biomeWrapper.getClass()+"] to ["+IBiomeWrapper.class+"]");
		}
		IBiomeWrapper coreBiomeWrapper = (IBiomeWrapper) biomeWrapper;
		
		if(!(dataSource instanceof FullDataSourceV2))
		{
			return DhApiResult.createFail("Unable to cast ["+dataSource.getClass()+"] to ["+FullDataSourceV2.class+"]");
		}
		FullDataSourceV2 coreDataSource = (FullDataSourceV2) dataSource;
		
		//endregion
		
		
		
		// use a mutable thread local to reduce allocations slightly
		DhBlockPosMutable blockWorldPos = MUTABLE_BLOCK_POS_THREAD_LOCAL.get();
		blockWorldPos.setX(blockWorldPosX);
		blockWorldPos.setY(blockWorldPosY);
		blockWorldPos.setZ(blockWorldPosZ);
		
		int color = this.getBlockColor(blockWorldPos, coreBiomeWrapper, coreDataSource, coreBlockStateWrapper, false);
		return DhApiResult.createSuccess(ColorUtil.toColorObjARGB(color));
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override 
	public IDhApiCustomRenderRegister getRenderRegister()
	{
		if (this.dhLevel == null)
		{
			return null;
		}
		
		return this.dhLevel.getGenericRenderer();
	}
	
	@Override
	public Color getCloudColor(float tickDelta)
	{
		#if MC_VER < MC_1_21_3

		#if MC_VER <= MC_1_7_10
		Vec3 colorVec3 = null;
		#elif MC_VER <= MC_1_12_2
		Vec3d colorVec3 = null;
		#else
		Vec3 colorVec3 = null;
		#endif
		try
		{
			#if MC_VER <= MC_1_12_2
			// 1.7.10 and 1.12.2 both use the British spelling
			colorVec3 = this.level.getCloudColour(tickDelta);
			#else
			colorVec3 = this.level.getCloudColor(tickDelta);
			#endif

			#if MC_VER <= MC_1_7_10
			return new Color((float)colorVec3.xCoord, (float)colorVec3.yCoord, (float)colorVec3.zCoord);
			#else
			return new Color((float)colorVec3.x, (float)colorVec3.y, (float)colorVec3.z);
			#endif
		}
		catch (Exception e)
		{
			// extra logging is due to some mods returning weird values, this way we can track down the issue better
			if (!this.cloudColorFailLogged)
			{
				this.cloudColorFailLogged = true;

				String colorString = "NULL";
				if (colorVec3 != null)
				{
					#if MC_VER <= MC_1_7_10
					colorString = "r["+(float)colorVec3.xCoord+"] g["+(float)colorVec3.yCoord+"] b["+(float)colorVec3.zCoord+"]";
					#else
					colorString = "r["+(float)colorVec3.x+"] g["+(float)colorVec3.y+"] b["+(float)colorVec3.z+"]";
					#endif
				}
				LOGGER.warn("Failed to get cloud color for ["+this.getDhIdentifier()+"]. vec3 ["+colorString+"], error: ["+e.getMessage()+"].", e);
			}

			// default to white if there's an issue
			return Color.WHITE;
		}
		#elif MC_VER <= MC_1_21_10
		int argbColor = 0;
		try
		{
			argbColor = this.level.getCloudColor(tickDelta);
			return ColorUtil.toColorObjARGB(argbColor);
		}
		catch (Exception e)
		{
			// extra logging is due to some mods returning weird values, this way we can track down the issue better
			if (!this.cloudColorFailLogged)
			{
				this.cloudColorFailLogged = true;
				LOGGER.warn("Failed to get cloud color for ["+this.getDhIdentifier()+"]. Int ["+argbColor+"], col ["+ColorUtil.toString(argbColor)+"], error: ["+e.getMessage()+"].", e);
			}
			
			// default to white if there's an issue
			return Color.WHITE;
		}
		#elif MC_VER <= MC_26_2_0
		int argbColor = 0;
		try
		{
			argbColor = this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_COLOR, BlockPos.ZERO);
			return new Color(ColorUtil.getRed(argbColor), ColorUtil.getGreen(argbColor), ColorUtil.getBlue(argbColor), 255 /* ignore alpha since DH clouds don't render correctly with transparency */);
		}
		catch (Exception e)
		{
			// extra logging is due to some mods returning weird values, this way we can track down the issue better
			if (!this.cloudColorFailLogged)
			{
				this.cloudColorFailLogged = true;
				LOGGER.warn("Failed to get cloud color for ["+this.getDhIdentifier()+"]. Int ["+argbColor+"], col ["+ColorUtil.toString(argbColor)+"], error: ["+e.getMessage()+"].", e);
			}
			
			// default to white if there's an issue
			return Color.WHITE;
		}
		#else
		Vector4fc argbColor = new Vector4f();
		try
		{
			argbColor = this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_COLOR, BlockPos.ZERO);
			return new Color(argbColor.x(), argbColor.y(), argbColor.z(), 1.0f /* ignore alpha since DH clouds don't render correctly with transparency */);
		}
		catch (Exception e)
		{
			// extra logging is due to some mods returning weird values, this way we can track down the issue better
			if (!this.cloudColorFailLogged)
			{
				this.cloudColorFailLogged = true;
				LOGGER.warn("Failed to get cloud color for ["+this.getDhIdentifier()+"]. vec4 ["+argbColor+"], error: ["+e.getMessage()+"].", e);
			}
			
			// default to white if there's an issue
			return Color.WHITE;
		}
		#endif
	}
	
	@Override
	public float getShade(EDhDirection lodDirection)
	{
		EDhApiLodShading lodShading = Config.Client.Advanced.Graphics.Quality.lodShading.get();
		switch (lodShading)
		{
			default:
			case AUTO:
				#if MC_VER <= MC_1_12_2
				// 1.12.2 level doesn't have a getShade method, fall through to ENABLED
				#else
					Direction mcDir = McObjectConverter.convert(lodDirection);
					#if MC_VER <= MC_1_21_11
					return this.level.getShade(mcDir, true);
					#else
					return this.level.cardinalLighting().byFace(mcDir);
					#endif
				#endif
			case ENABLED:
				switch (lodDirection)
				{
					case DOWN:
						return 0.5F;
					default:
					case UP:
						return 1.0F;
					case NORTH:
					case SOUTH:
						return 0.8F;
					case WEST:
					case EAST:
						return 0.6F;
				}
			
			case DISABLED:
				return 1.0F;
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public String toString()
	{
		if (this.level == null)
		{
			return "Wrapped{null}";
		}
		
		return "Wrapped{" + this.level.toString() + "@" + this.getDhIdentifier() + "}";
	}
	
	//endregion
	
	
	
}
