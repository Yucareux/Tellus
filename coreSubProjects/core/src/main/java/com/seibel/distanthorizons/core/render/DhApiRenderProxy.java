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

package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.jetbrains.annotations.Nullable;

/**
 * Used to interact with Distant Horizons' rendering systems.
 *
 * @author James Seibel
 * @version 2023-2-8
 */
public class DhApiRenderProxy implements IDhApiRenderProxy
{
	public static final DhApiRenderProxy INSTANCE = new DhApiRenderProxy();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	private boolean deferTransparentRendering = false;
	
	private static AbstractDhRenderApiDefinition renderApiDef = null;
	@Nullable
	private static AbstractDhRenderApiDefinition tryGetApiDef()
	{
		if (renderApiDef == null)
		{
			renderApiDef = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
		}
		
		return renderApiDef; 
	}
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private DhApiRenderProxy() { }
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhApiResult<Boolean> clearRenderDataCache()
	{
		// make sure this is a valid time to run the method
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world == null)
		{
			return DhApiResult.createFail("No world loaded");
		}
		
		
		// clear the render caches for each level
		Iterable<? extends IDhLevel> loadedLevels = world.getAllLoadedLevels();
		for (IDhLevel level : loadedLevels)
		{
			if (level instanceof IDhClientLevel)
			{
				((IDhClientLevel) level).clearRenderCache();
			}
		}
		
		return DhApiResult.createSuccess();
	}
	
	@Override 
	public EDhApiRenderingApi getRenderingApi() throws IllegalStateException
	{
		AbstractDhRenderApiDefinition apiDef = tryGetApiDef();
		if (apiDef == null)
		{
			// The rendering API hasn't been set up yet
			throw new IllegalStateException("Distant Horizons hasn't finished setup yet. No renderer has been set.");
		}
		
		return apiDef.getRenderApi();
	}
	
	@Override 
	public EDhApiRenderingEngine getRenderingEngine() throws IllegalStateException
	{
		AbstractDhRenderApiDefinition apiDef = tryGetApiDef();
		if (apiDef == null)
		{
			// The rendering API hasn't been set up yet
			throw new IllegalStateException("Distant Horizons hasn't finished setup yet. No renderer has been set.");
		}
		
		return apiDef.getRenderingEngine();
	}
	
	@Override 
	public boolean isNativeRenderer() throws IllegalStateException
	{
		AbstractDhRenderApiDefinition apiDef = tryGetApiDef();
		if (apiDef == null)
		{
			// The rendering API hasn't been set up yet
			throw new IllegalStateException("Distant Horizons hasn't finished setup yet. No renderer has been set.");
		}
		
		return apiDef.isNativeRenderer();
	}
	
	@Override
	public EDhApiDepthRange getDepthRange() throws IllegalStateException
	{
		AbstractDhRenderApiDefinition apiDef = tryGetApiDef();
		if (apiDef == null)
		{
			// The rendering API hasn't been set up yet
			throw new IllegalStateException("Distant Horizons hasn't finished setup yet. No renderer has been set.");
		}
		
		return apiDef.getDepthRange();
	}
	@Override
	public EDhApiDepthDirection getDepthDirection() throws IllegalStateException
	{
		AbstractDhRenderApiDefinition apiDef = tryGetApiDef();
		if (apiDef == null)
		{
			// The rendering API hasn't been set up yet
			throw new IllegalStateException("Distant Horizons hasn't finished setup yet. No renderer has been set.");
		}
		
		return apiDef.getDepthDirection();
	}
	
	
	public static int activeOpenGlDhDepthTextureId = -1;
	@Override
	public DhApiResult<Integer> getDhDepthTextureGlId()
	{
		int activeTexture = activeOpenGlDhDepthTextureId;
		return (activeTexture == -1) 
			? DhApiResult.createFail("DH's depth texture hasn't been created and/or bound yet.", -1)
			: DhApiResult.createSuccess(activeTexture);
	}
	
	public static IDhApiBlazeTextureWrapper activeBlazeDhDepthTextureWrapper = null;
	@Override
	public DhApiResult<IDhApiBlazeTextureWrapper> getDhDepthTextureBlazeWrapper()
	{
		IDhApiBlazeTextureWrapper activeTexture = activeBlazeDhDepthTextureWrapper;
		return (activeTexture == null) 
			? DhApiResult.createFail("DH's depth texture hasn't been created and/or bound yet.", null) 
			: DhApiResult.createSuccess(activeTexture);
	}
	
	
	public static int activeOpenGlDhColorTextureId = -1;
	@Override
	public DhApiResult<Integer> getDhColorTextureGlId()
	{
		int activeTexture = activeOpenGlDhColorTextureId;
		return (activeTexture == -1) 
			? DhApiResult.createFail("DH's color texture hasn't been created and/or bound yet.", -1) 
			: DhApiResult.createSuccess(activeTexture);
	}
	
	public static IDhApiBlazeTextureWrapper activeBlazeDhColorTextureWrapper = null;
	@Override
	public DhApiResult<IDhApiBlazeTextureWrapper> getDhColorTextureBlazeWrapper()
	{
		IDhApiBlazeTextureWrapper activeTexture = activeBlazeDhColorTextureWrapper;
		return (activeTexture == null) 
			? DhApiResult.createFail("DH's color texture hasn't been created and/or bound yet.", null) 
			: DhApiResult.createSuccess(activeTexture);
	}
	
	
	
	public static int getDhBlockRatioAtlasTextureGlId = -1;
	@Override
	public DhApiResult<Integer> getDhBlockRatioAtlasTextureGlId()
	{
		int activeTexture = getDhBlockRatioAtlasTextureGlId;
		return (activeTexture == -1)
			? DhApiResult.createFail("DH's block ratio atlas texture hasn't been created and/or bound yet.", -1)
			: DhApiResult.createSuccess(activeTexture);
	}
	
	public static IDhApiBlazeTextureWrapper activeBlazeDhBlockRatioAtlasTextureWrapper = null;
	@Override
	public DhApiResult<IDhApiBlazeTextureWrapper> getDhBlockRatioAtlasTextureBlazeWrapper()
	{
		IDhApiBlazeTextureWrapper activeTexture = activeBlazeDhBlockRatioAtlasTextureWrapper;
		return (activeTexture == null)
			? DhApiResult.createFail("DH's block ratio atlas texture hasn't been created and/or bound yet.", null)
			: DhApiResult.createSuccess(activeTexture);
	}
	
	
	
	@Override 
	public void setDeferTransparentRendering(boolean deferTransparentRendering) { this.deferTransparentRendering = deferTransparentRendering; }
	@Override 
	public boolean getDeferTransparentRendering() { return this.deferTransparentRendering; }
	
	@Override
	public float getNearClipPlaneDistanceInBlocks(float partialTicks) { return RenderUtil.getNearClipPlaneInBlocks(); }
	
	
	
}
