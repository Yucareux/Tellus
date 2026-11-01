package com.seibel.distanthorizons.common.render.openGl.terrain;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexAttributePostGL43;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexAttributePreGL43;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import com.seibel.distanthorizons.common.render.openGl.util.vertexFormat.GlLodVertexFormat;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Handles rendering the normal LOD terrain.
 * @see LodQuadBuilder
 */
public class GlDhTerrainShaderProgram extends GlShaderProgram implements IDhApiShaderProgram
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
		.fileLevelConfig(Config.Common.Logging.logRendererEventToFile)
		.build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	
	private static final DhVec3f MODEL_POS = new DhVec3f();
	/** single event object used to reduce GC pressure */
	private static final DhApiBeforeBufferRenderEvent.EventParam BEFORE_BUFFER_RENDER_EVENT_PARAM = new DhApiBeforeBufferRenderEvent.EventParam();
	
	
	
	private boolean init = false;
	
	public GlAbstractVertexAttribute vao;
	
	/** used for TAA jitter, the 8 represents how many jitter points are available */
	private int frameIndexMod8 = 0;
	
	
	
	// uniforms //
	//region 
	
	public int uCombinedMatrix = -1;
	public int uModelOffset = -1;
	public int uWorldYOffset = -1;
	
	public int uMircoOffset = -1;
	public int uEarthRadius = -1;
	
	public int uFrameMod8 = -1;
	public int uViewWidth = -1;
	public int uViewHeight = -1;
	
	public int uLightMap = -1;
	public int uBlockAtlas = -1;
	
	// fragment shader uniforms
	public int uClipDistance = -1;
	public int uDitherDhRendering = -1;
	
	// Noise Uniforms
	public int uNoiseEnabled = -1;
	public int uNoiseSteps = -1;
	public int uNoiseIntensity = -1;
	public int uNoiseDropoff = -1;
	
	// Debug Uniform
	public int uIsWhiteWorld = -1;
	
	//endregion
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlDhTerrainShaderProgram()
	{
		super(
			"assets/distanthorizons/shaders/terrain/gl/vert.vert",
			"assets/distanthorizons/shaders/terrain/gl/frag.frag",
			new String[]{"vPosition", "color", "irisData"}
		);
	}
	
	public void tryInit()
	{
		if (this.init)
		{
			return;
		}
		
		
		this.uCombinedMatrix = this.getUniformLocation("uCombinedMatrix");
		this.uModelOffset = this.getUniformLocation("uModelOffset");
		this.uWorldYOffset = this.getUniformLocation("uWorldYOffset");
		this.uDitherDhRendering = this.getUniformLocation("uDitherDhRendering");
		this.uMircoOffset = this.getUniformLocation("uMircoOffset");
		this.uEarthRadius = this.getUniformLocation("uEarthRadius");
		
		this.uFrameMod8 = this.getUniformLocation("uFrameMod8");
		this.uViewWidth = this.getUniformLocation("uViewWidth");
		this.uViewHeight = this.getUniformLocation("uViewHeight");
		
		this.uLightMap = this.getUniformLocation("uLightMap");
		this.uBlockAtlas = this.getUniformLocation("uBlockAtlas");
		
		// Fog/Clip Uniforms
		this.uClipDistance = this.getUniformLocation("uClipDistance");
		
		// Noise Uniforms
		this.uNoiseEnabled = this.getUniformLocation("uNoiseEnabled");
		this.uNoiseSteps = this.getUniformLocation("uNoiseSteps");
		this.uNoiseIntensity = this.getUniformLocation("uNoiseIntensity");
		this.uNoiseDropoff = this.getUniformLocation("uNoiseDropoff");
		
		// Debug Uniform
		this.uIsWhiteWorld = this.getUniformLocation("uIsWhiteWorld");
		
		
		if (GLProxy.getInstance().vertexAttributeBufferBindingSupported)
		{
			this.vao = new GlVertexAttributePostGL43(); // also binds AbstractVertexAttribute
		}
		else
		{
			this.vao = new GlVertexAttributePreGL43(); // also binds AbstractVertexAttribute
		}
		this.vao.bind();
		
		// short: x, y, z, meta
		//      meta: byte skylight, byte blocklight, byte microOffset
		this.vao.setVertexAttribute(0, 0, GlVertexPointer.addUnsignedShortsPointer(4, false, true));
		// byte: r, g, b, a
		this.vao.setVertexAttribute(0, 1, GlVertexPointer.addUnsignedBytesPointer(4, true, false));
		// byte: iris material ID, normal index, 2 spacers
		this.vao.setVertexAttribute(0, 2, GlVertexPointer.addUnsignedBytesPointer(4, true, true));
		
		try
		{
			int vertexByteCount = GlLodVertexFormat.DH_VERTEX_FORMAT.getByteSize();
			this.vao.completeAndCheck(vertexByteCount);
		}
		catch (RuntimeException e)
		{
			System.out.println(GlLodVertexFormat.DH_VERTEX_FORMAT);
			throw e;
		}
		
		// unbinding here is necessary to fix an issue when running on Legacy GL
		this.vao.unbind();
		
		this.init = true;
	}
	
	//endregion
	
	
	
	//=============//
	// API methods //
	//=============//
	//region
	
	@Override
	public void bind()
	{
		this.tryInit();
		super.bind();
		this.vao.bind();
	}
	@Override
	public void unbind()
	{
		super.unbind();
		this.vao.unbind();
	}
	
	@Override
	public void free()
	{
		this.vao.free();
		super.free();
	}
	
	@Override
	public void bindVertexBuffer(int vbo) { this.vao.bindBufferToAllBindingPoints(vbo); }
	
	@Override
	public void fillUniformData(DhApiRenderParam renderParameters)
	{
		DhMat4f combinedMatrix = new DhMat4f(renderParameters.dhProjectionMatrix);
		combinedMatrix.multiply(renderParameters.dhModelViewMatrix);
		
		super.bind();
		
		// uniforms
		this.setUniform(this.uCombinedMatrix, combinedMatrix);
		this.setUniform(this.uMircoOffset, 0.01f); // 0.01 block offset
		
		this.setUniform(this.uLightMap, LightMapWrapper.GL_DH_BOUND_INDEX);
		
		boolean texturedLodsEnabled = Config.Client.Advanced.Graphics.Texture.enableTexturedLods.get();
		if (texturedLodsEnabled)
		{
			this.setUniform(this.uBlockAtlas, GlBlockTextureAtlas.GL_BOUND_INDEX);
		}
		
		this.setUniform(this.uWorldYOffset, (float) renderParameters.worldYOffset);
		
		this.setUniform(this.uDitherDhRendering, Config.Client.Advanced.Graphics.Quality.ditherDhFade.get());
		
		float curveRatio = Config.Client.Advanced.Graphics.Experimental.earthCurveRatio.get();
		if (curveRatio < -1.0f || curveRatio > 1.0f)
		{
			curveRatio = /*6371KM*/ 6371000.0f / curveRatio;
		}
		else
		{
			// disable curvature if the config value is between -1 and 1
			curveRatio = 0.0f;
		}
		this.setUniform(this.uEarthRadius, curveRatio);
		
		
		if (Config.Client.Advanced.Graphics.enableAntiAliasing.get()
			&&
			(
				IRIS_ACCESSOR == null
				|| !IRIS_ACCESSOR.isShaderPackInUse()
			)
		)
		{
			this.frameIndexMod8++;
			this.frameIndexMod8 %= 8;
		}
		else
		{
			this.frameIndexMod8 = -1;
		}
		this.setUniform(this.uFrameMod8, (float)this.frameIndexMod8);
		
		int width = MC_RENDER.getTargetFramebufferViewportWidth();
		int height = MC_RENDER.getTargetFramebufferViewportHeight();
		this.setUniform(this.uViewWidth, (float) width);
		this.setUniform(this.uViewHeight, (float) height);
		
		// Noise Uniforms
		this.setUniform(this.uNoiseEnabled, Config.Client.Advanced.Graphics.NoiseTexture.enableNoiseTexture.get());
		this.setUniform(this.uNoiseSteps, Config.Client.Advanced.Graphics.NoiseTexture.noiseSteps.get());
		this.setUniform(this.uNoiseIntensity, Config.Client.Advanced.Graphics.NoiseTexture.noiseIntensity.get());
		this.setUniform(this.uNoiseDropoff, Config.Client.Advanced.Graphics.NoiseTexture.noiseDropoff.get());
		
		// Debug
		this.setUniform(this.uIsWhiteWorld, Config.Client.Advanced.Debugging.enableWhiteWorld.get());
		
		// Clip Uniform
		float dhNearClipDistance = RenderUtil.getNearClipPlaneInBlocks();
		if (!Config.Client.Advanced.Debugging.lodOnlyMode.get())
		{
			// this added value prevents the near clip plane and discard circle from touching, which looks bad
			dhNearClipDistance += 16f;
		}
		this.setUniform(this.uClipDistance, dhNearClipDistance);
	}
	
	@Override
	public void setModelOffsetPos(DhApiVec3f modelOffsetPos) { this.setUniform(this.uModelOffset, new DhVec3f(modelOffsetPos)); }
	
	@Override
	public int getId() { return this.id; }
	
	/** The base DH render program should always render */
	@Override
	public boolean overrideThisFrame() { return true; }
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void render(RenderParams renderEventParam, boolean opaquePass, SortedArraySet<LodBufferContainer> bufferContainers, IProfilerWrapper profiler)
	{
		//=======================//
		// debug wireframe setup //
		//=======================//
		
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
		
		if (!opaquePass)
		{
			GLMC.enableBlend();
			GLMC.enableDepthTest();
			LWJGL.glBlendEquation(GL14.GL_FUNC_ADD);
			GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		}
		else
		{
			GLMC.disableBlend();
		}
		
		// needs to be explicitly called since Iris may disable color rendering and not re-enable it
		// when boats are rendered in the scene (due to rendering out water inside the boat)
		LWJGL.glColorMask(true, true, true, true);
		
		// needs to be triggered after DH attempts to set the GL state so that Iris 
		// can override it as needed
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeRenderPassEvent.class, renderEventParam.apiCopy);
		
		
		
		//===========//
		// rendering //
		//===========//
		
		if (IRIS_ACCESSOR != null)
		{
			// done to fix a bug with Iris where face culling isn't properly set or reverted in the MC state manager
			// which causes Sodium to render some water chunks with their normals inverted
			// https://github.com/IrisShaders/Iris/issues/2582
			// https://github.com/IrisShaders/Iris/blob/1.21.9/common/src/main/java/net/irisshaders/iris/compat/dh/LodRendererEvents.java#L346
			GLMC.enableFaceCulling();
		}
		
		
		if (bufferContainers != null)
		{
			for (int lodIndex = 0; lodIndex < bufferContainers.size(); lodIndex++)
			{
				LodBufferContainer bufferContainer = bufferContainers.get(lodIndex);
				if (!bufferContainer.buffersUploaded)
				{
					// make sure we don't accidentally try
					// rendering a buffer that is (or is going to be) freed 
					continue;
				}
				
				// set uniforms and fire events
				{
					DhVec3d camPos = renderEventParam.exactCameraPosition;
					MODEL_POS.set(
						(float) (bufferContainer.minCornerBlockPos.getX() - camPos.x),
						(float) (bufferContainer.minCornerBlockPos.getY() - camPos.y),
						(float) (bufferContainer.minCornerBlockPos.getZ() - camPos.z));
					BEFORE_BUFFER_RENDER_EVENT_PARAM.update(renderEventParam, MODEL_POS);
					
					GlDhMetaRenderer.INSTANCE.shaderProgramForThisFrame.bind();
					GlDhMetaRenderer.INSTANCE.shaderProgramForThisFrame.setModelOffsetPos(MODEL_POS);
					
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeBufferRenderEvent.class, BEFORE_BUFFER_RENDER_EVENT_PARAM);
				}
				
				IVertexBufferWrapper[] vertexBuffers = (opaquePass ? bufferContainer.vboOpaqueWrappers : bufferContainer.vboTransparentWrappers);
				for (int vboIndex = 0; vboIndex < vertexBuffers.length; vboIndex++)
				{
					GLVertexBuffer vbo = (GLVertexBuffer) vertexBuffers[vboIndex];
					if (vbo == null)
					{
						continue;
					}
					
					
					// for lock information please view the lock's javadocs
					long vboReadStamp = vbo.renderStampLock.readLock();
					long iboReadStamp = vbo.getQuadIBO().renderStampLock.readLock();
					try
					{
						// don't render empty sections
						if (vbo.getVertexCount() == 0)
						{
							continue;
						}
						
						// don't render deleted VBOs (this will crash the driver/game)
						if (vbo.getId() == 0
							|| vbo.getQuadIBO().getId() == 0)
						{
							continue;
						}
						
						// 4 vertices per face, but 6 indices (IE 2 triangles) per face, aka need to multiply by 1.5
						int indexCount = (int) (vbo.getVertexCount() * 1.5);
						
						vbo.bind();
						vbo.getQuadIBO().bind();
						
						GlDhMetaRenderer.INSTANCE.shaderProgramForThisFrame.bindVertexBuffer(vbo.getId());
						LWJGL.glDrawElements(
							GL11.GL_TRIANGLES,
							indexCount,
							vbo.getQuadIBO().getGlType(), 0);
						
						vbo.unbind();
						vbo.getQuadIBO().unbind();
					}
					finally
					{
						vbo.renderStampLock.unlock(vboReadStamp);
						vbo.getQuadIBO().renderStampLock.unlock(iboReadStamp);
					}
				}
			}
		}
		
		
		
		//=========================//
		// debug wireframe cleanup //
		//=========================//
		
		if (renderWireframe)
		{
			// default back to GL_FILL since all other rendering uses it 
			LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
			GLMC.enableFaceCulling();
		}
		
		// Restore GL states that 1.12.2 vanilla expects
		#if MC_VER <= MC_1_12_2
		if (!opaquePass)
		{
			GLMC.disableBlend();
			GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO);
		}
		#endif
	}
	
	//endregion
	
	
	
}