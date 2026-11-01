package com.seibel.distanthorizons.common.commonMixins;

import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
#if MC_VER <= MC_1_12_2
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
#else
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#endif

public class MixinChunkMapCommon
{
	#if MC_VER <= MC_1_12_2
	public static void onChunkSave(WorldServer level, Chunk chunk)
	#else
	public static void onChunkSave(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<Boolean> ci)
	#endif
	{
		IServerLevelWrapper levelWrapper = ServerLevelWrapper.getWrapper(level);
		
		int chunkPosX;
		int chunkPosZ;
		#if MC_VER <= MC_1_7_10
		chunkPosX = chunk.xPosition;
		chunkPosZ = chunk.zPosition;
		#elif MC_VER <= MC_1_21_11
		chunkPosX = chunk.getPos().x;
		chunkPosZ = chunk.getPos().z;
		#else
		chunkPosX = chunk.getPos().x();
		chunkPosZ = chunk.getPos().z();
		#endif
		
		// is this position already being updated?
		if (SharedApi.isChunkAtChunkPosAlreadyUpdating(levelWrapper, chunkPosX, chunkPosZ))
		{
			return;
		}
		
		
		
		// is this chunk being saved to disk?
		boolean savingChunkToDisk = #if MC_VER <= MC_1_12_2 true #else ci.getReturnValue() #endif;
		// true means a chunk was saved to disk
		if (!savingChunkToDisk)
		{
			return;
		}
		
		
		
		// corrupt/incomplete chunk validation //
		
		// MC has a tendency to try saving incomplete or corrupted chunks (which show up as empty or black chunks)
		// this logic should prevent that from happening
		#if MC_VER <= MC_1_7_10
		if (!ChunkWrapper.canSaveChunk(chunk))
		{
			return;
		}
		#elif MC_VER <= MC_1_12_2
		if (!chunk.isTerrainPopulated() || !chunk.isLightPopulated())
		{
			return;
		}
		#elif MC_VER <= MC_1_17_1
		if (chunk.isUnsaved() || chunk.getUpgradeData() != null || !chunk.isLightCorrect())
		{
			return;
		}
		#else
		if (chunk.isUnsaved() || chunk.isUpgrading() || !chunk.isLightCorrect() || chunk instanceof ProtoChunk)
		{
			return;
		}
		#endif
		
		
		
		// biome validation //
		
		// some chunks may be missing their biomes, which cause issues when attempting to save them
		#if MC_VER <= MC_1_7_10
		// (no check on 1.7.10: the biome array is always allocated by the Chunk constructor, and
		//  ChunkWrapper reads biomes via World.getBiomeGenForCoords instead of the array anyway.
		//  Touching it would also crash under EndlessIDs, which replaces it with a short array.)
		#elif MC_VER <= MC_1_12_2
		if (chunk.getBiomeArray() == null)
		{
			return;
		}
		#elif MC_VER <= MC_1_17_1
		if (chunk.getBiomes() == null)
		{
			return;
		}
		#else
		try
		{
			// this will throw an exception if the biomes aren't set up
			chunk.getNoiseBiome(0,0,0);
		}
		catch (Exception e)
		{
			return;
		}
		#endif
		
		
		
		// submit the update event
		ServerApi.INSTANCE.serverChunkSaveEvent(
			new ChunkWrapper(chunk, levelWrapper),
			levelWrapper
		);
	}
}
