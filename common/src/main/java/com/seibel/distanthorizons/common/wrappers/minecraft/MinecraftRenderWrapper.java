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

package com.seibel.distanthorizons.common.wrappers.minecraft;

import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IAngelicaAccessor;
import org.jetbrains.annotations.Nullable;

#if MC_VER > MC_1_12_2
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
#endif
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;

import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;

#if MC_VER < MC_1_17_1
#elif MC_VER < MC_1_21_3
import net.minecraft.client.renderer.FogRenderer;
#elif MC_VER < MC_1_21_6
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
#else
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_19_2
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.Registry;
#else
import net.minecraft.core.registries.BuiltInRegistries;
#endif

#if MC_VER < MC_1_19_4
#else
#endif

import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IDimensionTypeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IOptifineAccessor;

#if MC_VER <= MC_1_12_2
import com.seibel.distanthorizons.common.commonMixins.IFramebufferDepthTexture;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.Camera;
import net.minecraft.block.Block;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fluids.IFluidBlock;
import org.joml.Vector3d;
#else
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraft.init.MobEffects;
#endif
#else
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffects;

import net.minecraft.world.phys.Vec3;
#endif
import net.minecraft.client.Minecraft;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

#if MC_VER <= MC_1_12_2
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;
#elif MC_VER < MC_1_17_1
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;
#else
import net.minecraft.world.level.material.FogType;
#endif

#if MC_VER <= MC_1_21_4
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.opengl.GlTexture;
#else
import com.mojang.renderpearl.backend.opengl.GlTexture;
#endif

#if MC_VER <= MC_1_21_10
#elif MC_VER <= MC_26_2_0
import net.minecraft.world.attribute.EnvironmentAttributes;
import com.mojang.blaze3d.textures.GpuTexture;
#else
import net.minecraft.world.attribute.EnvironmentAttributes;
import com.mojang.renderpearl.api.textures.GpuTexture;
#endif

/**
 * A singleton that contains everything
 * related to rendering in Minecraft.
 */
public class MinecraftRenderWrapper implements IMinecraftRenderWrapper
{
	public static final MinecraftRenderWrapper INSTANCE = new MinecraftRenderWrapper();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	private static final IOptifineAccessor OPTIFINE_ACCESSOR = ModAccessorInjector.INSTANCE.get(IOptifineAccessor.class);
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MC = Minecraft.getMinecraft();
	#else
	private static final Minecraft MC = Minecraft.getInstance();
	#endif
	
	/** Delayed accessing is necessary since this object will be created before the mod accessors are bound. */
	private static class DelayedAccessors
	{
		public static final IImmersivePortalsAccessor IMMERSIVE_PORTALS = ModAccessorInjector.INSTANCE.get(IImmersivePortalsAccessor.class);
		private static final IIrisAccessor IRIS = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
		private static final IAngelicaAccessor ANGELICA = ModAccessorInjector.INSTANCE.get(IAngelicaAccessor.class);
	}
	
	/**
	 * In the case of immersive portals multiple levels may be active at once, causing conflicting lightmaps. <br>
	 * Requiring the use of multiple {@link LightMapWrapper}.
	 */
	public ConcurrentHashMap<IDimensionTypeWrapper, LightMapWrapper> lightmapByDimensionType = new ConcurrentHashMap<>();
	
	/**
	 * Holds the render buffer that should be used when displaying levels to the screen.
	 * This is used for Optifine shader support so we can render directly to Optifine's level frame buffer.
	 */
	public int finalLevelFrameBufferId = -1;
	
	public boolean colorTextureCastFailLogged = false;
	public boolean depthTextureCastFailLogged = false;
	
