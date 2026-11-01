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

package com.seibel.distanthorizons.common.render.openGl.generic;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLIndexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.GenericRenderObjectFactory;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.coreapi.ModInfo;
import org.lwjgl.opengl.ARBInstancedArrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles rendering generic groups of {@link DhApiRenderableBox}.
 * 
 * @see IDhApiCustomRenderRegister
 * @see DhApiRenderableBox
 */
public class GlGenericObjectRenderer implements IDhGenericRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	private static final DhApiRenderableBoxGroupShading DEFAULT_SHADING = DhApiRenderableBoxGroupShading.getUnshaded();
	
	private static final DhApiBeforeGenericObjectRenderEvent.EventParam EVENT_PARAM = new DhApiBeforeGenericObjectRenderEvent.EventParam();
	
	/** 
	 * Can be used to troubleshoot the renderer. 
	 * If enabled several debug objects will render around (0,150,0). 
	 */
	public static final boolean RENDER_DEBUG_OBJECTS = false;
	
	
	// rendering setup
	private boolean init = false;
	
	private IDhApiGenericObjectShaderProgram instancedShaderProgram;
	private IDhApiGenericObjectShaderProgram directShaderProgram;
	private GLVertexBuffer boxVertexBuffer;
	private GLIndexBuffer boxIndexBuffer;
	
	private boolean instancedRenderingAvailable;
	private boolean vertexAttribDivisorSupported;
	private boolean instancedArraysSupported;
	
	
	
	private final ConcurrentHashMap<Long, RenderableBoxGroup> boxGroupById = new ConcurrentHashMap<>();
	
	
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
	//region
			// Pos x y z
			
			// min X, vertical face
			0, 0, 0,
			1, 0, 0,
			1, 1, 0,
			0, 1, 0,
			// max X, vertical face
			0, 1, 1,
			1, 1, 1,
			1, 0, 1,
			0, 0, 1,
			
			// min Z, vertical face
			0, 0, 1,
			0, 0, 0,
			0, 1, 0,
			0, 1, 1,
			// max Z, vertical face
			1, 0, 1,
			1, 1, 1,
			1, 1, 0,
			1, 0, 0,
			
			// min Y, horizontal face
			0, 0, 1,
			1, 0, 1,
			1, 0, 0,
			0, 0, 0,
			// max Y, horizontal face
			0, 1, 1,
			1, 1, 1,
			1, 1, 0,
			0, 1, 0,
	//endregion
	};
	
	
	private static final int[] BOX_INDICES = {
	//region
			// min X, vertical face
			2, 1, 0,    
			0, 3, 2,
			// max X, vertical face
			6, 5, 4,
			4, 7, 6,
			
			// min Z, vertical face
			10, 9, 8,
			8, 11, 10,
			// max Z, vertical face
			14, 13, 12,
			12, 15, 14,
			
			// min Y, horizontal face
			18, 17, 16,
			16, 19, 18,
			// max Y, horizontal face
			20, 21, 22, 
			22, 23, 20,
	//endregion
	};
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlGenericObjectRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		
		
		//===================================//
		// is instanced rendering available? //
		//===================================//
		
		this.vertexAttribDivisorSupported = GLProxy.getInstance().vertexAttribDivisorSupported;
		this.instancedArraysSupported = GLProxy.getInstance().instancedArraysSupported;
		boolean isMac = (EPlatform.get() == EPlatform.MACOS);
		if (isMac)
		{
			LOGGER.warn("Generic rendering not supported by Mac. Clouds, beacons, and some other effects will be disabled.");
			Config.Client.Advanced.Graphics.GenericRendering.enableGenericRendering.setMcVersionOverrideValue(false);
			// the following can't be enabled anyway, but manually turning
			// them off helps make the UI look correct
			Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.setMcVersionOverrideValue(false);
			Config.Client.Advanced.Graphics.GenericRendering.enableBeaconRendering.setMcVersionOverrideValue(false);
			return;
		}
		
		this.instancedRenderingAvailable = (this.vertexAttribDivisorSupported || this.instancedArraysSupported) && !isMac;
		if (!this.instancedRenderingAvailable)
		{
			LOGGER.warn("Instanced rendering not supported by this GPU, falling back to direct rendering. Generic object rendering will be slow and some effects may be disabled.");
		}
		
		
		
		//======================//
		// startup the renderer //
		//======================//
		
		this.instancedShaderProgram = new GlGenericObjectShaderProgram(true);
		this.directShaderProgram = new GlGenericObjectShaderProgram(false);
		
		this.createBuffers();
		
		if (RENDER_DEBUG_OBJECTS)
		{
			this.addGenericDebugObjects();
		}
	}
	private void createBuffers()
	{
		// box vertices 
		ByteBuffer boxVerticesBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		boxVerticesBuffer.order(ByteOrder.nativeOrder());
		boxVerticesBuffer.asFloatBuffer().put(BOX_VERTICES);
		boxVerticesBuffer.rewind();
		this.boxVertexBuffer = new GLVertexBuffer(false);
		this.boxVertexBuffer.bind();
		this.boxVertexBuffer.uploadBuffer(boxVerticesBuffer, 8, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
		
		// box vertex indexes
		ByteBuffer solidIndexBuffer = ByteBuffer.allocateDirect(BOX_INDICES.length * Integer.BYTES);
		solidIndexBuffer.order(ByteOrder.nativeOrder());
		solidIndexBuffer.asIntBuffer().put(BOX_INDICES);
		solidIndexBuffer.rewind();
		this.boxIndexBuffer = new GLIndexBuffer(false);
		this.boxIndexBuffer.uploadBuffer(solidIndexBuffer, EDhApiGpuUploadMethod.DATA, BOX_INDICES.length * Integer.BYTES, GL15.GL_STATIC_DRAW);
		this.boxIndexBuffer.bind();
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
		// generic rendering (both instanced and direct) is extremely unstable on Mac, so don't render anything
		if (EPlatform.get() == EPlatform.MACOS)
		{
			return;
		}
		
		
		
		// render setup //
		try (IProfilerWrapper.IProfileBlock setup_profile = profiler.push("setup"))
		{
			
			this.init();
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeGenericRenderSetupEvent.class, renderEventParam.apiCopy);
			
			
			boolean renderWireframe = Config.Client.Advanced.Debugging.renderWireframe.get();
			if (renderWireframe)
			{
				LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
				GLMC.disableFaceCulling();
			}
			else
			{
				LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
				GLMC.enableFaceCulling();
			}
			
			GLMC.enableBlend();
			LWJGL.glBlendEquation(GL14.GL_FUNC_ADD);
			GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
			
			IDhApiGenericObjectShaderProgram shaderProgram = this.instancedRenderingAvailable ? this.instancedShaderProgram : this.directShaderProgram;
			IDhApiGenericObjectShaderProgram shaderProgramOverride = OverrideInjector.INSTANCE.get(IDhApiGenericObjectShaderProgram.class);
			if (shaderProgramOverride != null && shaderProgram.overrideThisFrame())
			{
				shaderProgram = shaderProgramOverride;
			}
			
			shaderProgram.bind(renderEventParam);
			shaderProgram.bindVertexBuffer(this.boxVertexBuffer.getId());
			
			this.boxIndexBuffer.bind();
			
			
			
			// rendering //
			
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
				if (this.instancedRenderingAvailable)
				{
					boxGroup.tryUpdateInstancedDataAsync();
					
					// skip groups that haven't been uploaded yet
					if (boxGroup.vertexBufferContainer.getState() != GlGenericObjectVertexContainer.EState.RENDER)
					{
						continue;
					}
				}
				
				
				
				// render //
				
				profiler.popPush("rendering");
				try (IProfilerWrapper.IProfileBlock namespace_profile = profiler.push(boxGroup.getResourceLocationNamespace());
					IProfilerWrapper.IProfileBlock location_profile = profiler.push(boxGroup.getResourceLocationPath()))
				{
					if (this.instancedRenderingAvailable)
					{
						this.renderBoxGroupInstanced(shaderProgram, renderEventParam, boxGroup, renderEventParam.exactCameraPosition, profiler);
					}
					else
					{
						this.renderBoxGroupDirect(shaderProgram, renderEventParam, boxGroup, renderEventParam.exactCameraPosition, profiler);
					}
				}
				
				boxGroup.postRender(renderEventParam);
			}
			
			
			
			//==========//
			// clean up //
			//==========//
			
			profiler.popPush("cleanup");
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeGenericRenderCleanupEvent.class, renderEventParam.apiCopy);
			
			if (renderWireframe)
			{
				// default back to GL_FILL since all other rendering uses it 
				LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
				GLMC.enableFaceCulling();
			}
			
			shaderProgram.unbind();
			boxVertexBuffer.unbind();
			boxIndexBuffer.unbind();
			
			// Restore GL states that 1.12.2 vanilla expects
			#if MC_VER <= MC_1_12_2
			GLMC.disableBlend();
			GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
			#endif
		}
	}
	
	//endregion
	
	
	
	//=====================//
	// instanced rendering //
	//=====================//
	//region
	
	private void renderBoxGroupInstanced(
			IDhApiGenericObjectShaderProgram shaderProgram, DhApiRenderParam renderEventParam, 
			RenderableBoxGroup boxGroup, DhVec3d camPos,
			IProfilerWrapper profiler)
	{
		try (IProfilerWrapper.IProfileBlock render_profile = profiler.push("vertex setup"))
		{
			
			// update instance data //
			DhApiRenderableBoxGroupShading shading = boxGroup.shading;
			if (shading == null)
			{
				shading = DEFAULT_SHADING;
			}
			
			shaderProgram.fillIndirectUniformData(
				renderEventParam,
				shading, boxGroup,
				camPos);
			
			
			
			// Bind instance data //
			profiler.popPush("binding");
			
			GlGenericObjectVertexContainer container = (GlGenericObjectVertexContainer) (boxGroup.vertexBufferContainer);
			
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, container.color);
			LWJGL.glEnableVertexAttribArray(1);
			LWJGL.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
			this.vertexAttribDivisor(1, 1);
			
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, container.scale);
			LWJGL.glEnableVertexAttribArray(2);
			this.vertexAttribDivisor(2, 1);
			LWJGL.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
			
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, container.chunkPos);
			LWJGL.glEnableVertexAttribArray(3);
			this.vertexAttribDivisor(3, 1);
			LWJGL.glVertexAttribIPointer(3, 3, GL11.GL_INT, 3 * Integer.BYTES, 0);
			
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, container.subChunkPos);
			LWJGL.glEnableVertexAttribArray(4);
			this.vertexAttribDivisor(4, 1);
			LWJGL.glVertexAttribPointer(4, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
			
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, container.material);
			LWJGL.glEnableVertexAttribArray(5);
			this.vertexAttribDivisor(5, 1);
			LWJGL.glVertexAttribIPointer(5, 1, GL11.GL_BYTE, Byte.BYTES, 0);
			
			
			// Draw instanced
			profiler.popPush("render");
			if (container.uploadedBoxCount > 0)
			{
				LWJGL.glDrawElementsInstanced(GL11.GL_TRIANGLES, BOX_INDICES.length, GL11.GL_UNSIGNED_INT, 0, container.uploadedBoxCount);
			}
			
			
			// Clean up
			profiler.popPush("cleanup");
			
			LWJGL.glDisableVertexAttribArray(1);
			LWJGL.glDisableVertexAttribArray(2);
			LWJGL.glDisableVertexAttribArray(3);
			LWJGL.glDisableVertexAttribArray(4);
			LWJGL.glDisableVertexAttribArray(5);
		}
	}
	/** 
	 * Clean way to handle both {@link LWJGL#glVertexAttribDivisor} and {@link ARBInstancedArrays#glVertexAttribDivisorARB}
	 * based on which one is supported.
	 */
	private void vertexAttribDivisor(int index, int divisor)
	{
		if (this.vertexAttribDivisorSupported)
		{
			LWJGL.glVertexAttribDivisor(index, divisor);	
		}
		else if(this.instancedArraysSupported)
		{
			ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor);
		}
		else
		{
			throw new IllegalStateException("Instanced rendering isn't supported by this machine. Direct rendering should have been used instead.");
		}
	}
	
	//endregion
	
	
	
	//==================//
	// direct rendering //
	//==================//
	//region
	
	private void renderBoxGroupDirect(
		IDhApiGenericObjectShaderProgram shaderProgram, 
		DhApiRenderParam renderEventParam, 
		RenderableBoxGroup boxGroup, DhVec3d camPos,
		IProfilerWrapper profiler)
	{
		profiler.popPush("shared uniforms");
		DhApiRenderableBoxGroupShading shading = boxGroup.shading;
		if (shading == null)
		{
			shading = DhApiRenderableBoxGroupShading.getUnshaded();
		}
		
		shaderProgram.fillSharedDirectUniformData(renderEventParam, shading, boxGroup, camPos);
		
		for (int i = 0; i < boxGroup.size(); i++)
		{
			try
			{
				DhApiRenderableBox box = boxGroup.get(i);
				if (box != null)
				{
					profiler.popPush("direct uniforms");
					shaderProgram.fillDirectUniformData(renderEventParam, boxGroup, box, camPos);
					
					profiler.popPush("render");
					LWJGL.glDrawElements(GL11.GL_TRIANGLES, BOX_INDICES.length, GL11.GL_UNSIGNED_INT, 0);
				}
			}
			catch (IndexOutOfBoundsException e)
			{
				// Concurrency issue, the list was modified while rendering
				// this can probably be ignored.
				// However, if it does become a problem we can add locks to the box group. 
				break;
			}
		}
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	/** @throws IllegalStateException if {@link #init()} function hasn't been called yet */
	public boolean getInstancedRenderingAvailable() throws IllegalStateException
	{
		if (!this.init)
		{
			throw new IllegalStateException("GL initialization hasn't been completed.");
		}
		
		return this.instancedRenderingAvailable; 
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
		if (this.boxVertexBuffer != null)
		{
			this.boxVertexBuffer.close();
		}
		
		if (this.boxIndexBuffer != null)
		{
			this.boxIndexBuffer.close();
		}
	}
	
	//endregion
	
	
	
}
