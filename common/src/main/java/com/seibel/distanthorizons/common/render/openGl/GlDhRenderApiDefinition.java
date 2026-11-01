package com.seibel.distanthorizons.common.render.openGl;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectRenderer;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GlDummyUniformData;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhFarFadeRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fog.GlDhFogRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.ssao.GlDhSSAORenderer;
import com.seibel.distanthorizons.common.render.openGl.test.GlTestTriangleRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;

public class GlDhRenderApiDefinition extends AbstractDhRenderApiDefinition
{
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class); 
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public String getEngineName() { return "OpenGL"; }
	
	public EDhApiDepthDirection getDepthDirection() 
	{
		if (IRIS_ACCESSOR != null
			&& IRIS_ACCESSOR.isShaderPackInUse()
			&& !IRIS_ACCESSOR.isReverseZDuringShaders())
		{
			// reversed Z shouldn't be used when shaders are active
			// in order to maintain legacy behavior
			return EDhApiDepthDirection.FORWARD_Z; 
		}
		
		// reverse Z is better behavior going forward because it prevents
		// issues with clouds and other extremely far objects
		return EDhApiDepthDirection.REVERSE_Z;
	}
	
	public EDhApiDepthRange getDepthRange()
	{
		#if MC_VER <= MC_26_1_2
		return EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
		#else
		// probably caused due to the starting changes to Vulkan
		return EDhApiDepthRange.ZERO_TO_POS_ONE;
		#endif
	}
	
	public EDhApiRenderingApi getRenderApi() { return EDhApiRenderingApi.OPEN_GL; }
	public EDhApiRenderingEngine getRenderingEngine() { return EDhApiRenderingEngine.OPEN_GL; }
	public boolean isNativeRenderer() { return true; }
	
	//endregion
	
	
	
	//============//
	// singletons //
	//============//
	//region
	
	@Override public IDhMetaRenderer getMetaRenderer() { return GlDhMetaRenderer.INSTANCE; }
	@Override public IDhTerrainRenderer getTerrainRenderer() { return GlDhTerrainRenderer.INSTANCE; }
	@Override public IDhSsaoRenderer getSsaoRenderer() { return GlDhSSAORenderer.INSTANCE; }
	@Override public IDhFogRenderer getFogRenderer() { return GlDhFogRenderer.INSTANCE; }
	@Override public IDhFarFadeRenderer getFarFadeRenderer() { return GlDhFarFadeRenderer.INSTANCE; }
	@Override public IDhAntiAliasRenderer getAntiAliasRenderer() { return GlDhTaaRenderer.INSTANCE; }
	@Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return GlDhDebugWireframeRenderer.INSTANCE; }
	
	@Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return GlVanillaFadeRenderer.INSTANCE; }
	@Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return GlTestTriangleRenderer.INSTANCE; }
	
	//endregion
	
	
	
	//===========//
	// factories //
	//===========//
	//region
	
	@Override public IDhGenericRenderer createGenericRenderer() { return new GlGenericObjectRenderer(); }
	
	@Override public IVertexBufferWrapper createVboWrapper(String name) { return new GLVertexBuffer(); }
	@Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new GlDummyUniformData(); }
	@Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new GlGenericObjectVertexContainer(); }
	
	//endregion
	
	
	
}
