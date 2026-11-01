package com.seibel.distanthorizons.common.commonMixins;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_12_2
#else
import net.minecraft.client.Camera;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
#endif

#if MC_VER <= MC_1_7_10

#elif MC_VER <= MC_1_12_2
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraft.block.state.IBlockState;
#elif MC_VER < MC_1_17_1
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_3
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#elif MC_VER < MC_1_21_6
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Vector4f;
import com.mojang.blaze3d.systems.RenderSystem;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#else
import net.minecraft.world.level.material.FogType;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.FogData;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
#endif


public class MixinVanillaFogCommon
{
	#if MC_VER <= MC_1_7_10
	public static boolean cancelFog()
	#elif MC_VER <= MC_1_12_2
	public static boolean cancelFog(int startCoords, Minecraft mc)
	#elif MC_VER < MC_1_21_6
	public static boolean cancelFog(Camera camera, FogRenderer.FogMode fogMode)
	#else
	public static boolean cancelFog()
	#endif
	{
		// don't cancel fog if DH isn't rendering
		if (!Config.Client.quickEnableRendering.get())
		{
			return false;
		}
		
		
		
		#if MC_VER <= MC_1_7_10
		// entity not used in 1.7.10
		#elif MC_VER <= MC_1_12_2
		EntityPlayerSP entity = mc.player;
		#elif MC_VER < MC_1_21_6
		Entity entity = camera.getEntity();
		#elif MC_VER <= MC_1_21_10
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Entity entity = camera.getEntity();
		#elif MC_VER <= MC_26_1_2
		Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		Entity entity = camera.entity();	
		#else
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Entity entity = camera.entity();	
		#endif
		
		#if MC_VER <= MC_1_7_10
		boolean cameraNotInFluid = cameraNotInFluid();
		#elif MC_VER <= MC_1_12_2
		boolean cameraNotInFluid = cameraNotInFluid(mc);
		#else
		boolean cameraNotInFluid = cameraNotInFluid(camera);
		#endif
		
		#if MC_VER <= MC_1_7_10
		boolean isSpecialFog = false;
		#elif MC_VER <= MC_1_12_2
		boolean isSpecialFog = entity.isPotionActive(MobEffects.BLINDNESS);
		#else
		boolean isSpecialFog = (entity instanceof LivingEntity) && ((LivingEntity) entity).hasEffect(MobEffects.BLINDNESS);
		#endif
		
		boolean cancelFog = !isSpecialFog;
		cancelFog = cancelFog && cameraNotInFluid;
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		cancelFog = cancelFog && startCoords == 0; // 0 = terrain fog
		#elif MC_VER < MC_1_21_6
		cancelFog = cancelFog && (fogMode == FogRenderer.FogMode.FOG_TERRAIN);
		#endif
		
		cancelFog = cancelFog && !SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class).isFogStateSpecial();
		cancelFog = cancelFog && !Config.Client.Advanced.Graphics.Fog.enableVanillaFog.get();
		
		
		// since DH won't render through immersive portals 
		// the vanilla fog should be enabled
		IImmersivePortalsAccessor immersivePortals = ModAccessorInjector.INSTANCE.get(IImmersivePortalsAccessor.class);
		if (immersivePortals != null
			&& immersivePortals.isRenderingPortal())
		{
			cancelFog = false;
		}
		
		return cancelFog;
	}
	
	#if MC_VER <= MC_1_7_10
	private static boolean cameraNotInFluid()
	#elif MC_VER <= MC_1_12_2
	private static boolean cameraNotInFluid(Minecraft mc)
	#else
	private static boolean cameraNotInFluid(Camera camera)
	#endif
	{
		#if MC_VER <= MC_1_7_10
		boolean cameraNotInFluid = true;
		#elif MC_VER <= MC_1_12_2
		Entity view = mc.getRenderViewEntity();
		if (view == null)
		{
			 return true;
		}
		
		IBlockState fluidState = mc.world.getBlockState(new BlockPos(view.getPositionEyes(mc.getRenderPartialTicks())));
		boolean cameraNotInFluid = !(fluidState.getMaterial().isLiquid() || fluidState.getBlock() instanceof IFluidBlock);
		#elif MC_VER < MC_1_17_1
		FluidState fluidState = camera.getFluidInCamera();
		boolean cameraNotInFluid = fluidState.isEmpty();
		#else
		FogType fogTypes = camera.getFluidInCamera();
		boolean cameraNotInFluid = (fogTypes == FogType.NONE);
		#endif
		
		return cameraNotInFluid;
	}
	
	
	
}
