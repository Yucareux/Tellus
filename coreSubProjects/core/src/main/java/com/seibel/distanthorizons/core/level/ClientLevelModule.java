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

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;

import javax.annotation.WillNotClose;
import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class ClientLevelModule implements Closeable, IDataSourceUpdateListenerFunc<FullDataSourceV2>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	
	private final IDhClientLevel clientLevel;
	
	@WillNotClose
	public final FullDataSourceProviderV2 fullDataSourceProvider;
	public final AtomicReference<ClientRenderState> ClientRenderStateRef = new AtomicReference<>();
	/** 
	 * This is handled outside of the {@link ClientRenderState} to prevent destroying
	 * the {@link IDhGenericRenderer} when changing render distances or enabling/disabling rendering. <br><br>
	 * 
	 * Destroying the {@link IDhGenericRenderer} would cause any existing bindings to be 
	 * erroneously removed.
	 */
	public final IDhGenericRenderer genericRenderer = WRAPPER_FACTORY.createGenericRenderer();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ClientLevelModule(IDhClientLevel clientLevel)
	{
		this.clientLevel = clientLevel;
		
		this.fullDataSourceProvider = this.clientLevel.getFullDataProvider();
		this.fullDataSourceProvider.addDataSourceUpdateListener(this);
	}
	
	
	
	//==============//
	// tick methods //
	//==============//
	
	public void clientTick()
	{
		// can be false if the level is unloading
		if (!MC_CLIENT.playerExists())
		{
			return;
		}
		
		ClientRenderState clientRenderState = this.ClientRenderStateRef.get();
		if (clientRenderState == null)
		{
			return;
		}
		
		// recreate the RenderState if the render distance changes
		if (clientRenderState.viewDiameterInBlocks != ClientRenderState.getViewDiameterInBlocks())
		{
			// close the older renderer
			clientRenderState.close();
			this.ClientRenderStateRef.set(null);
			
			// create the new renderer
			clientRenderState = new ClientRenderState(this.clientLevel, this.clientLevel.getFullDataProvider());
			this.ClientRenderStateRef.set(clientRenderState);
		}
		
		
		DhBlockPos2D quadTreeTickBlockPos;
		if (Config.Client.Advanced.Graphics.Quality.useCameraPositionForQualityDropOff.get())
		{
			// use camera position allow free cam mods work better
			DhVec3d cameraDoublePos = MC_RENDER.getCameraExactPosition();
			quadTreeTickBlockPos = new DhBlockPos2D((int)cameraDoublePos.x, (int)cameraDoublePos.z);
		}
		else
		{
			// player position allows multi-cam mods to work better
			quadTreeTickBlockPos = new DhBlockPos2D(MC_CLIENT.getPlayerBlockPos());
		}
		
		clientRenderState.quadtree.tryTick(quadTreeTickBlockPos);
	}
	
	
	
	//========//
	// render //
	//========//
	
	public void startRenderer()
	{
		ClientRenderState clientRenderState = new ClientRenderState(this.clientLevel, this.clientLevel.getFullDataProvider());
		if (!this.ClientRenderStateRef.compareAndSet(null, clientRenderState))
		{
			LOGGER.warn("Renderer already started for ["+this.clientLevel.getClientLevelWrapper()+"].");
			clientRenderState.close();
		}
	}
	
	public boolean isRendering() { return this.ClientRenderStateRef.get() != null; }
	
	public void stopRenderer()
	{
		ClientRenderState clientRenderState = this.ClientRenderStateRef.get();
		if (clientRenderState == null)
		{
			LOGGER.warn("Tried to stop the renderer for [" + this + "] when it was not started.");
			return;
		}
		
		this.ClientRenderStateRef.compareAndSet(clientRenderState, null);
		clientRenderState.close();
	}
	
	
	
	//===============//
	// data handling //
	//===============//
	
	public CompletableFuture<Void> updateDataSourcesAsync(FullDataSourceV2 data) 
	{ return this.clientLevel.getFullDataProvider().updateDataSourceAsync(data); }
	@Override
	public void OnDataSourceUpdated(FullDataSourceV2 updatedFullDataSource)
	{
		// if rendering, also update the render sources
		ClientRenderState ClientRenderState = this.ClientRenderStateRef.get();
		if (ClientRenderState != null)
		{
			ClientRenderState.quadtree.queuePosToReload(updatedFullDataSource.getPos());
		}
	}
	
	public void close()
	{
		// shutdown the renderer
		ClientRenderState ClientRenderState = this.ClientRenderStateRef.get();
		if (ClientRenderState != null)
		{
			this.ClientRenderStateRef.compareAndSet(ClientRenderState, null);
			ClientRenderState.close();
		}
		
		this.fullDataSourceProvider.removeDataSourceUpdateListener(this);
		this.genericRenderer.close();
	}
	
	
	
	//=======================//
	// misc helper functions //
	//=======================//
	
	public void clearRenderCache()
	{
		IClientLevelWrapper clientLevelWrapper = this.clientLevel.getClientLevelWrapper();
		if (clientLevelWrapper != null)
		{
			clientLevelWrapper.clearBlockColorCache();
		}
		
		ClientRenderState ClientRenderState = this.ClientRenderStateRef.get();
		if (ClientRenderState != null && ClientRenderState.quadtree != null)
		{
			ClientRenderState.quadtree.clearRenderDataCache();
		}
	}
	
	public void reloadPos(long pos)
	{
		ClientRenderState clientRenderState = this.ClientRenderStateRef.get();
		if (clientRenderState != null && clientRenderState.quadtree != null)
		{
			clientRenderState.quadtree.queuePosToReload(pos);
		}
	}

	public void onGeneratorPlanChanged()
	{
		ClientRenderState clientRenderState = this.ClientRenderStateRef.get();
		if (clientRenderState != null)
		{
			clientRenderState.quadtree.onConfigValueSet();
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	public static class ClientRenderState implements Closeable
	{
		private static final DhLogger LOGGER = new DhLoggerBuilder().build();
		
		public final LodQuadTree quadtree;
		public final RenderBufferHandler renderBufferHandler;
		
		public final int viewDiameterInBlocks;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		public ClientRenderState(
				IDhClientLevel dhClientLevel, 
				FullDataSourceProviderV2 fullDataSourceProvider)
		{
			this.viewDiameterInBlocks = getViewDiameterInBlocks();
			
			this.quadtree = new LodQuadTree(
				dhClientLevel,
				this.viewDiameterInBlocks,
				// initial position is (0,0) just in case the player hasn't loaded in yet, the tree will be moved once the level starts ticking
				0, 0,
				fullDataSourceProvider);
			
			this.renderBufferHandler = new RenderBufferHandler(this.quadtree);
		}
		
		/**
		 * Used both during setup and
		 * to determine if the render distance changed.
		 */
		public static int getViewDiameterInBlocks() 
		{ 
			return Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() 
				* LodUtil.CHUNK_WIDTH 
				* 2; 
		}
		
		
		
		//================//
		// base overrides //
		//================//
		
		@Override
		public void close()
		{
			//LOGGER.info("Shutting down " + ClientRenderState.class.getSimpleName());
			this.quadtree.close();
		}
		
	}
	
}