	#if MC_VER < MC_1_21_6
	#else
	private static FogRenderer mcFogRenderer = null;
	#endif
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhVec3f getLookAtVector()
	{
		#if MC_VER <= MC_1_7_10
		Vec3 lookVector = MC.renderViewEntity.getLookVec();
		return new DhVec3f((float) lookVector.xCoord, (float) lookVector.yCoord, (float) lookVector.zCoord);
		#elif MC_VER <= MC_1_12_2
		net.minecraft.util.math.Vec3d lookVector = (MC.getRenderViewEntity().getLook(MC.getRenderPartialTicks()));
		return new DhVec3f((float) lookVector.x, (float) lookVector.y, (float) lookVector.z);
		#elif MC_VER <= MC_1_21_10
		Camera camera = MC.gameRenderer.getMainCamera();
		return new DhVec3f(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
		#elif MC_VER <= MC_26_1_2
		Camera camera = MC.gameRenderer.getMainCamera();
		return new DhVec3f(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z());
		#else
		Camera camera = MC.gameRenderer.mainCamera();
		return new DhVec3f(camera.forwardVector().x(), camera.forwardVector().y(), camera.forwardVector().z());
		#endif
	}
	
	/**
	 * Unless you really need to know if the player is blind,
	 * use {@link MinecraftRenderWrapper#isFogStateSpecial()} or {@link IMinecraftRenderWrapper#isFogStateSpecial()} instead
	 */
	@Override
	public boolean playerHasBlindingEffect()
	{
		#if MC_VER <= MC_1_7_10
		if (MC.thePlayer == null)
		#else
		if (MC.player == null)
		#endif
		{
			return false;
		}
		
		
		
		#if MC_VER <= MC_1_7_10
		return MC.thePlayer.getActivePotionEffect(Potion.blindness) != null;
		#elif MC_VER <= MC_1_12_2
		if (MC.player.getActivePotionMap() == null)
		{
			return false;
		}
		
		return MC.player.getActivePotionEffect(MobEffects.BLINDNESS) != null;
		#else
		if (MC.player.getActiveEffectsMap() == null)
		{
			return false;
		}
		
		return MC.player.getActiveEffectsMap().get(MobEffects.BLINDNESS) != null
			#if MC_VER >= MC_1_19_2
			|| MC.player.getActiveEffectsMap().get(MobEffects.DARKNESS) != null // Deep dark effect
			#endif
			;
		#endif
		
	}
	
	@Override
	public DhVec3d getCameraExactPosition()
	{
		// When immersive portals is enabled getting the camera position
		// outside the render thread means you may get the camera for any one of the dimensions
		// immersive portals is currently rendering, which isn't what DH wants.
		// We want the camera that the player is currently looking through.
		if (DelayedAccessors.IMMERSIVE_PORTALS != null
			&& !RenderThreadTaskHandler.INSTANCE.isCurrentThread())
		{
			// this camera position will likely be delayed by 1 frame, so it shouldn't
			// be used for rendering,
			// but anything else that doesn't require that level of percision is fine.
			DhVec3d cameraPos = DelayedAccessors.IMMERSIVE_PORTALS.getActualCameraPos();
			if (cameraPos != null)
			{
				return cameraPos;
			}
		}
		
		
		
		#if MC_VER <= MC_1_7_10
			float frameTime = Minecraft.getMinecraft().timer.renderPartialTicks;
			Camera.INSTANCE.update(MC.renderViewEntity, frameTime);
			Vector3d projectedView = Camera.INSTANCE.getPos();
			return new DhVec3d(projectedView.x, projectedView.y, projectedView.z);
		#elif MC_VER <= MC_1_12_2
			RenderManager rm = MC.getRenderManager();
			return new DhVec3d(rm.viewerPosX, rm.viewerPosY, rm.viewerPosZ);
		#elif MC_VER <= MC_1_21_10
			Camera camera = MC.gameRenderer.getMainCamera();
			Vec3 projectedView = camera.getPosition();
			return new DhVec3d(projectedView.x, projectedView.y, projectedView.z);
		#elif MC_VER <= MC_26_1_2
			Camera camera = MC.gameRenderer.getMainCamera();
			Vec3 projectedView = camera.position();
			return new DhVec3d(projectedView.x, projectedView.y, projectedView.z);
		#else
			Camera camera = MC.gameRenderer.mainCamera();
			Vec3 projectedView = camera.position();
			return new DhVec3d(projectedView.x, projectedView.y, projectedView.z);
		#endif
	}
	
	@Override
	public float getPartialTickTime()
	{
		#if MC_VER <= MC_1_7_10
		return Minecraft.getMinecraft().timer.renderPartialTicks;
		#elif MC_VER <= MC_1_12_2
		return MC.getRenderPartialTicks();
		#elif MC_VER < MC_1_21_1
		return MC.getFrameTime();
		#elif MC_VER < MC_1_21_3
		return MC.getTimer().getRealtimeDeltaTicks();
		#elif MC_VER <= MC_1_21_11
		return MC.deltaTracker.getRealtimeDeltaTicks();
		#else
		return MC.getDeltaTracker().getRealtimeDeltaTicks();
		#endif
	}
	
	@Override
	public Color getFogColor(float partialTicks)
	{
		#if MC_VER < MC_1_17_1
		
		#if MC_VER <= MC_1_7_10
		if (DelayedAccessors.ANGELICA != null)
		{
			return DelayedAccessors.ANGELICA.getFogColor();
		}
		#endif
		
		float[] colorValues = new float[4];
		LWJGL.glGetFloatv(GL11.GL_FOG_COLOR, colorValues);
		return new Color(
			Math.max(0f, Math.min(colorValues[0], 1f)), // r
			Math.max(0f, Math.min(colorValues[1], 1f)), // g
			Math.max(0f, Math.min(colorValues[2], 1f)), // b
			Math.max(0f, Math.min(colorValues[3], 1f))  // a
		);
		#elif MC_VER < MC_1_21_3
		FogRenderer.setupColor(MC.gameRenderer.getMainCamera(), partialTicks, MC.level, 1, MC.gameRenderer.getDarkenWorldAmount(partialTicks));
		float[] colorValues = RenderSystem.getShaderFogColor();
		return new Color(
			Math.max(0f, Math.min(colorValues[0], 1f)), // r
			Math.max(0f, Math.min(colorValues[1], 1f)), // g
			Math.max(0f, Math.min(colorValues[2], 1f)), // b
			Math.max(0f, Math.min(colorValues[3], 1f))  // a
		);
		#elif MC_VER < MC_1_21_6
		Vector4f colorValues = FogRenderer.computeFogColor(MC.gameRenderer.getMainCamera(), partialTicks, MC.level, 1, MC.gameRenderer.getDarkenWorldAmount(partialTicks));
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#elif MC_VER <= MC_1_21_10
		if (mcFogRenderer == null)
		{
			mcFogRenderer = new FogRenderer();
		}
		
		if (MC.level == null)
		{
			// shouldn't happen, but just in case
			return Color.white;
		}
		
		boolean isFoggy = 
				MC.level.effects().isFoggyAt(
						MC.gameRenderer.getMainCamera().getBlockPosition().getX(),
						MC.gameRenderer.getMainCamera().getBlockPosition().getZ()) 
					|| MC.gui.getBossOverlay().shouldCreateWorldFog();
		Vector4f colorValues = mcFogRenderer.setupFog(MC.gameRenderer.getMainCamera(), MC.options.getEffectiveRenderDistance(), isFoggy, MC.deltaTracker, MC.gameRenderer.getDarkenWorldAmount(MC.deltaTracker.getGameTimeDeltaPartialTick(true)), MC.level);
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#else
			
		if (mcFogRenderer == null)
		{
			mcFogRenderer = new FogRenderer();
		}
		
		if (MC.level == null)
		{
			// shouldn't happen, but just in case
			return Color.white;
		}
		
		float darkenAmount;
		#if MC_VER <= MC_1_21_11
		darkenAmount = MC.gameRenderer.getDarkenWorldAmount(MC.deltaTracker.getGameTimeDeltaPartialTick(true));
		#elif MC_VER <= MC_26_1_2
		darkenAmount = MC.gameRenderer.getBossOverlayWorldDarkening(MC.deltaTracker.getGameTimeDeltaPartialTick(true));
		#else
		darkenAmount = MC.gameRenderer.bossOverlayWorldDarkening(MC.deltaTracker.getGameTimeDeltaPartialTick(true));
		#endif
		
		
		#if MC_VER <= MC_26_1_2
		Camera camera = MC.gameRenderer.getMainCamera();
		#else
		Camera camera = MC.gameRenderer.mainCamera();
		#endif
		
		#if MC_VER <= MC_1_21_11
		Vector4f colorValues = mcFogRenderer.setupFog(
			camera,
			MC.options.getEffectiveRenderDistance(),
			MC.deltaTracker,
			darkenAmount,
			MC.level);
		#else
		FogData fogData = mcFogRenderer.setupFog(
			camera,
			MC.options.getEffectiveRenderDistance(),
			MC.deltaTracker,
			darkenAmount,
			MC.level);
		Vector4f colorValues = fogData.color;
		#endif
		
		return new Color(
				Math.max(0f, Math.min(colorValues.x, 1f)), // r
				Math.max(0f, Math.min(colorValues.y, 1f)), // g
				Math.max(0f, Math.min(colorValues.z, 1f)), // b
				Math.max(0f, Math.min(colorValues.w, 1f))  // a
		);
		#endif
	}
	
	@Override
	public Color getSkyColor()
	{
		#if MC_VER <= MC_1_7_10
		if (!MC.theWorld.provider.hasNoSky)
		#elif MC_VER <= MC_1_12_2
		if (MC.world.provider.hasSkyLight())
		#else
		if (MC.level.dimensionType().hasSkyLight())
		#endif
		{
			#if MC_VER <= MC_1_7_10
			float frameTime = this.getPartialTickTime();
			Vec3 colorValues = MC.theWorld.provider.getSkyColor(MC.renderViewEntity, frameTime);
			return new Color((float) colorValues.xCoord, (float) colorValues.yCoord, (float) colorValues.zCoord);
			#elif MC_VER <= MC_1_12_2
			float frameTime = this.getPartialTickTime();
			net.minecraft.util.math.Vec3d colorValues = MC.world.getSkyColor(MC.getRenderViewEntity(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
			#elif MC_VER < MC_1_17_1
			float frameTime = this.getPartialTickTime();
			Vec3 colorValues = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getBlockPosition(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
			#elif MC_VER < MC_1_21_3
			float frameTime = this.getPartialTickTime();
			Vec3 colorValues = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getPosition(), frameTime);
			return new Color((float) colorValues.x, (float) colorValues.y, (float) colorValues.z);
			#elif MC_VER <= MC_1_21_10
			float frameTime = this.getPartialTickTime();
			int argbColorInt = MC.level.getSkyColor(MC.gameRenderer.getMainCamera().getPosition(), frameTime);
			return ColorUtil.toColorObjARGB(argbColorInt);
			#elif MC_VER <= MC_26_1_2
			int argbColor = MC.level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, MC.gameRenderer.getMainCamera().position());
			return new Color(ColorUtil.getRed(argbColor), ColorUtil.getGreen(argbColor), ColorUtil.getBlue(argbColor), 255 /* ignore alpha since DH clouds don't render correctly with transparency */);
			#elif MC_VER <= MC_26_2_0
			int argbColor = MC.level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, MC.gameRenderer.mainCamera().position());
			return new Color(ColorUtil.getRed(argbColor), ColorUtil.getGreen(argbColor), ColorUtil.getBlue(argbColor), 255 /* ignore alpha since DH clouds don't render correctly with transparency */);
			#else
			Vector3fc argbColor = MC.level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, MC.gameRenderer.mainCamera().position());
			return new Color(argbColor.x(), argbColor.y(), argbColor.z(), 1.0f /* ignore alpha since DH clouds don't render correctly with transparency */);
			#endif
		}
		else
		{
			return new Color(0, 0, 0);
		}
	}
	
