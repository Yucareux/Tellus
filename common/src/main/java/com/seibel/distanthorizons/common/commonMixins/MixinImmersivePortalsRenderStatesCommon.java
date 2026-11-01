package com.seibel.distanthorizons.common.commonMixins;

#if MC_VER > MC_1_12_2
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.modAccessor.AbstractImmersivePortalsAccessorCommon;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public class MixinImmersivePortalsRenderStatesCommon
{
	/**
	 * Used to access variables that will change when rendering
	 * different levels with Immersive Portals 
	 * (ie player/camera position level reference)
	 * but that we only want for the loaded level.
	 */
	public static void saveVolatileOriginals()
	{
		AbstractImmersivePortalsAccessorCommon.lastUpdatedMsTime = System.currentTimeMillis();
		
		
		Minecraft mc = Minecraft.getInstance();
		
		AbstractImmersivePortalsAccessorCommon.actualLevel = mc.level;
		
		// clear everything if the player is missing
		// (ie the world hasn't loaded yet)
		if (mc.player == null) 
		{
			AbstractImmersivePortalsAccessorCommon.actualBlockPos = null;
			AbstractImmersivePortalsAccessorCommon.actualChunkPos = null;
			AbstractImmersivePortalsAccessorCommon.actualCameraPos = null;
			return;
		}
		
		// player block pos
		BlockPos playerBlockPos = mc.player.blockPosition();
		AbstractImmersivePortalsAccessorCommon.actualBlockPos = new DhBlockPos(playerBlockPos.getX(), playerBlockPos.getY(), playerBlockPos.getZ());
		
		// player chunk pos
		#if MC_VER < MC_1_17_1
        ChunkPos playerChunkPos = new ChunkPos(mc.player.blockPosition());
        #else
		ChunkPos playerChunkPos = mc.player.chunkPosition();
        #endif
		AbstractImmersivePortalsAccessorCommon.actualChunkPos = McObjectConverter.convert(playerChunkPos);
		
		// camera pos
		#if MC_VER <= MC_1_21_10
		Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
		#elif MC_VER <= MC_26_1_2
		Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
		#else
		Vec3 cameraPos = mc.gameRenderer.mainCamera().position();
		#endif
		AbstractImmersivePortalsAccessorCommon.actualCameraPos = new DhVec3d(cameraPos.x(), cameraPos.y(), cameraPos.z());
	}
	
}
#endif