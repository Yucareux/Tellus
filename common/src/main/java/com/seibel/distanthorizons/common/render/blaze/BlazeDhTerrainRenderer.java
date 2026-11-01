package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDhTerrainRenderer {}

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
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeBufferRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeBlockTextureAtlas;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeLodUniformBufferWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.buffer.BlazeVertexBufferWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

/** Renders rendering DH's LOD terrain. */
public class BlazeDhTerrainRenderer implements IDhTerrainRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	public static final BlazeDhTerrainRenderer INSTANCE = new BlazeDhTerrainRenderer();
	
	private static final DhVec3f MODEL_POS = new DhVec3f();
	/** single event object used to reduce GC pressure */
	private static final DhApiBeforeBufferRenderEvent.EventParam BEFORE_BUFFER_RENDER_EVENT_PARAM = new DhApiBeforeBufferRenderEvent.EventParam();
	
	
	private RenderPipeline opaquePipeline;
	private RenderPipeline transparentPipeline;
	private boolean init = false;
	
	private final BlazeUniformBufferWrapper fragUniformBufferWrapper = new BlazeUniformBufferWrapper("fragUniformBlock");
	private final BlazeUniformBufferWrapper vertSharedUniformBufferWrapper = new BlazeUniformBufferWrapper("vertSharedUniformBlock");
	
	/** used for TAA jitter, the 8 represents how many jitter points are available */
	private int frameIndexMod8 = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhTerrainRenderer() { }
	
	private void tryInit()
	{
		if (this.init)
		{
			return;
		}
		
		
		
		RenderPipelineBuilderWrapper opaquePipelineBuilder = new RenderPipelineBuilderWrapper();
		RenderPipelineBuilderWrapper translucentPipelineBuilder = new RenderPipelineBuilderWrapper();
		
		// apply shared options to both pipelines
		for (int i = 0; i < 2; i++)
		{
			RenderPipelineBuilderWrapper pipelineBuilder = (i == 0) 
				? opaquePipelineBuilder 
				: translucentPipelineBuilder;
			
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
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			
			pipelineBuilder.withSampler("uLightMap");
			pipelineBuilder.withSampler("uBlockAtlas");
			
			pipelineBuilder.withVertexShader("terrain/blaze/vert");
			pipelineBuilder.withFragmentShader("terrain/blaze/frag");
			
			pipelineBuilder.withUniformBuffer("vertUniqueUniformBlock");
			pipelineBuilder.withUniformBuffer("vertSharedUniformBlock");
			pipelineBuilder.withUniformBuffer("fragUniformBlock");
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.SHORT_XYZ_POS)
				.add("meta", BlazeDhVertexFormatUtil.META)
				.add("vColor", BlazeDhVertexFormatUtil.RGBA_UBYTE_COLOR)
				.add("irisMaterial", BlazeDhVertexFormatUtil.IRIS_MATERIAL)
				.add("irisNormal", BlazeDhVertexFormatUtil.IRIS_NORMAL)
				.add("textureTile", BlazeDhVertexFormatUtil.TEXTURE_TILE)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLES);
		}
		
		// opaque
		{
			opaquePipelineBuilder.withName("opaque_terrain");
			opaquePipelineBuilder.withoutBlend();
			this.opaquePipeline = opaquePipelineBuilder.build();
		}
		
		// transparent
		{
			translucentPipelineBuilder.withName("transparent_terrain");
			// TRANSLUCENT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
			translucentPipelineBuilder.withBlend(BlendFunction.TRANSLUCENT);
			this.transparentPipeline = translucentPipelineBuilder.build();
		}
		
		this.init = true;
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(
		RenderParams renderEventParam, 
		boolean opaquePass,
		SortedArraySet<LodBufferContainer> bufferContainers,
		IProfilerWrapper profiler)
	{
		this.tryInit();
		
		
		
		if (Config.Client.Advanced.Graphics.enableAntiAliasing.get())
		{
			this.frameIndexMod8++;
			this.frameIndexMod8 %= 8;
		}
		else
		{
			this.frameIndexMod8 = -1;
		}
		
		try(IProfilerWrapper.IProfileBlock terrain_profile = profiler.push("terrain render"))
		{
			profiler.popPush("vert unique uniforms");
			{
				// create data //
				
				for (int lodIndex = 0; lodIndex < bufferContainers.size(); lodIndex++)
				{
					LodBufferContainer bufferContainer = bufferContainers.get(lodIndex);
					bufferContainer.uniformContainer.tryUpload(bufferContainer);
				}
			}
			
			profiler.popPush("vert share uniforms");
			{
				float earthCurveRatio = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
				if (earthCurveRatio < -1.0f || earthCurveRatio > 1.0f)
				{
					earthCurveRatio = /*6371KM*/ 6371000.0f / earthCurveRatio;
				}
				else
				{
					// disable curvature if the config value is between -1 and 1
					earthCurveRatio = 0.0f;
				}
				
				
				// upload data //
				
				int i = Config.Client.Advanced.Debugging.enableWhiteWorld.get() ? 1 : 0;
				
				this.vertSharedUniformBufferWrapper
					.putInt(i) // uIsWhiteWorld
					
					.putFloat((float) renderEventParam.worldYOffset) // uWorldYOffset
					.putFloat(0.01f) // uMircoOffset // 0.01 block offset
					
					.putFloat(earthCurveRatio) // uEarthRadius
					
					.putFloat(this.frameIndexMod8) // uFrameMod8
					.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getWidth()) // uViewWidth
					.putFloat(BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.getHeight()) // uViewHeight
					
					.putVec3f(
						(float) renderEventParam.exactCameraPosition.x,
						(float) renderEventParam.exactCameraPosition.y,
						(float) renderEventParam.exactCameraPosition.z) // uCameraPos
					.putMat4f(renderEventParam.dhMvmProjMatrix) // uCombinedMatrix
					.finishAndUpload();
			}
			
			profiler.popPush("set frag uniforms");
			{
				// create data //
				
				float dhNearClipDistance = RenderUtil.getNearClipPlaneInBlocks();
				if (!Config.Client.Advanced.Debugging.lodOnlyMode.get())
				{
					// this added value prevents the near clip plane and discard circle from touching, which looks bad
					dhNearClipDistance += 16f;
				}
				
				
				// upload data //
				this.fragUniformBufferWrapper
					.putFloat(dhNearClipDistance) // uClipDistance
					.putFloat(Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get()) // uNoiseIntensity
					.putInt(Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get()) // uNoiseSteps
					.putInt(Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get()) // uNoiseDropoff
					.putInt(Config.Client.Advanced.Graphics.Quality.ditherDhFade.get() ? 1 : 0) // uDitherDhRendering
					.putInt(Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get() ? 1 : 0) // uNoiseEnabled
					.finishAndUpload()
				;
			}
			
			
			
			profiler.popPush("block texture upload");
			BlazeBlockTextureAtlas.INSTANCE.uploadPendingTiles();
			
			// render pass setup
			{
				profiler.popPush("rendering");
				
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderPassEvent.class, renderEventParam.apiCopy);
				
				// create a render pass
				try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
					this::getRenderPassName,
					BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper,
					BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper)
				)
				{
					LightMapWrapper lightMapWrapper = (LightMapWrapper) renderEventParam.lightmap;
					BlazeTextureViewWrapper lightmapTextureViewWrapper = lightMapWrapper.getTextureViewWrapper();
					renderPassWrapper.bindTexture("uLightMap", lightmapTextureViewWrapper);
					
					BlazeTextureWrapper blockTextureAtlas = BlazeBlockTextureAtlas.INSTANCE.getTextureWrapper();
					renderPassWrapper.bindTexture("uBlockAtlas", blockTextureAtlas);
					DhApiRenderProxy.activeBlazeDhBlockRatioAtlasTextureWrapper = blockTextureAtlas;
					
					// set pipeline
					renderPassWrapper.setPipeline(opaquePass ? this.opaquePipeline : this.transparentPipeline);
					
					// shared uniforms
					renderPassWrapper.setUniform("fragUniformBlock", this.fragUniformBufferWrapper);
					renderPassWrapper.setUniform("vertSharedUniformBlock", this.vertSharedUniformBufferWrapper);
					
					
					
					for (int lodIndex = 0; lodIndex < bufferContainers.size(); lodIndex++)
					{
						LodBufferContainer bufferContainer = bufferContainers.get(lodIndex);
						BlazeLodUniformBufferWrapper uniformWrapper = (BlazeLodUniformBufferWrapper) bufferContainer.uniformContainer;
						
						boolean columnBuilderDebugEnabled = Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugEnable.get();
						if (columnBuilderDebugEnabled)
						{
							if (DhSectionPos.getDetailLevel(bufferContainer.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugDetailLevel.get()
								&& DhSectionPos.getX(bufferContainer.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugXPos.get()
								&& DhSectionPos.getZ(bufferContainer.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugZPos.get())
							{
								int breakpoint = 0;
							}
							else
							{
								continue;
							}
						}
						
						renderPassWrapper.setUniform("vertUniqueUniformBlock", uniformWrapper);
						
						
						
						// render each buffer
						IVertexBufferWrapper[] bufferWrapperList = opaquePass ? bufferContainer.vboOpaqueWrappers : bufferContainer.vboTransparentWrappers;
						for (int i = 0; i < bufferWrapperList.length; i++)
						{
							BlazeVertexBufferWrapper bufferWrapper = (BlazeVertexBufferWrapper) bufferWrapperList[i];
							if (bufferWrapper == null // not sure how a buffer could be null here, but this did happen at least once 
								|| !bufferWrapper.uploaded
								|| bufferWrapper.vertexCount == 0)
							{
								continue;
							}
							
							// fire render event
							{
								DhVec3d camPos = renderEventParam.exactCameraPosition;
								MODEL_POS.set(
									(float) (bufferContainer.minCornerBlockPos.getX() - camPos.x),
									(float) (bufferContainer.minCornerBlockPos.getY() - camPos.y),
									(float) (bufferContainer.minCornerBlockPos.getZ() - camPos.z));
								BEFORE_BUFFER_RENDER_EVENT_PARAM.update(renderEventParam, MODEL_POS);
								ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeBufferRenderEvent.class, BEFORE_BUFFER_RENDER_EVENT_PARAM);
							}
							
							renderPassWrapper.setIndexBuffer(bufferWrapper.getIndexGpuBuffer());
							renderPassWrapper.setVertexBuffer(bufferWrapper.vertexGpuBuffer);
							
							if (!bufferWrapper.vertexGpuBuffer.isClosed())
							{
								renderPassWrapper.drawIndexed(bufferWrapper.indexCount);
							}
						}
					}
					
				}
			}
		}
	}
	private String getIndexBufferName() { return "distantHorizons:LodIndexBuffer"; }
	private String getRenderPassName() { return "distantHorizons:TerrainRenderer"; }
	
	//endregion
	
	
	
}
#endif