	/** Measured in chunks */
	@Override
	public int getRenderDistance()
	{
		#if MC_VER <= MC_1_12_2
		return MC.gameSettings.renderDistanceChunks;
		#elif MC_VER <= MC_1_17_1
		return MC.options.renderDistance;
		#else
		return MC.options.getEffectiveRenderDistance();
		#endif
	}
	
	/** Measured in degrees */
	@Override
	public double getFovSetting()
	{
		#if MC_VER <= MC_1_12_2
		return MC.gameSettings.fovSetting;
		#elif MC_VER <= MC_1_18_2
		return MC.options.fov;
		#else
		return MC.options.fov().get();
		#endif
	}
	
	@Override
	public int getFrameLimit()
	{
		#if MC_VER <= MC_1_12_2
		return MC.gameSettings.limitFramerate;
		#elif MC_VER <= MC_1_18_2
		return MC.options.framerateLimit;
		#else
		return MC.options.framerateLimit().get();
		#endif
	}
	
	#if MC_VER > MC_1_12_2
	public RenderTarget getRenderTarget()
	{
		#if MC_VER <= MC_26_1_2
		return MC.getMainRenderTarget();
		#else
		return MC.gameRenderer.mainRenderTarget();
		#endif
	}
	#endif
	
	@Override
	public boolean mcRendersToFrameBuffer()
	{
		#if MC_VER < MC_1_21_5
		return true;
		#else
		return false;
		#endif
	}
	
