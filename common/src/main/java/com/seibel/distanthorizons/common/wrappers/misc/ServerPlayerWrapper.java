package com.seibel.distanthorizons.common.wrappers.misc;

import com.google.common.base.Objects;
import com.google.common.collect.MapMaker;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
#if MC_VER <= MC_1_7_10
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
#elif MC_VER <= MC_1_12_2
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
#else
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
#endif

import java.util.concurrent.ConcurrentMap;

#if MC_VER <= MC_1_12_2

/**
 * This wrapper transparently ensures that the underlying {@link EntityPlayerMP} is always valid,
 * unless the player has disconnected.
 */
#else
/**
 * This wrapper transparently ensures that the underlying {@link ServerPlayer} is always valid,
 * unless the player has disconnected.
 */
#endif
public class ServerPlayerWrapper implements IServerPlayerWrapper
{
	#if MC_VER <= MC_1_12_2
	private static final ConcurrentMap<NetHandlerPlayServer, ServerPlayerWrapper> serverPlayerWrapperMap = new MapMaker().weakKeys().weakValues().makeMap();
	#else
	private static final ConcurrentMap<ServerGamePacketListenerImpl, ServerPlayerWrapper> serverPlayerWrapperMap = new MapMaker().weakKeys().weakValues().makeMap();
	#endif
	
	#if MC_VER <= MC_1_12_2
	private final NetHandlerPlayServer connection;
	#else
	private final ServerGamePacketListenerImpl connection;
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	#if MC_VER <= MC_1_12_2
	public static ServerPlayerWrapper getWrapper(EntityPlayerMP serverPlayer)
	#else
	public static ServerPlayerWrapper getWrapper(ServerPlayer serverPlayer)
	#endif
	{
		#if MC_VER <= MC_1_7_10
		// 1.7.10's NetHandlerPlayServer is reached through `playerNetServerHandler`, not `connection`
		NetHandlerPlayServer netHandler = serverPlayer.playerNetServerHandler;
		return serverPlayerWrapperMap.computeIfAbsent(netHandler, ignored -> new ServerPlayerWrapper(netHandler));
		#else
		return serverPlayerWrapperMap.computeIfAbsent(serverPlayer.connection, ignored -> new ServerPlayerWrapper(serverPlayer.connection));
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	private ServerPlayerWrapper(NetHandlerPlayServer connection)
	#else
	private ServerPlayerWrapper(ServerGamePacketListenerImpl connection)
	#endif
	{ this.connection = connection; }
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	#if MC_VER <= MC_1_12_2
	private EntityPlayerMP getServerPlayer()
	#else
	private ServerPlayer getServerPlayer()
	#endif
	{
		#if MC_VER <= MC_1_7_10
		return this.connection.playerEntity;
		#else
		return this.connection.player;
		#endif
	}
	
	@Override
	public String getName()
	{
		#if MC_VER <= MC_1_7_10
		return this.getServerPlayer().getDisplayName();
		#elif MC_VER <= MC_1_12_2
		return this.getServerPlayer().getName();
		#else
		return this.getServerPlayer().getName().getString();
		#endif
	}
	
	@Override
	public IServerLevelWrapper getLevel()
	{
		#if MC_VER <= MC_1_12_2
		WorldServer level = null;
		if (this.getServerPlayer() instanceof IMixinServerPlayer)
		{
			IMixinServerPlayer mixinPlayer = (IMixinServerPlayer) this.getServerPlayer();
			level = mixinPlayer.distantHorizons$getDimensionChangeDestination();
		}
		#else
		ServerLevel  level = ((IMixinServerPlayer) this.getServerPlayer()).distantHorizons$getDimensionChangeDestination();
		#endif
		
		if (level == null)
		{
			#if MC_VER <= MC_1_7_10
			level = (WorldServer) this.getServerPlayer().worldObj;
			#elif MC_VER <= MC_1_12_2
			MinecraftServer server = this.getServerPlayer().getServer();
			level = (server != null) ? server.getWorld(this.getServerPlayer().dimension) : this.getServerPlayer().getServerWorld();
			#elif MC_VER < MC_1_20_1
			level = this.getServerPlayer().getLevel();
			#elif MC_VER < MC_1_21_6
			level = this.getServerPlayer().serverLevel();
			#else
			level = this.getServerPlayer().level();
			#endif
		}
		
		return ServerLevelWrapper.getWrapper(level);
	}
	
	@Override
	public DhVec3d getPosition()
	{
		#if MC_VER <= MC_1_7_10
		EntityPlayerMP player = this.getServerPlayer();
		return new DhVec3d(player.posX, player.posY, player.posZ);
		#elif MC_VER <= MC_1_12_2
		BlockPos position = this.getServerPlayer().getPosition();
		return new DhVec3d(position.getX(), position.getY(), position.getZ());
		#else
		Vec3 position = this.getServerPlayer().position();
		return new DhVec3d(position.x, position.y, position.z);
		#endif
	}
	
	//endregion
	

	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public Object getWrappedMcObject() { return this.getServerPlayer(); }
	
	@Override
	public String toString() { return "Wrapped{" + this.getServerPlayer() + "}"; }
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		if (!(obj instanceof ServerPlayerWrapper))
		{
			return false;
		}
		ServerPlayerWrapper that = (ServerPlayerWrapper) obj;
		return Objects.equal(this.connection, that.connection);
	}
	
	@Override
	public int hashCode() { return Objects.hashCode(this.connection); }
	
	//endregion
	
	
	
}
