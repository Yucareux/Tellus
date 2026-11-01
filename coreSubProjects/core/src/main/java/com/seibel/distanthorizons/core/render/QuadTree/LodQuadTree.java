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

package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdatePropagatorV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.BeaconRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.repo.BeaconBeamRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.WorldGenUtil;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.WillNotClose;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This quadTree structure is our core data structure and holds
 * all rendering data.
 */
public class LodQuadTree extends QuadTree<LodRenderSection> implements IDebugRenderable, IConfigListener, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	/** how often generation positions the tree no longer wants are dropped */
	private static final long GENERATION_PRUNE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
	
	
	public final int blockRenderDistanceDiameter;
	@WillNotClose
	private final FullDataSourceProviderV2 fullDataSourceProvider;
	
	/** there should only ever be one {@link LodQuadTree} so having the thread static should be fine */
	private final ThreadPoolExecutor fullDataRetrievalQueueThread;
	
	/**
	 * This holds every {@link DhSectionPos} that should be reloaded next tick. <br>
	 * This is a {@link ConcurrentLinkedQueue} because new sections can be added to this list via the world generator threads.
	 */
	private final ConcurrentLinkedQueue<Long> sectionsToReload = new ConcurrentLinkedQueue<>();
	private final IDhClientLevel level;
	/** 
	 * Note: this doesn't lock all operations as some other threads/operations
	 * that may traverse the tree while it's being modified.
	 * IE {@link RenderBufferHandler} will walk through the tree each frame.
	 */
	private final ReentrantLock treeTickLock = new ReentrantLock();
	
	private final AtomicBoolean requeueAllRetrievalTasksRef = new AtomicBoolean(false);
	private final AtomicBoolean queueThreadRunningRef = new AtomicBoolean(false);
	
	
	/**
	 * contains the list of beacons currently being rendered in this section 
	 * if this list is modified the {@link LodQuadTree#beaconRenderHandler} should be updated to match.
	 */
	private final ArrayList<BeaconRenderHandler.BeaconBeamWithWidth> beaconList = new ArrayList<>();
	@Nullable
	private final BeaconRenderHandler beaconRenderHandler;
	@Nullable
	private final BeaconBeamRepo beaconBeamRepo;
	/** used to prevent updating the beacons concurrently */
	@NotNull
	private CompletableFuture<Void> beaconUpdateFuture = CompletableFuture.completedFuture(null);
	
	
	/** 
	 * The smallest numerical detail level number that can be rendered. <Br>
	 * Stored as an absolute detail level (ie lowest numerical value is 0)
	 */
	private byte maxLeafRenderDetailLevel;
	/** 
	 * the largest numerical detail level number that can be rendered. <Br>
	 * Stored as an absolute detail level (ie lowest numerical value is 0)
	 */
	private byte minRootRenderDetailLevel;
	
	/** used to calculate when a detail drop will occur */
	private double detailDropOffDistanceUnit;
	/** used to calculate when a detail drop will occur */
	private double detailDropOffLogBase;
	
	/** the {@link DhSectionPos} that need to be retrieved/generated */
	private final Set<Long> missingGenerationPosSet = Collections.newSetFromMap(new ConcurrentHashMap<>()); // concurrency is annoying but required due to needing to add/remove items in the world gen future
	private final Set<Long> queuedGenerationPosSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
	/**
	 * Generation positions dropped from {@link LodQuadTree#missingGenerationPosSet} because the tree
	 * stopped wanting that detail there (IE a camera zoom ended or the player moved away). <br>
	 * If one of them is wanted again its node has to look for missing positions again.
	 */
	private final Set<Long> prunedGenerationPosSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** generation positions created by splitting a task -> the position the tree originally asked for */
	private final ConcurrentHashMap<Long, Long> splitGenerationRootByPos = new ConcurrentHashMap<>();
	private long lastGenerationPruneNanos = 0L;
	/** cached array to prevent having to re-allocate it each tick */
	private final ArrayList<Long> sortedMissingPosList = new ArrayList<>();
	
	private long lastRegenTaskCountQueryMs = 0L;
	private long cachedRegenTaskCount = 0L;
	
	private final ArrayList<LodRenderSection> debugNodeList = new ArrayList<>();
	/** cached to prevent re-allocating each tick */
	private final QuadTreeTickNodeHolder tickNodeHolder = new QuadTreeTickNodeHolder();
	
	/** list of sections that should be rendered */
	private ArrayList<LodRenderSection> enabledSections = new ArrayList<>();
	/** alternate list for thread safety  */
	private ArrayList<LodRenderSection> altEnabledSections = new ArrayList<>();
	/** This lock should be very quick since it will be used on the render thread */ 
	private final ReentrantLock enabledRenderSectionLock = new ReentrantLock();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region constructor
	
	public LodQuadTree(
			IDhClientLevel level, int viewDiameterInBlocks,
			int initialPlayerBlockX, int initialPlayerBlockZ,
			FullDataSourceProviderV2 fullDataSourceProvider)
	{
		super(viewDiameterInBlocks, FullDataSourceV2.WIDTH, 
			new DhBlockPos2D(initialPlayerBlockX, initialPlayerBlockZ), DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		
		DEBUG_RENDERER.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus);
		
		this.level = level;
		this.fullDataSourceProvider = fullDataSourceProvider;
		this.blockRenderDistanceDiameter = viewDiameterInBlocks;
		
		this.fullDataRetrievalQueueThread = ThreadUtil.makeSingleDaemonThreadPool("["+this.level.getLevelWrapper().getDimensionName()+"] LodQuadTree Data Retrieval Queue");
		
		IDhGenericRenderer genericObjectRenderer = this.level.getGenericRenderer();
		this.beaconRenderHandler = (genericObjectRenderer != null) ? new BeaconRenderHandler(genericObjectRenderer) : null;
		
		this.beaconBeamRepo = this.level.getBeaconBeamRepo();
		
		Config.Common.WorldGenerator.generatorPlan.addListener(this);
		
	}
	
	//endregion constructor
	
	
	
	//==================//
	// property getters //
	//==================//
	//region property getters
	
	public void populateListWithEnabledRenderSections(ArrayList<LodRenderSection> tempProcessNodeList)
	{
		try
		{
			// lock for thread safety
			this.enabledRenderSectionLock.lock();
			
			tempProcessNodeList.clear();
			
			// manual add and loop to reduce GC pressure due to addAll() doing unnecessary
			// array copies
			for (int i = 0; i < this.enabledSections.size(); i++)
			{
				tempProcessNodeList.add(this.enabledSections.get(i));
			}
		}
		finally
		{
			this.enabledRenderSectionLock.unlock();
		}
	}
	
	//endregion property getters
	
	
	
	//=============//
	// tick update //
	//=============//
	//region tick update
	
	/** 
	 * update the quadTree using the playerPos 
	 * and queue any necessary work based on the tree's state.
	 */
	public void tryTick(DhBlockPos2D playerPos)
	{
		if (this.level == null)
		{
			// the quad tree was created before a level reference was created
			return;
		}
		
		
		
		// don't tick the tree if a modification is still going
		if (this.treeTickLock.tryLock())
		{
			// this shouldn't be updated while the tree is being iterated through
			this.updateDetailLevelVariables();
			
			try
			{
				this.updateAllRenderSections(playerPos);
			}
			catch (Exception e)
			{
				LOGGER.error("Quad Tree tick exception for level: [" + this.level.getLevelWrapper().getDhIdentifier() + "], error: [" + e.getMessage() + "].", e);
			}
			finally
			{
				this.treeTickLock.unlock();
			}
		}
	}
	private void updateAllRenderSections(DhBlockPos2D playerPos)
	{
		// this data will be updated as we walk through the tree
		this.tickNodeHolder.clear();
		
		
		//===================//
		// recenter the tree //
		//===================//
		//region
		
		// remove out of bound sections
		this.setCenterBlockPos(playerPos, 
			// remove completely out of bound nodes
			// (the root node is no longer in bounds)
			(renderSection) ->
			{
				if (renderSection != null)
				{
					this.fullDataSourceProvider.removeRetrievalRequestIf((long genPos) -> DhSectionPos.contains(renderSection.pos, genPos));
					
					// unfortunately we have to fully go through each set
					// since a removed position may be larger than the multiple generated positions
					// it contains
					this.missingGenerationPosSet.removeIf((Long genPos) -> DhSectionPos.contains(renderSection.pos, genPos));
					this.queuedGenerationPosSet.removeIf((Long genPos) -> DhSectionPos.contains(renderSection.pos, genPos));
					this.prunedGenerationPosSet.removeIf((Long genPos) -> DhSectionPos.contains(renderSection.pos, genPos));
					this.splitGenerationRootByPos.keySet().removeIf((Long genPos) -> DhSectionPos.contains(renderSection.pos, genPos));
					
					renderSection.close();
				}
			},
			// mutate partially out of bound nodes
			// (the root node is still in bounds, but this individual child node isn't)
			(renderSection) ->
			{
				if (renderSection != null)
				{
					// when this node comes back into render distance
					// we'll need to re-load it since the full data
					// may have been modified while it was out of bounds
					renderSection.renderDataDirty = true;
				}
			}
		);
		
		//endregion
		
		
		
		//=======================//
		// walk through the tree //
		//=======================//
		//region
		
		// walk through each root node
		LongIterator rootPosIterator = this.rootNodePosIterator();
		while (rootPosIterator.hasNext())
		{
			// make sure all root nodes have been created
			long rootPos = rootPosIterator.nextLong();
			if (this.getNode(rootPos) == null)
			{
				this.setValue(rootPos, new LodRenderSection(rootPos, this, this.level, this.fullDataSourceProvider));
			}
			
			QuadNode<LodRenderSection> rootNode = this.getNode(rootPos);
			LodUtil.assertTrue(rootNode != null, "All root nodes should have been created by this point.");
			this.recursivelyUpdateRenderSectionNode(
				playerPos, 
				rootNode, null, rootNode, rootNode.sectionPos);
		}
		
		//endregion
		
		
		
		//============//
		// queue work //
		//============//
		//region
		
		if (this.requeueAllRetrievalTasksRef.getAndSet(false))
		{
			Iterator<QuadNode<LodRenderSection>> nodeIterator = this.nodeIterator();
			while (nodeIterator.hasNext())
			{
				QuadNode<LodRenderSection> node = nodeIterator.next();
				if (node == null || node.value == null)
				{
					continue;
				}
				
				node.value.queuedMissingSectionsForRetrieval = false;
			}
		}
		
		// reloading is for sections that have been loaded once already,
		// loading is for sections that haven't rendered yet
		this.queueRenderLoads(playerPos, this.tickNodeHolder.getLoadSections());
		
		//endregion
		
		
		
		//==================//
		// enable rendering //
		//==================//
		//region
		
		this.altEnabledSections.clear();
		
		for (QuadNode<LodRenderSection> node : this.tickNodeHolder.getEnabledNodes())
		{
			// shouldn't happen, but just in case
			if (node == null || node.value == null) { continue; }
			
			node.value.setRenderingEnabled(true);
			this.altEnabledSections.add(node.value);
		}
		for (QuadNode<LodRenderSection> node : this.tickNodeHolder.getEnableDeleteChildrenNodes())
		{
			if (node == null || node.value == null) { continue; }
			
			node.value.setRenderingEnabled(true);
			this.altEnabledSections.add(node.value);
		}
		
		//endregion
		
		
		
		//====================//
		// update render list //
		//====================//
		//region
		
		try
		{
			this.enabledRenderSectionLock.lock();
			
			ArrayList<LodRenderSection> temp = this.enabledSections;
			this.enabledSections = this.altEnabledSections;	
			this.altEnabledSections = temp;
		}
		finally
		{
			this.enabledRenderSectionLock.unlock();
		}
		
		//endregion
		
		
		
		//=========================//
		// node disabling/deletion //
		//=========================//
		//region
		
		for (QuadNode<LodRenderSection> node : this.tickNodeHolder.getDisableNodes())
		{
			if (node == null || node.value == null) { continue; }
			
			node.value.setRenderingEnabled(false);
		}
		
		for (QuadNode<LodRenderSection> node : this.tickNodeHolder.getEnableDeleteChildrenNodes())
		{
			if (node == null
				|| node.value == null
				// only clear the children if there are children to clear
				|| node.getDirectChildCount() == 0) 
			{
				continue; 
			}
			
			// we have conflicting desires for running this method.
			// 1. running on the render thread to prevent closing data sources that are actively being rendered.
			// 2. closing nodes that were queued due to zoom, but are no longer needed.
			// If we run this on the render thread we lag the render thread and end up queueing a lot
			// of unnecessary work.
			// However, running this on this thread prevents infinitely growing lod-loading tasks.
			// Due to the problems with infinitely growing tasks, we're going to run this method
			// here instead of the render thread.
			node.deleteAllChildren((childRenderSection) ->
			{
				if (childRenderSection != null)
				{
					childRenderSection.setRenderingEnabled(false);
					childRenderSection.close();
				}
			});
		}
		
		//endregion
		
		
		
		//=================//
		// beacon enabling //
		//=================//
		//region
		
		this.tryRefreshRenderingBeaconsAsync(playerPos);
		
		//endregion
		
		
		
		//===================//
		// world gen queuing //
		//===================//
		//region
		
		this.pruneUnwantedGenerationPositions(playerPos);
		
		// queue full data retrieval (world gen) requests if needed
		if (threadPoolCanAcceptWorldGenTasks()
			&& this.fullDataSourceProvider.canQueueRetrievalNow()
			&& !this.queueThreadRunningRef.get())
		{
			this.queueThreadRunningRef.set(true); // don't run multiple threads at once
			
			// needs to be called outside the queue thread to prevent concurrency issues
			ArrayList<QuadNode<LodRenderSection>> worldGenNodes = this.tickNodeHolder.getWorldGenNodesNearToFar(playerPos);
			
			// running on a separate thread allows for faster loading
			// of finished LODs
			this.fullDataRetrievalQueueThread.execute(() ->
			{
				try
				{
					for (int i = 0; i < worldGenNodes.size(); i++)
					{
						QuadNode<LodRenderSection> node = worldGenNodes.get(i);
						if (node == null 
							|| node.value == null) 
						{
							continue; 
						}
						
						// since this section wants to render
						// check if it needs any generation to do so
						if (!node.value.queuedMissingSectionsForRetrieval)
						{
							node.value.queuedMissingSectionsForRetrieval = true;
							this.tryQueuePosForRetrieval(node.value.pos); // can be quite slow
						}
					}
					
					// Keep looping as long as there's something to queue.
					// This allows really fast generators to remain fed.
					while (!Thread.interrupted())
					{
						int missingPosCountBeforeQueuing = this.missingGenerationPosSet.size();
						
						this.startQueuedRetrievalTasks(playerPos);
						
						
						boolean workRemaining = !this.missingGenerationPosSet.isEmpty();
						if (!workRemaining)
						{
							// Nothing left to queue, the queuing thread can stop looking.
							// The next tree tick will restart it when new work shows up.
							break;
						}
						
						boolean somethingQueued = this.missingGenerationPosSet.size() != missingPosCountBeforeQueuing;
						if (!somethingQueued)
						{
							// there's work in the queue, but we weren't able to
							// queue any of it this loop (generator queue is probably full).
							// Stop instead of waiting here: the next tree tick restarts the queuing
							// with the sections and player position of that moment,
							// otherwise a moving player keeps getting LODs queued around where they used to be
							// and newly needed sections aren't added until this backlog is gone.
							break;
						}
					}
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected error starting queued retrieval tasks, error: [" + e.getMessage() + "].", e);
				}
				finally
				{
					this.queueThreadRunningRef.set(false);
				}
			});
		}
		
		//endregion
		
	}
	
	//=========================//
	// tick - recursive update //
	//=========================//
	//region
	
	/** @return true if the node at this position has uploaded its render data */
	private boolean recursivelyUpdateRenderSectionNode(
		@NotNull DhBlockPos2D playerPos, 
		@NotNull QuadNode<LodRenderSection> rootNode, 
		@Nullable QuadNode<LodRenderSection> parentNode, 
		@Nullable QuadNode<LodRenderSection> quadNode, 
		long sectionPos // section pos is needed here since the quad node may be null
		)
	{
		//=====================//
		// get/create the node //
		// and render section  //
		//=====================//
		//region
		
		quadNode = this.tryAddNodeToTree(rootNode, quadNode, sectionPos);
		
		
		// Skip sections that are out-of-bounds.
		// If not done some sections will appear and/or generate 
		// outside the desired render distance
		if (!this.isSectionPosInBounds(quadNode.sectionPos))
		{
			this.tickNodeHolder.addDisableNode(quadNode);
			this.recursivelyDisableChildNodes(quadNode);
			return true;
		}
		
		
		// make sure the render section is created (shouldn't be necessary, but just in case)
		LodRenderSection renderSection = quadNode.value;
		if (renderSection == null)
		{
			renderSection = new LodRenderSection(sectionPos, this, this.level, this.fullDataSourceProvider);
			quadNode.setValue(sectionPos, renderSection);
		}
		
		//endregion
		
		
		
		//===============================//
		// handle enabling, loading,     //
		// and disabling render sections //
		//===============================//
		//region
		
		// load every node for rendering
		if (!renderSection.gpuUploadInProgress()
			&& !renderSection.gpuUploadComplete())
		{
			this.tickNodeHolder.addLoadSection(renderSection);
		}
		
		
		byte expectedDetailLevel = this.calcExpectedSectionDetailLevel(playerPos, quadNode.sectionPos);
		
		if (DhSectionPos.getDetailLevel(quadNode.sectionPos) > expectedDetailLevel)
		{
			return this.onDetailLevelTooLow(playerPos, rootNode, quadNode);
		}
		// the (expectedDetailLevel-1) fixes corners being cut out due to distance calculations using the LOD center 
		else if (DhSectionPos.getDetailLevel(quadNode.sectionPos) == expectedDetailLevel 
			|| DhSectionPos.getDetailLevel(quadNode.sectionPos) == expectedDetailLevel - 1)
		{
			return this.onDesiredDetailLevel(quadNode, parentNode);
		}
		else
		{
			// A zoomed in camera boosts detail directionally, 
			// so when a node intersects the zoom cone and we recurse down,
			// its out-of-cone children can end up several
			// detail levels finer than their own distance alone would request.
			// Rendering them at the finer level keeps the tree consistent and only
			// affects a thin shell of sections around the zoom cone.
			if (CameraZoom.INSTANCE.magnification > CameraZoom.NOT_ZOOMED_MAGNIFICATION)
			{
				return this.onDesiredDetailLevel(quadNode, parentNode);
			}
			
			throw new IllegalStateException("LodQuadTree shouldn't be updating renderSections below the expected detail level: [" + expectedDetailLevel + "].");
		}
		
		//endregion
	}
	/** @return true if the node at this position has uploaded its render data */
	private boolean onDetailLevelTooLow(
		@NotNull DhBlockPos2D playerPos, 
		@NotNull QuadNode<LodRenderSection> rootNode, 
		@NotNull QuadNode<LodRenderSection> quadNode)
	{
		// recursively update each child node
		int childNodeRenderCount = 0;
		for (int i = 0; i < 4; i++)
		{
			long childPos = DhSectionPos.getChildByIndex(quadNode.sectionPos, i);
			QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
			
			boolean childCanRender = this.recursivelyUpdateRenderSectionNode(
				playerPos,
				rootNode, quadNode, childNode, childPos);
			if (childCanRender)
			{
				// node can be rendered
				childNodeRenderCount++;
			}
		}
		
		boolean isRootNode = (quadNode == rootNode);
		if (isRootNode)
		{
			// Never render the root node.
			// This is done to prevent flashing when moving across root node
			// boundaries.
			// Otherwise, when moving, new empty nodes will be added at the edge of the tree
			// which will require the root node to render to cover the "empty" area.
			this.tickNodeHolder.addDisableNode(quadNode);
			return false;
		}
		else if (childNodeRenderCount >= 4)
		{
			this.tickNodeHolder.addDisableNode(quadNode);
			
			// all children can render,
			// the area will be filled when rendering
			return true;
		}
		else
		{
			// A section that hasn't been generated yet uploads an empty mesh,
			// so only a section with data for its whole area can stand in for its children.
			boolean nodeCanRender = quadNode.value != null
									&& quadNode.value.canRenderWholeArea();
			if (nodeCanRender)
			{
				// not all child positions are loaded yet, this one should be rendered instead
				this.tickNodeHolder.addEnableNode(quadNode);
				this.recursivelyDisableChildNodes(quadNode);
			}
			else
			{
				this.tickNodeHolder.addDisableNode(quadNode);
			}
			
			return nodeCanRender;
		}
	}
	
	/** @return true if the node at this position has uploaded its render data */
	private boolean onDesiredDetailLevel(
		@NotNull QuadNode<LodRenderSection> quadNode,
		@Nullable QuadNode<LodRenderSection> parentNode)
	{
		// Skip sections that are out-of-bounds.
		// If not done some sections will appear and/or generate 
		// outside the desired render distance
		if (!this.isSectionPosInBounds(quadNode.sectionPos))
		{
			return true;
		}
		
		// retrieve this section's data even if it can't render yet or stays hidden below,
		// otherwise a parent drawn in its place would never be replaced
		this.tickNodeHolder.addExpectedDetailNode(quadNode);
		if (!this.prunedGenerationPosSet.isEmpty()
			&& this.prunedGenerationPosSet.remove(quadNode.sectionPos)
			&& quadNode.value != null)
		{
			// this section's generation was dropped while the tree didn't want it,
			// look for its missing positions again
			quadNode.value.queuedMissingSectionsForRetrieval = false;
		}
		
		LodRenderSection renderSection = quadNode.value;
		boolean canRender = (renderSection != null && renderSection.canRender());
		if (canRender)
		{
			if (this.tickNodeHolder.getEnabledNodes().contains(parentNode))
			{
				this.tickNodeHolder.addDisableNode(quadNode);
				return false;
			}
			
			if (renderSection.canRenderWholeArea())
			{
				this.tickNodeHolder.addEnableDeleteChildrenNode(quadNode);
				return true;
			}
		}
		
		// This section isn't loaded or generated yet (or only partly).
		// When moving away from an area its finer children are often still loaded,
		// keep drawing them until this section can replace them.
		if (this.tryRenderChildrenInstead(quadNode))
		{
			this.tickNodeHolder.addDisableNode(quadNode);
			return true;
		}
		
		if (canRender)
		{
			// Draw what this section has, but report it as incomplete
			// so a parent that can draw the whole area is used instead.
			this.tickNodeHolder.addEnableDeleteChildrenNode(quadNode);
		}
		else
		{
			this.tickNodeHolder.addDisableNode(quadNode);
		}
		return false;
	}
	
	/**
	 * Renders a node's direct children in its place.
	 * @return false (and changes nothing) unless every child can draw its whole area
	 */
	private boolean tryRenderChildrenInstead(@NotNull QuadNode<LodRenderSection> quadNode)
	{
		for (int i = 0; i < 4; i++)
		{
			QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
			if (childNode == null
				|| childNode.value == null
				|| !childNode.value.canRenderWholeArea())
			{
				return false;
			}
		}
		
		for (int i = 0; i < 4; i++)
		{
			QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
			this.tickNodeHolder.addEnableNode(childNode);
			this.recursivelyDisableChildNodes(childNode);
		}
		return true;
	}
	
	
	@NotNull
	private QuadNode<LodRenderSection> tryAddNodeToTree(
		@NotNull QuadNode<LodRenderSection> rootNode,
		@Nullable QuadNode<LodRenderSection> quadNode,
		long sectionPos // section pos is needed here since the quad node may be null
		)
	{
		// create the node
		if (quadNode == null)
		{
			rootNode.setValue(sectionPos, new LodRenderSection(sectionPos, this, this.level, this.fullDataSourceProvider));
			quadNode = rootNode.getNode(sectionPos);
		}
		if (quadNode == null)
		{
			LodUtil.assertNotReach("Unable to add node with pos ["+DhSectionPos.toString(sectionPos)+"] to tree root ["+rootNode+"].");
		}
		
		return quadNode;
	}
	
	private void recursivelyDisableChildNodes(@NotNull QuadNode<LodRenderSection> quadNode)
	{
		for (int i = 0; i < 4; i++)
		{
			QuadNode<LodRenderSection> childNode = quadNode.getChildByIndex(i);
			this.tickNodeHolder.removeEnableAndDisableNode(childNode);
			
			if (childNode != null)
			{
				this.recursivelyDisableChildNodes(childNode);
			}
		}
	}
	
	//endregion
	
	//=====================//
	// tick - work queuing //
	//=====================//
	//region
	
	/**
	 * How many render loads may wait in the render loader's queue. <br>
	 * The rest wait here, so each tick can hand out the most important ones first.
	 * Otherwise a backlog of reloads (data keeps changing while the player flies over newly generated areas)
	 * holds up the LODs in front of the player behind distant ones,
	 * and keeps world gen from being queued at all (see {@link #threadPoolCanAcceptWorldGenTasks()}).
	 */
	private static int getMaxQueuedRenderLoadCount() { return Config.Common.MultiThreading.numberOfThreads.get() * 4; }
	
	/**
	 * Starts loads (sections that haven't rendered yet) and reloads (sections whose data changed),
	 * nearest first: first for sections that are drawn or wanted at their own detail level,
	 * then for sections only kept to stand in for their children.
	 */
	private void queueRenderLoads(DhBlockPos2D playerPos, HashSet<LodRenderSection> nodesNeedingLoading)
	{
		PriorityTaskPicker.Executor renderLoadExecutor = ThreadPoolUtil.getRenderLoadingExecutor();
		int remainingLoadCount = (renderLoadExecutor != null) ? getMaxQueuedRenderLoadCount() - renderLoadExecutor.getQueueSize() : 0;
		
		ArrayList<LodRenderSection> wantedList = new ArrayList<>();
		ArrayList<LodRenderSection> standInList = new ArrayList<>();
		HashSet<Long> positionsToRequeue = new HashSet<>();
		
		// reloading is for sections that have been loaded once already
		HashSet<Long> reloadPositions = new HashSet<>();
		Long pos;
		while ((pos = this.sectionsToReload.poll()) != null)
		{
			if (!reloadPositions.add(pos))
			{
				continue;
			}
			
			LodRenderSection renderSection = this.tryGetValue(pos);
			// the section only needs to be updated if a buffer is currently present
			if (renderSection == null
				|| !renderSection.gpuUploadComplete())
			{
				continue;
			}
			
			if (renderSection.gpuUploadInProgress())
			{
				// if a section is already loading
				// we need to wait to trigger it again
				// if we don't trigger it again the LOD will be out of date
				// and may be invisible/missing
				positionsToRequeue.add(pos);
			}
			else
			{
				(this.isWantedAtOwnDetailLevel(playerPos, renderSection) ? wantedList : standInList).add(renderSection);
			}
		}
		
		// loading is for sections that haven't rendered yet
		for (LodRenderSection renderSection : nodesNeedingLoading)
		{
			if (!renderSection.gpuUploadInProgress()
				&& !renderSection.gpuUploadComplete())
			{
				(this.isWantedAtOwnDetailLevel(playerPos, renderSection) ? wantedList : standInList).add(renderSection);
			}
		}
			
		Comparator<LodRenderSection> nearestFirst = Comparator.comparingInt((LodRenderSection renderSection) -> DhSectionPos.getManhattanBlockDistance(renderSection.pos, playerPos));
		wantedList.sort(nearestFirst);
		standInList.sort(nearestFirst);
		
		remainingLoadCount = startRenderLoads(wantedList, remainingLoadCount, positionsToRequeue);
		startRenderLoads(standInList, remainingLoadCount, positionsToRequeue);
		
		this.sectionsToReload.addAll(positionsToRequeue);
	}
	/** @return true if the section is drawn or would be drawn at its own detail level once it can render */
	private boolean isWantedAtOwnDetailLevel(DhBlockPos2D playerPos, LodRenderSection renderSection)
	{
		if (renderSection.getRenderingEnabled())
		{
			return true;
		}
		
		byte expectedDetailLevel = this.calcExpectedDetailLevel(playerPos, renderSection.pos);
		expectedDetailLevel = (byte) Math.min(expectedDetailLevel, this.minRootRenderDetailLevel);
		expectedDetailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		return DhSectionPos.getDetailLevel(renderSection.pos) <= expectedDetailLevel;
	}
	/**
	 * Loads that aren't started are requested again by the next tick's tree walk,
	 * reloads are added to positionsToRequeue.
	 * @return how many more loads can be started
	 */
	private static int startRenderLoads(ArrayList<LodRenderSection> renderSectionList, int remainingLoadCount, HashSet<Long> positionsToRequeue)
	{
		for (int i = 0; i < renderSectionList.size(); i++)
		{
			LodRenderSection renderSection = renderSectionList.get(i);
			boolean reload = renderSection.gpuUploadComplete();
			if (remainingLoadCount > 0
				&& renderSection.uploadRenderDataToGpuAsync())
			{
				remainingLoadCount--;
			}
			else if (reload)
			{
				// not started this tick, otherwise the LOD would stay out of date
				positionsToRequeue.add(renderSection.pos);
			}
		}
		return remainingLoadCount;
	}
	
	//endregion
	//endregion tick update
	
	
	
	//=================================//
	// full data retrieval (world gen) //
	//=================================//
	//region world gen
	
	private void startQueuedRetrievalTasks(DhBlockPos2D playerPos)
	{
		// sort the nodes from nearest to farthest
		this.sortedMissingPosList.clear();
		this.sortedMissingPosList.addAll(this.missingGenerationPosSet);
		this.sortedMissingPosList.sort((posA, posB) ->
		{
			int aDist = DhSectionPos.getManhattanBlockDistance(posA, playerPos);
			int bDist = DhSectionPos.getManhattanBlockDistance(posB, playerPos);
			return Integer.compare(aDist, bDist);
		});
		
		
		
		//==================================//
		// add retrieval tasks to the queue //
		//==================================//
		
		for (int i = 0; i < this.sortedMissingPosList.size(); i++)
		{
			if (!this.fullDataSourceProvider.canQueueRetrievalNow())
			{
				break;
			}
			
			long missingPos = this.sortedMissingPosList.get(i);
			if (!this.missingGenerationPosSet.contains(missingPos))
			{
				// dropped since the list was sorted
				continue;
			}
			
			// is this position within acceptable generator range?
			boolean posInRange = WorldGenUtil.isPosInWorldGenRange(
				missingPos,
				Config.Common.WorldGenerator.generationCenterChunkX.get(), Config.Common.WorldGenerator.generationCenterChunkZ.get(),
				Config.Common.WorldGenerator.generationMaxChunkRadius.get()
			);
			if (!posInRange)
			{
				continue;
			}
			
			CompletableFuture<DataSourceRetrievalResult> genFuture = this.fullDataSourceProvider.queuePositionForRetrieval(missingPos);
			boolean positionQueued = (genFuture != null && !genFuture.isCompletedExceptionally());
			if (positionQueued)
			{
				this.queuedGenerationPosSet.add(missingPos);
				this.missingGenerationPosSet.remove(missingPos);
				
				genFuture.exceptionally((Throwable throwable) ->
				{
					// gen task failed,
					// requeue so we can try again in the future
					
					this.queuedGenerationPosSet.remove(missingPos);
					this.missingGenerationPosSet.add(missingPos);
					return null;
				});
				genFuture.thenAccept((DataSourceRetrievalResult result) ->
				{
					// task finished
					this.queuedGenerationPosSet.remove(missingPos);
					
					if (result.state == ERetrievalResultState.REQUIRES_SPLITTING)
					{
						long requestedPos = this.splitGenerationRootByPos.getOrDefault(missingPos, missingPos);
						DhSectionPos.forEachChild(missingPos, (long childPos) ->
						{
							this.splitGenerationRootByPos.put(childPos, requestedPos);
							this.tryQueuePosForRetrieval(childPos);
							if (!this.missingGenerationPosSet.contains(childPos)
								&& !this.queuedGenerationPosSet.contains(childPos))
							{
								// already generated
								this.splitGenerationRootByPos.remove(childPos);
							}
						});
					}
					this.splitGenerationRootByPos.remove(missingPos);
				});
			}
		}
		
		
		
		//==========================//
		// calc task count estimate //
		//==========================//
		
		// calculate an estimate for the max number of chunks for the queue
		long totalWorldGenChunkCount = 0;
		
		if (!this.sortedMissingPosList.isEmpty())
		{
			// count the missing LODs
			boolean highDetailRequestPresent = true;
			for (int i = 0; i < this.sortedMissingPosList.size(); i++)
			{
				long missingPos = this.sortedMissingPosList.get(i);
				
				// chunk count
				long sectionWidthInChunks = DhSectionPos.getChunkWidth(missingPos);
				totalWorldGenChunkCount += (sectionWidthInChunks * sectionWidthInChunks);
				
				// don't let any regeneration happen until
				// all low-detail LODs have finished generating
				if (DhSectionPos.getDetailLevel(missingPos) == DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
				{
					highDetailRequestPresent = false;
				}
			}
			
			this.fullDataSourceProvider.setCanRegenerate(!highDetailRequestPresent);
		}
		else
		{
			// count the LODs that need re-generating
			
			if (WorldGenUtil.regenAllowed(this.fullDataSourceProvider))
			{
				// only query the DB every few seconds
				// to prevent constant DB reads (we only need this as an estimate
				// and it isn't likely to change very often/quickly)
				long timeSinceLastQuery = System.currentTimeMillis() - this.lastRegenTaskCountQueryMs;
				if (timeSinceLastQuery > 10_000L)
				{
					int maxRegenDistanceInBlocks = WorldGenUtil.getMaxRegenDistanceInBlocks();
					totalWorldGenChunkCount = this.fullDataSourceProvider.repo.getRegenChunkCount(playerPos.x, playerPos.z, maxRegenDistanceInBlocks);
					this.cachedRegenTaskCount = totalWorldGenChunkCount;
					
					this.lastRegenTaskCountQueryMs = System.currentTimeMillis();
				}
				else
				{
					totalWorldGenChunkCount = this.cachedRegenTaskCount;
				}
			}
			
			this.fullDataSourceProvider.setCanRegenerate(true);
		}
		
		this.fullDataSourceProvider.setEstimatedRemainingRetrievalChunkCount(totalWorldGenChunkCount);
	}
	
	@Override
	public void onConfigValueSet()
	{
		// the generator plan was changed,
		// clear the queues to make sure we get the
		// correct sized generator tasks
		this.missingGenerationPosSet.clear();
		this.queuedGenerationPosSet.clear();
		this.prunedGenerationPosSet.clear();
		this.splitGenerationRootByPos.clear();
		
		// don't allow regeneration until the next world gen tick
		// this is done to prevent queuing chunks if there are still
		// lower-detail LODs that need generating
		this.fullDataSourceProvider.setCanRegenerate(false);
		
		
		boolean generatorEnabled = this.fullDataSourceProvider.getGeneratorPlan().generationEnabled;
		if (generatorEnabled)
		{
			// world gen tasks will need to be re-queued
			// since all the render sections will already have been loaded
			this.requeueAllRetrievalTasksRef.set(true);
		}
		else
		{
			this.requeueAllRetrievalTasksRef.set(false);
		}
	}
	
	/** Does nothing if the missing positions are already queued. */
	private void tryQueuePosForRetrieval(long pos)
	{
		byte worldGenDetail = DhSectionPos.getDetailLevel(pos);
		LongArrayList missingPosList = this.fullDataSourceProvider.getPositionsToRetrieve(pos, worldGenDetail);
		if (missingPosList == null)
		{
			return;
		}
		
		for (int i = 0; i < missingPosList.size(); i++)
		{
			long missingPos = missingPosList.getLong(i);
			if (!this.queuedGenerationPosSet.contains(missingPos))
			{
				this.missingGenerationPosSet.add(missingPos);
			}
		}
	}
	
	/**
	 * Drops generation positions that are finer than the tree currently wants at their location,
	 * IE after a camera zoom ended or the player moved away. <br>
	 * Missing positions used to stay queued until they were generated, so a brief zoom
	 * could leave thousands of full detail LODs to generate that nothing would render.
	 */
	private void pruneUnwantedGenerationPositions(DhBlockPos2D playerPos)
	{
		long nowNanos = System.nanoTime();
		if (nowNanos - this.lastGenerationPruneNanos < GENERATION_PRUNE_INTERVAL_NANOS)
		{
			return;
		}
		this.lastGenerationPruneNanos = nowNanos;
		
		if (CameraZoom.INSTANCE.magnification > CameraZoom.NOT_ZOOMED_MAGNIFICATION)
		{
			// zoomed in sections are wanted finer than their distance alone asks for,
			// they're dropped once the zoom ends
			return;
		}
		
		// cancelled tasks are put back in the missing set (see startQueuedRetrievalTasks()),
		// so they need to be cancelled first
		this.fullDataSourceProvider.removeRetrievalRequestIf((long genPos) -> !this.isGenerationPosWanted(playerPos, genPos));
		
		this.missingGenerationPosSet.removeIf((Long genPos) ->
		{
			if (this.isGenerationPosWanted(playerPos, genPos))
			{
				return false;
			}
			
			this.prunedGenerationPosSet.add(this.splitGenerationRootByPos.getOrDefault(genPos, genPos));
			this.splitGenerationRootByPos.remove(genPos);
			return true;
		});
	}
	/** @return false if the tree wouldn't render a node this fine at the position */
	private boolean isGenerationPosWanted(DhBlockPos2D playerPos, long genPos)
	{
		// split positions are judged by the position the tree asked for
		long requestedPos = this.splitGenerationRootByPos.getOrDefault(genPos, genPos);
		byte detailLevel = DhSectionPos.getDetailLevel(requestedPos);
		
		// nodes render at their expected detail level or one finer (see recursivelyUpdateRenderSectionNode()),
		if (detailLevel < this.calcExpectedSectionDetailLevel(playerPos, requestedPos) - 1)
		{
			return false;
		}
		// but the tree only walks down to a node when its parent's detail is too low for the parent's own position
		return detailLevel >= this.treeRootDetailLevel
			|| detailLevel + 1 > this.calcExpectedSectionDetailLevel(playerPos, DhSectionPos.getParentPos(requestedPos));
	}
	
	/**
	 * Prevents DH from
	 * accidentally starting to queue chunks out of order
	 * if the thread pool suddenly become (un)available while we're walking
	 * through the missing positions.
	 */
	private static boolean threadPoolCanAcceptWorldGenTasks()
	{
		// don't queue additional world gen requests if the render loader is busy
		PriorityTaskPicker.Executor renderLoadExecutor = ThreadPoolUtil.getRenderLoadingExecutor();
		if (renderLoadExecutor == null
			|| renderLoadExecutor.getQueueSize() >= FullDataUpdatePropagatorV2.getMaxPropagateTaskCount() / 2)
		{
			return false;
		}
		
		// don't queue additional world gen requests if the file handler is busy
		PriorityTaskPicker.Executor fileHandlerExecutor = ThreadPoolUtil.getFileHandlerExecutor();
		if (fileHandlerExecutor == null
			|| fileHandlerExecutor.getQueueSize() >= FullDataUpdatePropagatorV2.getMaxPropagateTaskCount() / 2)
		{
			return false;
		}
		
		return true;
	}
	
	//endregion world gen
	
	
	
	//=================//
	// beacon handling //
	//=================//
	//region beacon handling
	
	/** gets the active beacon list and stops/starts beacon rendering as necessary */
	private void tryRefreshRenderingBeaconsAsync(DhBlockPos2D playerPos)
	{
		// do nothing if beacon rendering or repos are unavailable
		if (this.beaconBeamRepo == null
			|| this.beaconRenderHandler == null)
		{
			return;
		}
		
		AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null)
		{
			// shouldn't normally happen, but just in case
			return;
		}
		
		if (!this.beaconUpdateFuture.isDone())
		{
			return;
		}
		CompletableFuture<Void> future = new CompletableFuture<>();
		this.beaconUpdateFuture = future;
		
		try
		{
			executor.execute(() ->
			{
				this.refreshRenderingBeacons(playerPos);
				
				try { Thread.sleep(2_000); } catch (InterruptedException ignore) { }
				future.complete(null);
			});
		}
		catch (RejectedExecutionException e)
		{
			// the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back
			future.completeExceptionally(e);
		}
	}
	private void refreshRenderingBeacons(DhBlockPos2D playerPos)
	{
		if (this.beaconBeamRepo == null
			|| this.beaconRenderHandler == null)
		{
			return;
		}
		
		try
		{
			// Synchronized to prevent two threads for accessing the array at once
			synchronized (this.beaconList)
			{
				// get beacons //
				
				int blockDistanceRadius = (this.blockRenderDistanceDiameter / 2);
				int minBlockPosX = playerPos.x - blockDistanceRadius;
				int minBlockPosZ = playerPos.z - blockDistanceRadius;
				int maxBlockPosX = playerPos.x + blockDistanceRadius;
				int maxBlockPosZ = playerPos.z + blockDistanceRadius;
				
				ArrayList<BeaconBeamDTO> dbBeacons = this.beaconBeamRepo.getAllBeamsInBlockPosRange(
					minBlockPosX, maxBlockPosX,
					minBlockPosZ, maxBlockPosZ
				);
				
				
				// convert DB beacons //
				
				ArrayList<BeaconRenderHandler.BeaconBeamWithWidth> newBeaconList = new ArrayList<>(this.beaconList.size());
				for (BeaconBeamDTO beaconBeam : dbBeacons)
				{
					byte beaconDetailLevel = this.calcExpectedDetailLevel(playerPos, beaconBeam.blockPos.getX(), beaconBeam.blockPos.getZ());
					newBeaconList.add(new BeaconRenderHandler.BeaconBeamWithWidth(beaconBeam, beaconDetailLevel));
				}
				
				
				boolean replaceBeacons = false;
				if (this.beaconList.size() != newBeaconList.size())
				{
					// lists are different sizes
					replaceBeacons = true;
				}
				else
				{
					// sort the beacons so they can be compared
					this.beaconList.sort(BeaconRenderHandler.NegativeInfiniteBlockPosComparator.INSTANCE);
					newBeaconList.sort(BeaconRenderHandler.NegativeInfiniteBlockPosComparator.INSTANCE);
					
					for (int i = 0; i < this.beaconList.size(); i++)
					{
						BeaconRenderHandler.BeaconBeamWithWidth oldBeam = this.beaconList.get(i);
						BeaconRenderHandler.BeaconBeamWithWidth newBeam = newBeaconList.get(i);
						if (!oldBeam.equals(newBeam))
						{
							replaceBeacons = true;
							break;
						}
					}
				}
				
				
				// only replace the beacons if something changed
				// this is done to prevent constantly re-uploading the render data
				if (replaceBeacons)
				{
					this.beaconList.clear();
					this.beaconList.addAll(newBeaconList);
					this.beaconRenderHandler.replaceRenderingBeacons(this.beaconList);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected issue updating beacons, error: ["+e.getMessage()+"].", e);
		}
	}
	
	//endregion beacon handling
	
	
	
	//====================//
	// detail level logic //
	//====================//
	//region detail level logic
	
	/** @return the section detail level the tree renders at the given position, the same units as {@link DhSectionPos#getDetailLevel(long)} */
	private byte calcExpectedSectionDetailLevel(DhBlockPos2D playerPos, long sectionPos)
	{
		byte expectedDetailLevel = this.calcExpectedDetailLevel(playerPos, sectionPos);
		expectedDetailLevel = (byte) Math.min(expectedDetailLevel, this.minRootRenderDetailLevel);
		expectedDetailLevel += DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		return expectedDetailLevel;
	}
	
	/**
	 * Determines the detail level expected for the given position based on player position.
	 *
	 * @param playerPos player position as a reference for calculating the detail level
	 * @param sectionPos section position
	 * @return detail level of this section pos
	 */
	public byte calcExpectedDetailLevel(DhBlockPos2D playerPos, long sectionPos)
	{
		// the radius (half diagonal) is needed so the zoom cone check doesn't
		// miss sections that only partially overlap the camera's view
		double sectionBlockRadius = DhSectionPos.getBlockWidth(sectionPos) * (Math.sqrt(2.0) / 2.0);
		return this.calcExpectedDetailLevel(playerPos, sectionPos, DhSectionPos.getCenterBlockPosX(sectionPos), DhSectionPos.getCenterBlockPosZ(sectionPos), sectionBlockRadius);
	}
	
	public byte calcExpectedDetailLevel(DhBlockPos2D playerPos, int targetBlockPosX, int targetBlockPosZ)
	{ return this.calcExpectedDetailLevel(playerPos, DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, new DhBlockPos(targetBlockPosX, 0, targetBlockPosZ)), targetBlockPosX, targetBlockPosZ, 0.0); }
	
	private byte calcExpectedDetailLevel(DhBlockPos2D playerPos, long targetSectionPos, int targetBlockPosX, int targetBlockPosZ, double targetBlockRadius)
	{
		double blockDistance = playerPos.dist(targetBlockPosX, targetBlockPosZ);
		
		// Don't allow zooming for LODs that will be missing child nodes.
		// Doing so will cause holes/missing LODs.
		boolean allowZooming = DhSectionPos.getDetailLevel(targetSectionPos) < FullDataUpdatePropagatorV2.HIGHEST_DOWNSAMPLE_DETAIL_LEVEL;
		if (allowZooming)
		{
			// LODs visible through a zoomed in camera appear closer than they actually are,
			// using the magnified distance gives them the detail they'd have if the player walked up to them
			if (CameraZoom.INSTANCE.magnification > CameraZoom.NOT_ZOOMED_MAGNIFICATION
				&& CameraZoom.INSTANCE.coneIntersectsCircle(playerPos.x, playerPos.z, targetBlockPosX, targetBlockPosZ, targetBlockRadius))
			{
				blockDistance /= CameraZoom.INSTANCE.magnification;
				
				EDhApiMaxHorizontalResolution maxHorizontalResolution = Config.Client.Advanced.Graphics.Quality.maxHorizontalResolution.get();
				return this.calcDetailLevelFromDistance(blockDistance, maxHorizontalResolution.detailLevel);
			}
		}
		
		return this.calcDetailLevelFromDistance(blockDistance);
	}
	
	private void updateDetailLevelVariables()
	{
		// Note: these detail levels are all absolute, not section.
		// Section detail levels should be subtracted by "DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL".
		
		this.detailDropOffDistanceUnit = Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().distanceUnitInBlocks * LodUtil.CHUNK_WIDTH;
		this.detailDropOffLogBase = Math.log(Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().quadraticBase);
		
		this.maxLeafRenderDetailLevel = Config.Client.Advanced.Graphics.Quality.maxHorizontalResolution.get().detailLevel;
		
		// The minimum detail level is done to prevent single corner sections rendering 1 detail level lower than the others.
		// If not done corners may not be flush with the other LODs, which looks bad.
		byte minSectionDetailLevel = this.calcDetailLevelFromDistance(this.blockRenderDistanceDiameter); // get the minimum allowed detail level
		minSectionDetailLevel -= 1; // -1 so corners can't render lower than their adjacent neighbors. space
		minSectionDetailLevel = (byte) Math.min(minSectionDetailLevel, this.treeRootDetailLevel); // don't allow rendering lower detail sections than what the tree contains
		minSectionDetailLevel = (byte) Math.min(
			minSectionDetailLevel, 
			FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL - DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL); // the tree can only provide LODs as low as the provider has
		this.minRootRenderDetailLevel = (byte) Math.max(minSectionDetailLevel, this.maxLeafRenderDetailLevel); // respect the user's selected max resolution if it is lower detail (IE they want 2x2 block, but minSectionDetailLevel is specifically for 1x1 block render resolution)
	}
	
	private byte calcDetailLevelFromDistance(double blockDistance)
	{ return this.calcDetailLevelFromDistance(blockDistance, this.maxLeafRenderDetailLevel); }
	private byte calcDetailLevelFromDistance(double blockDistance, byte maxDetailLevel)
	{
		int detailLevel = (int) (Math.log(blockDistance / this.detailDropOffDistanceUnit) / this.detailDropOffLogBase);
		return (byte) MathUtil.clamp(maxDetailLevel, detailLevel, FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL);
	}
	
	//endregion detail level logic
	
	
	
	//==========================//
	// external render requests //
	//==========================//
	//region external render requests
	
	/**
	 * Re-creates the color, render data.
	 * This method should be called after resource packs are changed or LOD settings are modified.
	 */
	public void clearRenderDataCache()
	{
		try
		{
			this.treeTickLock.lock();
			LOGGER.info("Disposing render data...");
			
			// clear the tree
			Iterator<QuadNode<LodRenderSection>> nodeIterator = this.nodeIterator();
			while (nodeIterator.hasNext())
			{
				QuadNode<LodRenderSection> quadNode = nodeIterator.next();
				if (quadNode.value != null)
				{
					quadNode.value.close();
					quadNode.value = null;
				}
			}
			
			LOGGER.info("Render data cleared, please wait a moment for everything to reload...");
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error when clearing LodQuadTree render cache: " + e.getMessage(), e);
		}
		finally
		{
			this.treeTickLock.unlock();
		}
	}
	
	/**
	 * Can be called whenever a render section's data needs to be refreshed. <br>
	 * This should be called whenever a world generation task is completed or if the connected server has new data to show.
	 */
	public void queuePosToReload(long pos)
	{		
		// queue reloads //
		
		// only queue each section for reloading
		// after the cache has been cleared,
		// this is done to prevent accidentally using old cached data
		
		this.sectionsToReload.add(pos);
		
		// the adjacent locations also need to be updated to make sure lighting
		// and water updates correctly, otherwise oceans may have walls
		// and lights may not show up over LOD borders
		for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
		{
			long adjacentPos = DhSectionPos.getAdjacentPos(pos, direction);
			this.sectionsToReload.add(adjacentPos);
		}
	}
	
	//endregion external render requests
	
	
	
	//===========//
	// debugging //
	//===========//
	//region debugging
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer debugRenderer)
	{
		this.populateListWithEnabledRenderSections(this.debugNodeList);
		
		for (int i = 0; i < this.debugNodeList.size(); i++)
		{
			LodRenderSection renderSection = this.debugNodeList.get(i);
			
			Color color = Color.BLACK;
			if (renderSection.gpuUploadInProgress())
			{
				color = Color.ORANGE;
			}
			else if (!renderSection.canRender())
			{
				// uploaded but the buffer is missing
				color = Color.PINK;
			}
			else if (renderSection.renderBufferContainer.hasNonNullVbos())
			{
				if (renderSection.renderBufferContainer.vboBufferCount() != 0)
				{
					color = Color.GREEN;
				}
				else
				{
					// This section is probably rendering an empty chunk
					color = Color.RED;
				}
			}
			
			
			int levelMinY = this.level.getLevelWrapper().getMinHeight();
			int levelMaxY = this.level.getLevelWrapper().getMaxHeight();
			// show the wireframe a bit lower than world max height,
			// since most worlds don't render all the way up to the max height
			int levelHeightRange = (levelMaxY - levelMinY);
			int maxY = levelMaxY - (levelHeightRange / 2);
			
			debugRenderer.renderBox(new AbstractDebugWireframeRenderer.Box(renderSection.pos, levelMinY, maxY, 0.05f, color));
		}
	}
	
	//endregion debugging
	
	
	
	//==============//
	// base methods //
	//==============//
	//region base methods
	
	@Override
	public void close()
	{
		//LOGGER.info("Shutting down LodQuadTree...");
		
		DEBUG_RENDERER.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showQuadTreeRenderStatus);
		Config.Common.WorldGenerator.generatorPlan.removeListener(this);
		
		this.fullDataRetrievalQueueThread.shutdownNow();
		
		
		ThreadPoolExecutor mainCleanupExecutor = ThreadPoolUtil.getCleanupExecutor();
		// closing every node may take a few moments
		// so this is run on a separate thread to prevent lagging the render thread
		mainCleanupExecutor.execute(() -> 
		{
			this.treeTickLock.lock();
			try
			{
				// walk through each node
				Iterator<QuadNode<LodRenderSection>> nodeIterator = this.nodeIterator();
				while (nodeIterator.hasNext())
				{
					QuadNode<LodRenderSection> quadNode = nodeIterator.next();
					LodRenderSection renderSection = quadNode.value;
					if (renderSection != null)
					{
						renderSection.close();
						quadNode.value = null;
					}
				}
			}
			finally
			{
				this.treeTickLock.unlock();
			}
		});
		
		
		//LOGGER.info("Finished shutting down LodQuadTree");
	}
	
	//endregion base methods
	
	
	
}