	@Override
	public boolean runningLegacyOpenGL()
	{
		#if MC_VER <= MC_1_16_5
		return true;
		#else
		return false;
		#endif
	}
	
	private EDhApiRenderingApi renderApi = null;
	@Override
	public EDhApiRenderingApi getMcRenderingApi()
	{
		if (this.renderApi != null)
		{
			return this.renderApi;
		}
		
		
		#if MC_VER <= MC_26_1_2
		this.renderApi = EDhApiRenderingApi.OPEN_GL;
		#else
		String backendName = RenderSystem
			.getDevice()
			.getDeviceInfo()
			.backendName();
		boolean isVulkan = backendName.equalsIgnoreCase("Vulkan");
		this.renderApi = isVulkan ? EDhApiRenderingApi.VULKAN : EDhApiRenderingApi.OPEN_GL;
		#endif
		return this.renderApi;
	}
	
	@Override 
	public EDhApiDepthDirection getMcDepthDirection()
	{
		if (DelayedAccessors.IRIS != null
			&& DelayedAccessors.IRIS.isShaderPackInUse())
		{
			if (DelayedAccessors.IRIS.isReverseZDuringShaders())
			{
				return EDhApiDepthDirection.REVERSE_Z;
			}
			else
			{
				return EDhApiDepthDirection.FORWARD_Z;
			}
		}
		
		#if MC_VER <= MC_26_1_2
		return EDhApiDepthDirection.FORWARD_Z;
		#else
		return EDhApiDepthDirection.REVERSE_Z;
		#endif
	}
	
