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

package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDhGenericObjectRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
#else
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.vertex.VertexFormat;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericRenderSetupEvent;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.common.render.blaze.objects.BlazeGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.render.renderer.GenericRenderObjectFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.ModInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles rendering generic groups of {@link DhApiRenderableBox}.
 * 
 * @see IDhApiCustomRenderRegister
 * @see DhApiRenderableBox
 */
public class BlazeDhGenericObjectRenderer implements IDhGenericRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private static final DhApiRenderableBoxGroupShading DEFAULT_SHADING = DhApiRenderableBoxGroupShading.getUnshaded();
	
	/** 
	 * Can be used to troubleshoot the renderer. 
	 * If enabled several debug objects will render around (0,150,0). 
	 */
	public static final boolean RENDER_DEBUG_OBJECTS = false;
	
	private static final DhApiBeforeGenericObjectRenderEvent.EventParam EVENT_PARAM = new DhApiBeforeGenericObjectRenderEvent.EventParam();
	
	
	
	private final ConcurrentHashMap<Long, RenderableBoxGroup> boxGroupById = new ConcurrentHashMap<>();
	
	
	// rendering setup
	private boolean init = false;
	
	private RenderPipeline pipeline;
	
	private final BlazeUniformBufferWrapper vertUniformBufferWrapper = new BlazeUniformBufferWrapper("vertUniformBlock");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeDhGenericObjectRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.createPipelines();
		
		if (RENDER_DEBUG_OBJECTS)
		{
			this.addGenericDebugObjects();
		}
	}
	private void createPipelines()
	{
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(true);
			pipelineBuilder.withDepthWrite(true);
			if (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.FORWARD_Z)
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.LESS);
			}
			else
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.GREATER);
			}
			pipelineBuilder.withBlend(BlendFunction.TRANSLUCENT); // TRANSLUCENT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("generic_objects");
			
			pipelineBuilder.withVertexShader("generic/blaze/vert");
			pipelineBuilder.withFragmentShader("generic/blaze/frag");
			
			pipelineBuilder.withSampler("uLightMap");
			
			pipelineBuilder.withUniformBuffer("vertUniformBlock");
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.FLOAT_XYZ_POS)
				.add("aColor", BlazeDhVertexFormatUtil.RGBA_UBYTE_COLOR)
				.add("aMaterial", BlazeDhVertexFormatUtil.IRIS_MATERIAL)
				
				.add("paddingOne", BlazeDhVertexFormatUtil.BYTE_PAD)
				.add("paddingTwo", BlazeDhVertexFormatUtil.BYTE_PAD)
				.add("paddingThree", BlazeDhVertexFormatUtil.BYTE_PAD)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLES);
		}
		this.pipeline = pipelineBuilder.build();
	}
	private void addGenericDebugObjects()
	{
		GenericRenderObjectFactory factory = GenericRenderObjectFactory.INSTANCE;
		
		
		// single giant box
		IDhApiRenderableBoxGroup singleGiantBoxGroup = factory.createForSingleBox(
				ModInfo.NAME + ":CyanChunkBox",
				new DhApiRenderableBox(
						new DhApiVec3d(0,0,0), new DhApiVec3d(16,190,16),
						new Color(Color.CYAN.getRed(), Color.CYAN.getGreen(), Color.CYAN.getBlue(), 125),
						EDhApiBlockMaterial.WATER)
		);
		singleGiantBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		singleGiantBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.add(singleGiantBoxGroup);


		// single slender box
		IDhApiRenderableBoxGroup singleTallBoxGroup = factory.createForSingleBox(
				ModInfo.NAME + ":GreenBeacon",
				new DhApiRenderableBox(
						new DhApiVec3d(16,0,31), new DhApiVec3d(17,2000,32),
						new Color(Color.GREEN.getRed(), Color.GREEN.getGreen(), Color.GREEN.getBlue(), 125),
						EDhApiBlockMaterial.ILLUMINATED)
		);
		singleTallBoxGroup.setSkyLight(LodUtil.MAX_MC_LIGHT);
		singleTallBoxGroup.setBlockLight(LodUtil.MAX_MC_LIGHT);
		this.add(singleTallBoxGroup);


		// absolute box group
		ArrayList<DhApiRenderableBox> absBoxList = new ArrayList<>();
		for (int i = 0; i < 18; i++)
		{
			absBoxList.add(new DhApiRenderableBox(
					new DhApiVec3d(i,150+i,24), new DhApiVec3d(1+i,151+i,25),
					new Color(Color.ORANGE.getRed(), Color.ORANGE.getGreen(), Color.ORANGE.getBlue()),
					EDhApiBlockMaterial.LAVA
				)
			);
		}
		IDhApiRenderableBoxGroup absolutePosBoxGroup = factory.createAbsolutePositionedGroup(ModInfo.NAME + ":OrangeStairs", absBoxList);
		this.add(absolutePosBoxGroup);


		// relative box group
		ArrayList<DhApiRenderableBox> relBoxList = new ArrayList<>();
		for (int i = 0; i < 8; i+=2)
		{
			relBoxList.add(new DhApiRenderableBox(
					new DhApiVec3d(0,i,0), new DhApiVec3d(1,1+i,1),
					new Color(Color.MAGENTA.getRed(), Color.MAGENTA.getGreen(), Color.MAGENTA.getBlue()),
					EDhApiBlockMaterial.METAL
				)
			);
		}
		IDhApiRenderableBoxGroup relativePosBoxGroup = factory.createRelativePositionedGroup(
				ModInfo.NAME + ":MovingMagentaGroup",
				new DhApiVec3d(24, 140, 24),
				relBoxList);
		relativePosBoxGroup.setPreRenderFunc((event) ->
		{
			DhApiVec3d pos = relativePosBoxGroup.getOriginBlockPos();
			pos.x += event.partialTicks / 2;
			pos.x %= 32;
			relativePosBoxGroup.setOriginBlockPos(pos);
		});
		this.add(relativePosBoxGroup);


		// massive relative box group
		ArrayList<DhApiRenderableBox> massRelBoxList = new ArrayList<>();
		for (int x = 0; x < 50*2; x+=2)
		{
			for (int z = 0; z < 50*2; z+=2)
			{
				massRelBoxList.add(new DhApiRenderableBox(
						new DhApiVec3d(-x, 0, -z), new DhApiVec3d(1-x, 1, 1-z),
						new Color(Color.RED.getRed(), Color.RED.getGreen(), Color.RED.getBlue()),
						EDhApiBlockMaterial.TERRACOTTA
					)
				);
			}
		}
		IDhApiRenderableBoxGroup massRelativePosBoxGroup = factory.createRelativePositionedGroup(
				ModInfo.NAME + ":MassRedGroup",
				new DhApiVec3d(-25, 140, 0),
				massRelBoxList);
		massRelativePosBoxGroup.setPreRenderFunc((event) ->
		{
			DhApiVec3d blockPos = massRelativePosBoxGroup.getOriginBlockPos();
			blockPos.y += event.partialTicks / 4;
			if (blockPos.y > 150f)
			{
				blockPos.y = 140f;

				Color newColor = (massRelativePosBoxGroup.get(0).color == Color.RED) ? Color.RED.darker() : Color.RED;
				massRelativePosBoxGroup.forEach((box) -> { box.color = newColor; });
				massRelativePosBoxGroup.triggerBoxChange();
			}

			massRelativePosBoxGroup.setOriginBlockPos(blockPos);
		});
		this.add(massRelativePosBoxGroup);
	}
	
	//endregion
	
	
	
	//==============//
	// registration //
	//==============//
	//region
	
	@Override
	public void add(IDhApiRenderableBoxGroup iBoxGroup) throws IllegalArgumentException 
	{
		if (!(iBoxGroup instanceof RenderableBoxGroup))
		{
			throw new IllegalArgumentException("Box group must be of type ["+ RenderableBoxGroup.class.getSimpleName()+"], type received: ["+(iBoxGroup != null ? iBoxGroup.getClass() : "NULL")+"].");
		}
		RenderableBoxGroup boxGroup = (RenderableBoxGroup) iBoxGroup;
		if (boxGroup.size() != 0)
		{
			// trigger a box change to make sure the initial data is uploaded
			boxGroup.triggerBoxChange();
		}
		
		long id = boxGroup.getId();
		if (this.boxGroupById.containsKey(id))
		{
			throw new IllegalArgumentException("A box group with the ID [" + id + "] is already present.");
		}
		
		this.boxGroupById.put(id, boxGroup);
	}
	
	@Override
	public IDhApiRenderableBoxGroup remove(long id) { return this.boxGroupById.remove(id); }
	
	public void clear() { this.boxGroupById.clear(); }
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	/**
	 * @param renderingWithSsao 
	 *      if true that means this render call is happening before the SSAO pass
     *      and any objects rendered in this pass will have SSAO applied to them.
	 */
	@Override
	public void render(RenderParams renderEventParam, IProfilerWrapper profiler, boolean renderingWithSsao)
	{
		
		try (IProfilerWrapper.IProfileBlock generic_profile = profiler.push("setup"))
		{
			
			
			//==============//
			// render setup //
			//==============//
			//#region
			
			this.init();
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeGenericRenderSetupEvent.class, renderEventParam.apiCopy);
			
			DhVec3d camPos = MC_RENDER.getCameraExactPosition();
			
			//#endregion
			
			if (BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.isEmpty()
				|| BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.isEmpty())
			{
				return;
			}
			
			
			
			//===========//
			// rendering //
			//===========//
			//#region
			
			Collection<RenderableBoxGroup> boxList = this.boxGroupById.values();
			for (RenderableBoxGroup boxGroup : boxList)
			{
				// validation //
				
				// shouldn't happen, but just in case
				if (boxGroup == null)
				{
					continue;
				}
				
				// skip boxes that shouldn't render this pass
				if (boxGroup.ssaoEnabled != renderingWithSsao)
				{
					continue;
				}
				
				profiler.popPush("render prep");
				boxGroup.preRender(renderEventParam); // called even if the group is inactive, so the group can be activate if desired
				
				// ignore inactive groups
				if (!boxGroup.active)
				{
					continue;
				}
				
				// allow API users to cancel this object's rendering
				EVENT_PARAM.update(renderEventParam, boxGroup);
				boolean cancelRendering = ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeGenericObjectRenderEvent.class, EVENT_PARAM);
				if (cancelRendering)
				{
					continue;
				}
				
				// update instanced data if needed
				{
					boxGroup.tryUpdateInstancedDataAsync();
					
					// skip groups that haven't been uploaded yet
					if (boxGroup.vertexBufferContainer.getState() != IDhGenericObjectVertexBufferContainer.EState.RENDER)
					{
						continue;
					}
				}
				
				
				DhApiRenderableBoxGroupShading shading = boxGroup.shading;
				if (shading == null)
				{
					shading = DEFAULT_SHADING;
				}
				
				// uniforms
				{
					// create data //
					
					DhMat4f projectionMvmMatrix = new DhMat4f(renderEventParam.dhProjectionMatrix);
					projectionMvmMatrix.multiply(renderEventParam.dhModelViewMatrix);
					
					
					// upload data //
					
					this.vertUniformBufferWrapper
						.putVec3i(
							LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().x),
							LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().y),
							LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().z)
						) // uOffsetChunk
						.putVec3f(
							LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().x),
							LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().y),
							LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().z)
						) // uOffsetSubChunk
						.putVec3i(
							LodUtil.getChunkPosFromDouble(camPos.x),
							LodUtil.getChunkPosFromDouble(camPos.y),
							LodUtil.getChunkPosFromDouble(camPos.z)
						) // uCameraPosChunk
						.putVec3f(
							LodUtil.getSubChunkPosFromDouble(camPos.x),
							LodUtil.getSubChunkPosFromDouble(camPos.y),
							LodUtil.getSubChunkPosFromDouble(camPos.z)
						) // uCameraPosSubChunk
						
						.putMat4f(projectionMvmMatrix) // uProjectionMvm
						.putInt(boxGroup.getSkyLight()) // uSkyLight
						.putInt(boxGroup.getBlockLight()) // uBlockLight
						
						.putFloat(shading.north)
						.putFloat(shading.south)
						.putFloat(shading.east)
						.putFloat(shading.west)
						.putFloat(shading.top)
						.putFloat(shading.bottom)
						
						.finishAndUpload()
					;
				}
				
				
				
				
				// render //
				
				profiler.popPush("rendering");
				try (IProfilerWrapper.IProfileBlock namespace_profile = profiler.push(boxGroup.getResourceLocationNamespace());
					IProfilerWrapper.IProfileBlock location_profile = profiler.push(boxGroup.getResourceLocationPath()))
				{
					this.renderBoxGroupInstanced(renderEventParam, boxGroup, profiler);
				}
				
				boxGroup.postRender(renderEventParam);
			}
			
			//#endregion
			
			
			
			//==========//
			// clean up //
			//==========//
			//region
			
			profiler.popPush("cleanup");
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeGenericRenderCleanupEvent.class, renderEventParam);
			
			//endregion
		}
	}
	private String getRenderPassName() { return "distantHorizons:GenericObjectRenderer"; }
	
	//endregion
	
	
	
	//=====================//
	// instanced rendering //
	//=====================//
	//region
	
	private void renderBoxGroupInstanced(
		RenderParams renderEventParam, 
		RenderableBoxGroup boxGroup,
		IProfilerWrapper profiler)
	{
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper,
			BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper))
		{
			
			// update instance data //
			
			BlazeGenericObjectVertexContainer container = (BlazeGenericObjectVertexContainer) boxGroup.vertexBufferContainer;
			
			LightMapWrapper lightMapWrapper = (LightMapWrapper) renderEventParam.lightmap;
			BlazeTextureViewWrapper lightmapTextureViewWrapper = lightMapWrapper.getTextureViewWrapper();
			renderPassWrapper.bindTexture("uLightMap", lightmapTextureViewWrapper);
			
			
			
			// Bind instance data //
			
			
			renderPassWrapper.setUniform("vertUniformBlock", this.vertUniformBufferWrapper);
			
			// set pipeline
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.setIndexBuffer(container.indexGpuBuffer);
			
			renderPassWrapper.setVertexBuffer(container.vboGpuBuffer);
			
			// Draw instanced
			if (container.uploadedBoxCount > 0)
			{
				// 36 = 6 faces * 6 verticies per face
				renderPassWrapper.drawIndexed(container.uploadedBoxCount * 36);
			}
		}
	}
	
	//endregion
	
	
	
	//=========//
	// F3 menu //
	//=========//
	//region
	
	public String getVboRenderDebugMenuString()
	{
		// get counts
		int totalGroupCount = this.boxGroupById.size();
		int totalBoxCount = 0;
		
		int activeGroupCount = 0;
		int activeBoxCount = 0;
		
		for (long key : this.boxGroupById.keySet())
		{
			RenderableBoxGroup renderGroup = this.boxGroupById.get(key);
			if (renderGroup.active)
			{
				activeGroupCount++;
				activeBoxCount += renderGroup.size();
			}
			totalBoxCount += renderGroup.size();
		}
		
		
		return "Generic Obj #: " + F3Screen.NUMBER_FORMAT.format(activeGroupCount) + "/" + F3Screen.NUMBER_FORMAT.format(totalGroupCount) + ", " +
				"Cube #: " + F3Screen.NUMBER_FORMAT.format(activeBoxCount) + "/" + F3Screen.NUMBER_FORMAT.format(totalBoxCount);
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		// close is called outside the render thread and buffer closing must be done on the render thread
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("Generic Obj Cleanup", () ->
		{
			if (this.vertUniformBufferWrapper != null)
			{
				this.vertUniformBufferWrapper.close();
			}
		});
	}
	
	//endregion
	
	
	
}
#endif