package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDhRenderApiDefinition {}

#else

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.common.render.blaze.objects.BlazeGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.blaze.postProcessing.*;
import com.seibel.distanthorizons.common.render.blaze.test.BlazeDhTestTriangleRenderer;
import com.seibel.distanthorizons.common.render.blaze.wrappers.buffer.BlazeVertexBufferWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeLodUniformBufferWrapper;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;

public class BlazeDhRenderApiDefinition extends AbstractDhRenderApiDefinition
{
	//=========//
	// getters //
	//=========//
	//region
	
	private final String engineName;
	public String getEngineName() { return this.engineName; }
	
	@Override
	public EDhApiDepthDirection getDepthDirection()
	{
		#if MC_VER <= MC_26_1_2
		return EDhApiDepthDirection.FORWARD_Z;
		#else
		return EDhApiDepthDirection.REVERSE_Z;
		#endif
	}
	
	@Override
	public EDhApiDepthRange getDepthRange()
	{
		#if MC_VER <= MC_26_1_2
		return EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
		#else
		// probably caused due to the starting changes to Vulkan
		return EDhApiDepthRange.ZERO_TO_POS_ONE;
		#endif
	}
	
	private final EDhApiRenderingApi renderApi;
	public EDhApiRenderingApi getRenderApi() { return this.renderApi; }
	public EDhApiRenderingEngine getRenderingEngine() { return EDhApiRenderingEngine.BLAZE_3D; }
	public boolean isNativeRenderer() { return false; }
	
	//endregion
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeDhRenderApiDefinition()
	{
		#if MC_VER <= MC_26_1_2
		renderApi = EDhApiRenderingApi.OPEN_GL;
		#else
		// Blaze always uses the same rendering API as Minecraft
		this.renderApi = MinecraftRenderWrapper.INSTANCE.getMcRenderingApi();
		#endif
		
		this.engineName = "Blaze3D: " + this.getRenderApi();
	}
	
	//endregion
	
	
	
	//============//
	// singletons //
	//============//
	//region
	
	@Override public IDhMetaRenderer getMetaRenderer() { return BlazeDhMetaRenderer.INSTANCE; }
	@Override public IDhTerrainRenderer getTerrainRenderer() { return BlazeDhTerrainRenderer.INSTANCE; }
	@Override public IDhSsaoRenderer getSsaoRenderer() { return BlazeDhSsaoRenderer.INSTANCE; }
	@Override public IDhFogRenderer getFogRenderer() { return BlazeDhFogRenderer.INSTANCE; }
	@Override public IDhFarFadeRenderer getFarFadeRenderer() { return BlazeDhFarFadeRenderer.INSTANCE; }
	@Override public IDhAntiAliasRenderer getAntiAliasRenderer() { return BlazeDhTaaRenderer.INSTANCE; }
	@Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return BlazeDebugWireframeRenderer.INSTANCE; }
	
	@Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return BlazeVanillaFadeRenderer.INSTANCE; }
	@Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return BlazeDhTestTriangleRenderer.INSTANCE; }
	
	//endregion
	
	
	
	//===========//
	// factories //
	//===========//
	//region
	
	@Override public IDhGenericRenderer createGenericRenderer() { return new BlazeDhGenericObjectRenderer(); }
	
	@Override public IVertexBufferWrapper createVboWrapper(String name) { return new BlazeVertexBufferWrapper(name); }
	@Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new BlazeLodUniformBufferWrapper(); }
	@Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new BlazeGenericObjectVertexContainer(); }
	
	//endregion
	
	
	
}
#endif