	@Override
	public int getTargetFramebuffer()
	{
		// used so we can access the framebuffer shaders end up rendering to
		if (OPTIFINE_ACCESSOR != null)
		{
			return this.finalLevelFrameBufferId;
		}
		
		#if MC_VER <= MC_1_12_2
		return MC.getFramebuffer().framebufferObject;
		#elif MC_VER < MC_1_21_5
		return this.getRenderTarget().frameBufferId;
		#else
		// MC renders to a texture and then directly to the default FBO now
		// we need to draw to their texture instead of the FBO
		return 0; // 0 is the ID for the default frame buffer
		#endif
	}
	
	@Override
	public void clearTargetFrameBuffer() { this.finalLevelFrameBufferId = -1; }
	
	@Override
	public int getGlDepthTextureId()
	{
		#if MC_VER <= MC_1_7_10
		if (DelayedAccessors.ANGELICA != null)
		{
			return DelayedAccessors.ANGELICA.getDepthTextureId();
		}
		
		final Framebuffer framebuffer = Minecraft.getMinecraft().getFramebuffer();
		return framebuffer.depthBuffer;
		#elif MC_VER <= MC_1_12_2
		final Framebuffer framebuffer = MC.getFramebuffer();
		if (DelayedAccessors.IRIS != null)
		{
			return DelayedAccessors.IRIS.getFramebufferDepthTextureId(framebuffer);
		}
		else
		{
			return ((IFramebufferDepthTexture) framebuffer).distantHorizons$getDistantHorizonsDepthTexture();
		}
		#elif MC_VER < MC_1_21_5
		return this.getRenderTarget().getDepthTextureId();
		#else
		try
		{		
			GlTexture glTexture = (GlTexture) this.getRenderTarget().getDepthTexture();
			if (glTexture == null)
			{
				// shouldn't happen, but just in case
				return -1;
			}

			return glTexture.glId();
			
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.depthTextureCastFailLogged)
			{
				this.depthTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target depth texture to GlTexture. MC or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	@Override
	public int getGlColorTextureId()
	{
		#if MC_VER <= MC_1_12_2
		return MC.getFramebuffer().framebufferTexture;
		#elif MC_VER < MC_1_21_5
		return this.getRenderTarget().getColorTextureId();
		#else
		try
		{
			GlTexture glTexture = (GlTexture) this.getRenderTarget().getColorTexture();
			if (glTexture == null)
			{
				// shouldn't happen, but just in case
				return -1;
			}
			
			return glTexture.glId();
		}
		catch (Exception e)
		{
			// only log this error once per session
			if (!this.colorTextureCastFailLogged)
			{
				this.colorTextureCastFailLogged = true;
				LOGGER.error("Unable to cast render Target color texture to GlTexture. MC or a rendering mod may have changed the object type.", e);
			}
			return -1;
		}
		#endif
	}
	
	@Override
	public int getTargetFramebufferViewportWidth()
	{
		#if MC_VER <= MC_1_12_2
		return MC.getFramebuffer().framebufferWidth;
		#elif MC_VER < MC_1_21_9
		return this.getRenderTarget().viewWidth;
		#else
		return this.getRenderTarget().width;
		#endif
	}
	
	@Override
	public int getTargetFramebufferViewportHeight()
	{
		#if MC_VER <= MC_1_12_2
		return MC.getFramebuffer().framebufferHeight;
		#elif MC_VER < MC_1_21_9
		return this.getRenderTarget().viewHeight;
		#else
		return this.getRenderTarget().height;
		#endif
	}
	
	@Override
	public boolean isFogStateSpecial()
	{
		#if MC_VER <= MC_1_7_10
		DhVec3d cameraPos = this.getCameraExactPosition();
		
		Block fluidBlock = MC.renderViewEntity.worldObj.getBlock(
			MathHelper.floor_double(cameraPos.x),
			MathHelper.floor_double(cameraPos.y),
			MathHelper.floor_double(cameraPos.z));
		
		return this.playerHasBlindingEffect() 
			|| fluidBlock.getMaterial().isLiquid() 
			|| fluidBlock instanceof IFluidBlock;
		#elif MC_VER <= MC_1_12_2
		BlockPos blockPos = new BlockPos(MC.getRenderViewEntity().getPositionEyes(MC.getRenderPartialTicks()));
		IBlockState fluidState = MC.getRenderViewEntity().world.getBlockState(blockPos);
		return this.playerHasBlindingEffect()
			|| fluidState.getMaterial().isLiquid()
			|| fluidState.getBlock() instanceof IFluidBlock;
		
		#elif MC_VER < MC_1_17_1
		Camera camera = MC.gameRenderer.getMainCamera();
		FluidState fluidState=camera.getFluidInCamera();
		boolean cameraNotInFluid =! (fluidState.is(FluidTags.WATER) || fluidState.is(FluidTags.LAVA));
	    if(cameraNotInFluid)
	    {	
			String fluidAtCamera= Registry.FLUID.getKey(MC.level.getFluidState(MC.gameRenderer.getMainCamera().getBlockPosition()).getType()).toString();	
			boolean isNotAtVanillaFluid =! 
				(
					fluidAtCamera.equals("minecraft:water") 
					|| fluidAtCamera.equals("minecraft:lava")
				) 
				&& !fluidAtCamera.equals("minecraft:empty");
			cameraNotInFluid =! (cameraNotInFluid && isNotAtVanillaFluid);
		}
		boolean isBlind = this.playerHasBlindingEffect();
		return !cameraNotInFluid || isBlind;
		
		#elif MC_VER <= MC_1_19_2
		boolean cameraNotInFluid=MC.gameRenderer.getMainCamera().getFluidInCamera() == FogType.NONE;	
	    if(cameraNotInFluid)
	    {	
			String fluidAtCamera= Registry.FLUID.getKey(MC.level.getFluidState(MC.gameRenderer.getMainCamera().getBlockPosition()).getType()).toString();	
			boolean isNotAtVanillaFluid =! 
				(
					fluidAtCamera.equals("minecraft:water")
					|| fluidAtCamera.equals("minecraft:lava")
				)
				&& !fluidAtCamera.equals("minecraft:empty");
			cameraNotInFluid =! (cameraNotInFluid && isNotAtVanillaFluid);
		}
		boolean isBlind = this.playerHasBlindingEffect();
		return !cameraNotInFluid || isBlind;

		#elif MC_VER <= MC_1_21_10
		boolean cameraNotInFluid = MC.gameRenderer.getMainCamera().getFluidInCamera() == FogType.NONE;
		if(cameraNotInFluid)
		{
			String fluidAtCamera = BuiltInRegistries.FLUID.getKey(MC.level.getFluidState(MC.gameRenderer.getMainCamera().getBlockPosition()).getType()).toString();
			boolean isNotAtVanillaFluid =! 
				(
					fluidAtCamera.equals("minecraft:water")
					|| fluidAtCamera.equals("minecraft:lava")
				)
				&& !fluidAtCamera.equals("minecraft:empty");
			cameraNotInFluid =! (cameraNotInFluid && isNotAtVanillaFluid);
		}
		boolean isBlind = this.playerHasBlindingEffect();
		return !cameraNotInFluid || isBlind;
		
		#elif MC_VER <= MC_26_1_2
		boolean cameraNotInFluid=MC.gameRenderer.getMainCamera().getFluidInCamera() == FogType.NONE;	
		if(cameraNotInFluid)
		{	
			String fluidAtCamera=BuiltInRegistries.FLUID.getKey(MC.level.getFluidState(MC.gameRenderer.getMainCamera().blockPosition()).getType()).toString();
			boolean isNotAtVanillaFluid = 
				!(
					fluidAtCamera.equals("minecraft:water")
					|| fluidAtCamera.equals("minecraft:lava")
				)
				&& !fluidAtCamera.equals("minecraft:empty");
			cameraNotInFluid =! (cameraNotInFluid && isNotAtVanillaFluid);
		}
		boolean isBlind = this.playerHasBlindingEffect();
		return !cameraNotInFluid || isBlind;
		
		#else
		boolean cameraNotInFluid = MC.gameRenderer.mainCamera().getFluidInCamera() == FogType.NONE;
		if(cameraNotInFluid)
		{
			String fluidAtCamera = BuiltInRegistries.FLUID.getKey(
				MC.level.getFluidState(
					MC.gameRenderer.mainCamera().blockPosition()
				).getType())
				.toString();
			boolean isNotAtVanillaFluid =! 
				(
					fluidAtCamera.equals("minecraft:water")
					|| fluidAtCamera.equals("minecraft:lava")
				)
				&& !fluidAtCamera.equals("minecraft:empty");
			cameraNotInFluid =! (cameraNotInFluid && isNotAtVanillaFluid);
		}
		boolean isBlind = this.playerHasBlindingEffect();
		return !cameraNotInFluid || isBlind;
		
		#endif
	}
	
	
	
	//==========//
	// lightmap //
	//==========//
	//region
	
	@Override
	#if MC_VER <= MC_1_7_10
	// No mixin populates the map on 1.7.10 
	// (LightMapWrapper.getOpenGlId() queries MC
	// directly there, so the wrapper instance is effectively stateless). 
	// Lazy-create one per dimension to keep the same map semantics as the other loaders.
	public ILightMapWrapper getLightmapWrapper(@NotNull ILevelWrapper level) { return this.lightmapByDimensionType.computeIfAbsent(level.getDimensionType(), k -> new LightMapWrapper()); }
	#else
	public ILightMapWrapper getLightmapWrapper(@NotNull ILevelWrapper level) { return this.lightmapByDimensionType.get(level.getDimensionType()); }
	#endif
	
	/**
	 * It's better to use {@link MinecraftRenderWrapper#setLightmapId(int)} if possible,
	 * however old MC versions don't support it.
	 */
	#if MC_VER > MC_1_12_2
	public void updateLightmap(NativeImage lightPixels)
	{
		IClientLevelWrapper clientLevel = getLightmapClientLevelWrapper();
		if (clientLevel == null)
		{
			return;
		}
		
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = clientLevel.getDimensionType();
		
		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.uploadLightmap(lightPixels);
	}
	#endif
	public void setLightmapId(int textureId)
	{
		IClientLevelWrapper clientLevel = getLightmapClientLevelWrapper();
		if (clientLevel == null)
		{
			return;
		}
		
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = clientLevel.getDimensionType();
		
		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.setLightmapId(textureId);
	}
	
	#if MC_VER <= MC_1_21_10
	#else
	public void setLightmapGpuTexture(GpuTexture gpuTexture)
	{
		IClientLevelWrapper clientLevel = getLightmapClientLevelWrapper();
		if (clientLevel == null)
		{
			return;
		}
	
		// Using ClientLevelWrapper as the key would be better, but we don't have a consistent way to create the same
		// object for the same MC level and/or the same hash,
		// so this will have to do for now
		IDimensionTypeWrapper dimensionType = clientLevel.getDimensionType();

		LightMapWrapper wrapper = this.lightmapByDimensionType.computeIfAbsent(dimensionType, (dimType) -> new LightMapWrapper());
		wrapper.setLightmapGpuTexture(gpuTexture);
	}
	#endif
	
	/** special logic is necessary in order for Immersive Portals to work correctly */
	private static @Nullable IClientLevelWrapper getLightmapClientLevelWrapper()
	{
		IClientLevelWrapper clientLevel = ClientApi.RENDER_STATE.clientLevelWrapper;
		if (clientLevel == null)
		{
			clientLevel = MC_CLIENT.getWrappedClientLevel();
		}
		
		return clientLevel;
	}
	
	//endregion
	
	
	
}
