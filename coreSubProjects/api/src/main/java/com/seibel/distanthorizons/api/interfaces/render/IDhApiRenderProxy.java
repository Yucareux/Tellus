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

package com.seibel.distanthorizons.api.interfaces.render;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.objects.DhApiResult;


/**
 * Used to interact with Distant Horizons' rendering system.
 *
 * @author James Seibel
 * @version 2024-7-27
 * @since API 1.0.0
 */
public interface IDhApiRenderProxy
{
	/**
	 * Forces any cached render data to be deleted and regenerated.
	 * This is generally called whenever resource packs are changed or specific
	 * rendering settings are changed in Distant Horizon's config. <Br><Br>
	 *
	 * If this is called on a dedicated server it won't do anything and will return {@link DhApiResult#success} = false <Br><Br>
	 *
	 * Background: <Br>
	 * When rendering Distant Horizons bakes each block's color into the geometry that's rendered. <Br>
	 * This improves rendering speed and VRAM size, but prevents dynamically changing LOD colors. <Br>
	 */
	DhApiResult<Boolean> clearRenderDataCache();
	
	/**
	 * Returns which specific {@link EDhApiRenderingApi}
	 * Distant Horizons will use for rendering. <br><br>
	 * 
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 * 
	 * @see DhApiAfterDhInitEvent
	 * @since API 7.0.0
	 */
	EDhApiRenderingApi getRenderingApi() throws IllegalStateException;
	/**
	 * Returns which specific {@link EDhApiRenderingEngine}
	 * Distant Horizons will use for rendering. <br><br>
	 * 
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 * 
	 * @see DhApiAfterDhInitEvent
	 * @since API 7.1.0
	 */
	EDhApiRenderingEngine getRenderingEngine() throws IllegalStateException;
	/**
	 * Returns true if the current renderer
	 * is calling the base rendering API's method calls. <br>
	 * ie GL.drawArrays() for OpenGL. <Br><br>
	 *
	 * If DH is using a rendering interpretation layer like Blaze3D (Mojang's rendering API) 
	 * this will return false.
	 *
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 *
	 * @see DhApiAfterDhInitEvent
	 * @since API 7.0.0
	 */
	boolean isNativeRenderer() throws IllegalStateException;
	
	/**
	 * Returns the currently active {@link EDhApiDepthRange} DH is rendering with this frame. <Br>
	 * This value may change from frame to frame based on config and other active mods.
	 *
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 *
	 * @since API 7.2.0
	 */
	EDhApiDepthRange getDepthRange() throws IllegalStateException;
	/**
	 * Returns the currently active {@link EDhApiDepthDirection} DH is rendering with this frame. <Br>
	 * This value may change from frame to frame based on config and other active mods.
	 *
	 * @throws IllegalStateException if no renderer has been bound yet, 
	 *      wait till after {@link DhApiAfterDhInitEvent} has been fired
	 *
	 * @since API 7.2.0
	 */
	EDhApiDepthDirection getDepthDirection() throws IllegalStateException;
	
	
	
	//=======================//
	// OpenGL object getters //
	//=======================//
	
	/** @deprecated in favor of {@link IDhApiRenderProxy#getDhDepthTextureGlId} */
	@Deprecated // don't remove, this method is needed by Iris
	default DhApiResult<Integer> getDhDepthTextureId() { return this.getDhDepthTextureGlId(); }
	/**
	 * Returns the OpenGL name of Distant Horizons' depth texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = -1 if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#OPEN_GL} is in use.
	 *
	 * @see IDhApiRenderProxy#getRenderingEngine()
	 * @since API 7.1.0
	 */
	DhApiResult<Integer> getDhDepthTextureGlId();
	/**
	 * Returns a wrapper around the Blaze3D objects that represent Distant Horizons' depth texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = null if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#BLAZE_3D} is in use.
	 *
	 * @see IDhApiRenderProxy#getRenderingEngine()
	 * @since API 7.1.0
	 */
	DhApiResult<IDhApiBlazeTextureWrapper> getDhDepthTextureBlazeWrapper();
	
	
	/** @deprecated in favor of {@link IDhApiRenderProxy#getDhDepthTextureGlId} */
	@Deprecated // don't remove, this method is needed by Iris
	default DhApiResult<Integer> getDhColorTextureId() { return this.getDhColorTextureGlId(); }
	/**
	 * Returns the OpenGL name of Distant Horizons' color texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = -1 if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#OPEN_GL} is in use.
	 *
	 * @see IDhApiRenderProxy#getRenderingEngine()
	 * @since API 7.1.0
	 */
	DhApiResult<Integer> getDhColorTextureGlId();
	/**
	 * Returns a wrapper around the Blaze3D objects that represent Distant Horizons' color texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = null if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#BLAZE_3D} is in use.
	 *
	 * @see IDhApiRenderProxy#getRenderingEngine() 
	 * @since API 7.1.0
	 */
	DhApiResult<IDhApiBlazeTextureWrapper> getDhColorTextureBlazeWrapper();
	
	/**
	 * Returns the OpenGL name of Distant Horizons' block ratio atlas texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = -1 if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#OPEN_GL} is in use. <br><br>
	 *  
	 * The term "block ratio atlas" means the texture is a ratio between DH's underlying color
	 * and the color the block should have for that face. <br>
	 * A color value of 0.5f (or half a byte) means DH's base color will be used for that pixel. <br><br>
	 *  
	 * <b>Note:</b> <br>
	 * This texture is likely to be recreated/changed without warning.
	 * If you want to use this texture at render-time it's recommended you
	 * query this method every time before binding.
	 * 
	 * @see IDhApiRenderProxy#getRenderingEngine()
	 * @since API 7.1.0
	 */
	DhApiResult<Integer> getDhBlockRatioAtlasTextureGlId();
	/**
	 * Returns a wrapper around the Blaze3D objects that represent Distant Horizons' block ratio atlas texture. <br>
	 * Will return {@link DhApiResult#success} = false and {@link DhApiResult#payload} = null if the texture hasn't been created yet
	 * or a rendering API other than {@link EDhApiRenderingEngine#BLAZE_3D} is in use. <br><br>
	 * 
	 * The term "block ratio atlas" means the texture is a ratio between DH's underlying color
	 * and the color the block should have for that face. <br>
	 * A color value of 0.5f (or half a byte) means DH's base color will be used for that pixel. <br><br>
	 *
	 * <b>Note:</b> <br>
	 * This texture is likely to be recreated/changed without warning.
	 * If you want to use this texture at render-time it's recommended you
	 * query this method every time before binding.
	 *
	 * @see IDhApiRenderProxy#getRenderingEngine()
	 * @since API 7.1.0
	 */
	DhApiResult<IDhApiBlazeTextureWrapper> getDhBlockRatioAtlasTextureBlazeWrapper();
	
	
	
	//======================//
	// Shader compatibility //
	//======================//
	
	/**
	 * If set to true DH won't render opaque and transparent LODs in the same pass.
	 * Instead, opaque objects will be rendered at the normal time, but 
	 * transparent objects will only be rendered in a second pass during Minecraft's
	 * own transparent rendering pass.
	 */
	void setDeferTransparentRendering(boolean deferTransparentRendering);
	/** @return If DH should defer transparent rendering or not. */
	boolean getDeferTransparentRendering();
	
	/** This may change based on FOV, player speed, and other factors. */
	float getNearClipPlaneDistanceInBlocks(float partialTicks);
	
	
}
