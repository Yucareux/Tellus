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

package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.MathUtil;

/**
 * This holds miscellaneous helper code
 * used in the rendering process.
 */
public class RenderUtil
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().maxCountPerSecond(1).build();
	
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	/** 
	 * all speeds are measured in blocks per second 
	 * 
	 * @see LodUtil#WALKING_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#SPRINTING_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#ROCKET_ELYTRA_SPEED_IN_BLOCKS_PER_SEC
	 * @see LodUtil#MAX_SPECTATOR_SPEED_IN_BLOCKS_PER_SEC
	 */
	private static class DynamicOverdraw
	{
		public static final float MIN_SPEED = 10.0f; // a little faster than sprinting (7)
		public static final float MAX_SPEED = (float)LodUtil.MAX_SPECTATOR_SPEED_IN_BLOCKS_PER_SEC;
		public static final float MIN_OVERDRAW_RATIO = 0.2f;
	}
	
	
	
	//=====================//
	// matrix manipulation //
	//=====================//
	//region
	
	/**
	 * create and return a new projection matrix based on MC's modelView and projection matrices
	 *
	 * @param mcProjMat Minecraft's current projection matrix
	 */
	public static void setDhProjectionMatrix(DhApiMat4f updateMatrix, DhApiMat4f mcProjMat)
	{
		// in James' testing a near clip plane distance of 2 blocks is enough to allow the fragment
		// culling to take effect instead of seeing the near clip plane.
		float nearClipDist = RenderUtil.getNearClipPlaneInBlocks();
		// limit the near clip plane if we are close to the ground
		if (getHeightBasedNearClipOverrideBlockDistance() == -1)
		{
			// min() used to prevent the near clip plane from becoming visible at large vanilla render distances
			// DH's dithering/discard shader handles everything farther away anyway so the near clip plane
			// would just need to be far enough to prevent depth precision errors
			nearClipDist = Math.min(nearClipDist, 7.5f);
		}
		
		float farClipDist = RenderUtil.getFarClipPlaneDistanceInBlocks();
		
		// Create a copy of the current matrix, so it won't be modified.
		updateMatrix.set(mcProjMat);
		
		
		// Set new far and near clip plane values.
		if (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.FORWARD_Z)
		{
			setClipPlanes(updateMatrix, nearClipDist, farClipDist, false);
		}
		else
		{
			setClipPlanes(updateMatrix, farClipDist, nearClipDist, true);
		}
	}
	
	/**
	 * Changes the values that store the clipping planes.
	 * Formula for calculating matrix values is the same that OpenGL uses when making matrices.
	 *
	 * @param nearClip New near clipping plane value.
	 * @param farClip New far clipping plane value.
	 */
	public static void setClipPlanes(DhApiMat4f matrix, float nearClip, float farClip, boolean zZeroToOne)
	{
		// formula copied JOML's implementation to match Minecraft
		matrix.m22 = (zZeroToOne ? farClip : farClip + nearClip) / (nearClip - farClip);
		matrix.m23 = (zZeroToOne ? farClip : farClip + farClip) * nearClip / (nearClip - farClip);
	}
	
	//endregion
	
	
	
	//=================//
	// near clip plane //
	//=================//
	//region
	
	public static float getNearClipPlaneInBlocks()
	{
		float overdraw = Config.Client.Advanced.Graphics.Culling.overdrawPrevention.get();
		if (overdraw < 0)
		{
			// automatic mode,
			// get overdraw based on vanilla render distance.
			// At low render distances this hides the vanilla RD border
			
			int chunkRenderDistance = MC_RENDER.getRenderDistance();
			
			if (IRIS_ACCESSOR != null
				&& IRIS_ACCESSOR.isShaderPackInUse())
			{
				// shaders handle the near clip plane/overdraw differently, best to play it
				// safe and have the plane really close otherwise
				// there might be cutouts on the screen edges
				overdraw = 0.2f;
			}
			else if (chunkRenderDistance <= 2)
			{
				overdraw = 0.2f;
			}
			else if (chunkRenderDistance <= 4)
			{
				overdraw = 0.3f;
			}
			else if (chunkRenderDistance <= 6)
			{
				overdraw = 0.6f;
			}
			else if (chunkRenderDistance <= 10)
			{
				overdraw = 0.8f;
			}
			else
			{
				overdraw = 0.9f;
			}
		}
		else
		{
			// prevent setting an overdraw of 0
			// since that will cause rendering issues
			overdraw = MathUtil.clamp(0.05f, overdraw, 1.0f);
		}
		
		
		if (Config.Client.Advanced.Graphics.Culling.reduceOverdrawWithFastMovement.get())
		{
			double avgSpeed = ClientApi.INSTANCE.getAvgCameraSpeed();
			if (avgSpeed >= DynamicOverdraw.MIN_SPEED)
			{
				// if the player is moving fast enough,
				// smoothly decrease the fade distance
				// to give MC have a chance to load/generate.
				
				// convert the speed into a range of 0.0 - 1.0
				float speedRange = (float)((DynamicOverdraw.MAX_SPEED - avgSpeed) / DynamicOverdraw.MAX_SPEED);
				// if math.max isn't done here we could completely
				// remove vanilla rendering at high speeds
				speedRange = Math.max(speedRange, DynamicOverdraw.MIN_OVERDRAW_RATIO);
				
				overdraw *= speedRange;
			}
		}
		
		return getNearClipPlaneDistanceInBlocks(overdraw);
	}
	private static float getNearClipPlaneDistanceInBlocks(float overdrawPreventionPercent)
	{
		int chunkRenderDistance = MC_RENDER.getRenderDistance();
		int vanillaBlockRenderedDistance = chunkRenderDistance * LodUtil.CHUNK_WIDTH;
		
		// Note: setting this to a number lower than 1.0 (ie 1 block)
		// can cause distant clouds to flash due to depth buffer precision loss.
		// This is not an issue when using Reverse Z depth.
		float nearClipPlane;
		if (Config.Client.Advanced.Debugging.lodOnlyMode.get())
		{
			nearClipPlane = 0.5f;
		}
		else
		{
			nearClipPlane = vanillaBlockRenderedDistance;
			nearClipPlane *= overdrawPreventionPercent; 
			
			// the near clip plane should never be closer than 1 block,
			// otherwise Z-fighting and other issues may occur
			if (nearClipPlane < 1.0f)
			{
				nearClipPlane = 1.0f;
			}
		}
		
		
		float heightOverride = getHeightBasedNearClipOverrideBlockDistance();
		if (heightOverride != -1.0f)
		{
			nearClipPlane = heightOverride;
		}
		
		
		// the player's FOV setting doesn't affect vanilla's render distance,
		// which can cause issues for certain zoom mods.
		// So the FOV setting should not affect DH's near clip plane;
		// therefore, the FOV is left at a fixed value of 70 (MC's default)
		double fov = 70;
		
		double aspectRatio = (double) MC_RENDER.getTargetFramebufferViewportWidth() / MC_RENDER.getTargetFramebufferViewportHeight();
		
		// source: https://stackoverflow.com/questions/8101119/how-do-i-methodically-choose-the-near-clip-plane-distance-for-a-perspective-proj/8101234#8101234
		return (float) (nearClipPlane
				/ Math.sqrt(1d + MathUtil.pow2(Math.tan(fov / 180d * Math.PI / 2d))
				* (MathUtil.pow2(aspectRatio) + 1d)));
	}
	
	/** 
	 * Returns a new distance if the player is sufficiently far above the world.
	 * @return -1 if no override is necessary 
	 */
	public static float getHeightBasedNearClipOverrideBlockDistance()
	{
		// always using the client level like this might cause issues with immersive portals and the like,
		// but for now it works well enough
		IClientLevelWrapper level = MC.getWrappedClientLevel();
		// a level should always be loaded, but just in case
		if (level != null)
		{
			// if the player is a significant distance above the work, increase the
			// near clip plane to fix Z imprecision issues
			int playerHeight = MC.getPlayerBlockPos().getY();
			int levelMaxHeight = level.getMaxHeight();
			if (playerHeight > levelMaxHeight + 1_000)
			{
				return playerHeight - (levelMaxHeight + 1000);
			}
		}
		
		return -1.0f;
	}
	
	//endregion
	
	
	
	//================//
	// far clip plane //
	//================//
	
	//region
	
	public static float getFarClipPlaneDistanceInBlocks()
	{
		if (IRIS_ACCESSOR != null)
		{
			// Iris doesn't use the far clip plane DH generates, instead
			// they use a manually generated one, which causes problems.
			// This is a hack so DH's far clip plane matches up with what Iris thinks it is,
			// fixing projection/depth mapping.
			// https://github.com/IrisShaders/Iris/issues/2534
			
			int lodChunkDist = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
			int lodBlockDist = lodChunkDist * 16; /* 16 = chunk width in blocks */
			// sqrt 2 to prevent the corners from being cut off
			return (float) ((lodBlockDist + 512 /* 512 = region width in blocks */) * Math.sqrt(2));
		}
		else
		{
			// Current DH logic
			// uses a farther depth to help when far above the world
			
			int lodChunkDist = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
			int lodBlockDist = lodChunkDist * LodUtil.CHUNK_WIDTH;
			// * 2 to prevent clipping when high above the world
			return (lodBlockDist + LodUtil.REGION_WIDTH) * 2;
		}
	}
	
	//endregion
	
	
	
}
