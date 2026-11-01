package com.seibel.distanthorizons.core.wrapperInterfaces.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

public abstract class AbstractDhRenderApiDefinition implements IBindable
{
	//=========//
	// getters //
	//=========//
	//region
	
	/** Used for debugging */
	public abstract String getEngineName();
	
	private final boolean useSingleIbo = (EPlatform.get() != EPlatform.MACOS);
	/**
	 * Mac has a problem where binding an IBO that's longer than the VBO
	 * can cause OpenGL to render past the end of the VBO, throwing random junk
	 * on the screen. <br>
	 * To fix this we have to use individual IBOs for each VBO, which
	 * is slower due to having to construct new IBOs.
	 */
	public boolean useSingleIbo() { return this.useSingleIbo; }
	
	public abstract EDhApiDepthDirection getDepthDirection();
	public abstract EDhApiDepthRange getDepthRange();
	public abstract EDhApiRenderingApi getRenderApi();
	public abstract EDhApiRenderingEngine getRenderingEngine();
	/** 
	 * Returns true if the current renderer
	 * is calling the base rendering API's method calls. <br>
	 * ie GL.drawArrays() for OpenGL. <Br><br>
	 *
	 * If DH is using Blaze3D (Mojang's rendering API) 
	 * this will return false.
	 */
	public abstract boolean isNativeRenderer();
	
	//endregion
	
	
	
	//============//
	// singletons //
	//============//
	//region
	
	public abstract IDhMetaRenderer getMetaRenderer();
	public abstract IDhTerrainRenderer getTerrainRenderer();
	public abstract IDhSsaoRenderer getSsaoRenderer();
	public abstract IDhFogRenderer getFogRenderer();
	public abstract IDhFarFadeRenderer getFarFadeRenderer();
	public abstract IDhAntiAliasRenderer getAntiAliasRenderer();
	public abstract AbstractDebugWireframeRenderer getDebugWireframeRenderer();
	public abstract IDhVanillaFadeRenderer getVanillaFadeRenderer();
	public abstract IDhTestTriangleRenderer getTestTriangleRenderer();
	
	/** 
	 * this will NOT run on the render thread.
	 * Render thread setup tasks should be handled
	 * during the first rendered frame.
	 */
	public void bindRenderers()
	{
		SingletonInjector.INSTANCE.bind(AbstractDhRenderApiDefinition.class, this);
		
		SingletonInjector.INSTANCE.bind(IDhMetaRenderer.class, this.getMetaRenderer());
		SingletonInjector.INSTANCE.bind(IDhTerrainRenderer.class, this.getTerrainRenderer());
		SingletonInjector.INSTANCE.bind(IDhSsaoRenderer.class, this.getSsaoRenderer());
		SingletonInjector.INSTANCE.bind(IDhFogRenderer.class, this.getFogRenderer());
		SingletonInjector.INSTANCE.bind(IDhFarFadeRenderer.class, this.getFarFadeRenderer());
		SingletonInjector.INSTANCE.bind(IDhAntiAliasRenderer.class, this.getAntiAliasRenderer());
		SingletonInjector.INSTANCE.bind(AbstractDebugWireframeRenderer.class, this.getDebugWireframeRenderer());
		SingletonInjector.INSTANCE.bind(IDhVanillaFadeRenderer.class, this.getVanillaFadeRenderer());
		SingletonInjector.INSTANCE.bind(IDhTestTriangleRenderer.class, this.getTestTriangleRenderer());
	}
	
	//endregion
	
	
	
	//===========//
	// factories //
	//===========//
	//region
	
	// these methods are used by WrapperFactory
	
	/** 
	 * Generic renderers are created for each level they're used in
	 * so we can't just define a single instance.
	 */
	public abstract IDhGenericRenderer createGenericRenderer();
	
	public abstract IVertexBufferWrapper createVboWrapper(String name);
	public abstract ILodContainerUniformBufferWrapper createLodContainerUniformWrapper();
	public abstract IDhGenericObjectVertexBufferContainer createGenericVboContainer();
	
	//endregion
	
	
	
}
