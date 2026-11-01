package com.seibel.distanthorizons.common.render.openGl;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiTextureCreatedParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import com.seibel.distanthorizons.common.render.openGl.glObject.GlDhFramebuffer;
import com.seibel.distanthorizons.common.render.openGl.glObject.texture.*;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.copy.GlDhCopyShader;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlBlockTextureAtlas;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IOptifineAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GlDhMetaRenderer implements IDhMetaRenderer
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
		.fileLevelConfig(Config.Common.Logging.logRendererEventToFile)
		.build();
	
	public static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
		.fileLevelConfig(Config.Common.Logging.logRendererEventToFile)
		.maxCountPerSecond(4)
		.build();
	
	public static final GlDhMetaRenderer INSTANCE = new GlDhMetaRenderer();
	
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final IOptifineAccessor OPTIFINE_ACCESSOR = ModAccessorInjector.INSTANCE.get(IOptifineAccessor.class);
	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	
	
	private int activeFramebufferId = -1;
	private int activeColorTextureId = -1;
	private int activeDepthTextureId = -1;
	private int textureWidth;
	private int textureHeight;
	
	
	// framebuffer and texture ID's for this renderer
	private IDhApiFramebuffer framebuffer;
	/** will be null if MC's framebuffer is being used since MC already has a color texture */
	@Nullable
	private GlDhColorTexture nullableColorTexture;
	private GlDhDepthTexture depthTexture;
	/**
	 * If true the {@link GlDhMetaRenderer#framebuffer} is the same as MC's.
	 * This should only be true in the case of Optifine so LODs won't be overwritten when shaders are enabled.
	 */
	private boolean usingMcFramebuffer = false;
	
	private boolean renderObjectsCreated = false;
	/** used in case there's an API override */
	public IDhApiShaderProgram shaderProgramForThisFrame;
	
	/** Older MC versions assume GL state is unchanged, so we must restore it after DH rendering */
	#if MC_VER <= MC_1_12_2
	private int previousBoundTextureId;
	private int previousDepthFunc;
	private final float[] previousClearDepth = new float[1];
	private boolean previousBlend;
	private int previousBlendSrcRgb;
	private int previousBlendDstRgb;
	private int previousBlendSrcAlpha;
	private int previousBlendDstAlpha;
	private boolean previousDepthMask;
	#endif
	
	
	
	//============//
	// pre render //
	//============//
	//region
	
	@Override
	public void runRenderPassSetup(RenderParams renderParams)
	{
		boolean firstPass =
			(renderParams.renderPass == EDhApiRenderPass.OPAQUE
			|| renderParams.renderPass == EDhApiRenderPass.OPAQUE_AND_TRANSPARENT);
		
		if (!this.renderObjectsCreated)
		{
			boolean setupSuccess = this.createRenderObjects();
			if (!setupSuccess)
			{
				// shouldn't normally happen, but just in case
				return;
			}
			
			this.renderObjectsCreated = true;
		}
		
		this.shaderProgramForThisFrame = GlDhTerrainRenderer.INSTANCE.getTerrainShaderProgram();
		IDhApiShaderProgram lodShaderProgramOverride = OverrideInjector.INSTANCE.get(IDhApiShaderProgram.class);
		if (lodShaderProgramOverride != null && this.shaderProgramForThisFrame.overrideThisFrame())
		{
			this.shaderProgramForThisFrame = lodShaderProgramOverride;
		}
		
		
		this.setGLState(renderParams, firstPass);
		
		this.bindLightmap(renderParams.lightmap);
		
		// Iris also needs the neutral atlas tile when DH textures are disabled.
		// Iris owns its sampler bindings; bind unit 1 only for DH's own shader.
		boolean irisInactive = this.irisShadersInactive();
		if (Config.Client.Advanced.Graphics.Texture.enableTexturedLods.get() || !irisInactive)
		{
			GlBlockTextureAtlas.INSTANCE.uploadPendingTiles();
			if (irisInactive) { GlBlockTextureAtlas.INSTANCE.bind(); }
		}
	}
	private void setGLState(
		DhApiRenderParam renderEventParam,
		boolean firstPass)
	{
		//=================//
		// local var setup //
		//=================//
		
		#if MC_VER <= MC_1_12_2
		this.previousBoundTextureId = GLMC.getActiveTexture();
		this.previousDepthFunc = GLMC.getActiveDepthFunc();
		LWJGL.glGetFloatv(GL11.GL_DEPTH_CLEAR_VALUE, this.previousClearDepth);
		this.previousBlend = LWJGL.glGetBoolean(GL11.GL_BLEND);
		this.previousBlendSrcRgb = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_RGB);
		this.previousBlendDstRgb = LWJGL.glGetInteger(GL14.GL_BLEND_DST_RGB);
		this.previousBlendSrcAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
		this.previousBlendDstAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		this.previousDepthMask = LWJGL.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		#endif
		
		// view sizes are used in a few places and
		// should be used instead of the instance variables
		// to prevent using the last frame's value
		int viewportWidth = MC_RENDER.getTargetFramebufferViewportWidth();
		int viewportHeight = MC_RENDER.getTargetFramebufferViewportHeight();
		
		
		
		//===================//
		// framebuffer setup //
		//===================//
		
		// get the active framebuffer
		IDhApiFramebuffer framebuffer = this.framebuffer;
		IDhApiFramebuffer framebufferOverride = OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class);
		if (framebufferOverride != null && framebufferOverride.overrideThisFrame())
		{
			framebuffer = framebufferOverride;
		}
		this.setActiveFramebufferId(framebuffer.getId());
		framebuffer.bind();
		
		
		
		//==========//
		// bindings //
		//==========//
		
		// by default draw everything as triangles
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
		GLMC.enableFaceCulling();
		
		GLMC.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		
		LWJGL.glDisable(GL11.GL_SCISSOR_TEST);
		
		// Enable depth test and depth mask
		GLMC.enableDepthTest();
		if (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.FORWARD_Z)
		{
			GLMC.glDepthFunc(GL11.GL_LESS);
		}
		else
		{
			GLMC.glDepthFunc(GL11.GL_GREATER);
		}
		GLMC.enableDepthMask();
		
		// don't change the viewport size when Iris is rendering the shadow pass
		// Iris has a custom shadowmap size, and changing the viewport will cause shadows to render
		// incorrectly.
		if (IRIS_ACCESSOR == null
			|| !IRIS_ACCESSOR.isRenderingShadowPass())
		{
			// This is required for MC versions 1.21.5+
			// due to MC updating the lightmap by changing the viewport size
			// and some camera mods that change the viewport size 
			GLMC.glViewport(0, 0, viewportWidth, viewportHeight);
		}
		
		this.shaderProgramForThisFrame.bind();
		
		
		
		//==========//
		// uniforms //
		//==========//
		
		IDhApiShaderProgram shaderProgramOverride = OverrideInjector.INSTANCE.get(IDhApiShaderProgram.class);
		if (shaderProgramOverride != null)
		{
			shaderProgramOverride.fillUniformData(renderEventParam);
		}
		
		this.shaderProgramForThisFrame.fillUniformData(renderEventParam);
		
		
		
		//===============//
		// texture setup //
		//===============//
		
		// resize the textures if needed
		if (viewportWidth != this.textureWidth
			|| viewportHeight != this.textureHeight)
		{
			// just resizing the textures doesn't work when Optifine is present,
			// so recreate the textures with the new size instead
			this.createAndBindTextures();
		}
		
		
		// set the active textures
		int depthTextureId = this.depthTexture.getTextureId();
		this.setActiveDepthTextureId(depthTextureId);
		
		if (this.nullableColorTexture != null)
		{
			int colorTextureId = this.nullableColorTexture.getTextureId();
			this.setActiveColorTextureId(colorTextureId);
		}
		else
		{
			// get MC's color texture 
			int colorTextureId = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
			this.setActiveColorTextureId(colorTextureId);
		}
		
		
		// needs to be fired after all the textures have been created/bound
		boolean clearTextures = !ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeTextureClearEvent.class, renderEventParam);
		if (clearTextures)
		{
			float clearDepth = RENDER_DEF.getDepthDirection().farDepth;
			LWJGL.glClearDepth(clearDepth);
			
			float[] clearColorValues = new float[4];
			LWJGL.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorValues);
			LWJGL.glClearColor(clearColorValues[0], clearColorValues[1], clearColorValues[2], 1.0f);
			
			if (this.usingMcFramebuffer && framebufferOverride == null)
			{
				// Due to using MC/Optifine's framebuffer we need to re-bind the depth texture,
				// otherwise we'll be writing to MC/Optifine's depth texture which causes rendering issues
				framebuffer.addDepthAttachment(this.depthTexture.getTextureId(), EGlDhDepthBufferFormat.DEPTH32F.isCombinedStencil());
				
				
				// don't clear the color texture, that removes the sky 
				LWJGL.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			}
			else if (firstPass)
			{
				LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			}
		}
	}
	
	private boolean createRenderObjects()
	{
		if (this.renderObjectsCreated)
		{
			LOGGER.warn("Renderer setup called but it has already completed setup!");
			return false;
		}
		
		// GLProxy should have already been created by this point, but just in case create it now
		GLProxy.getInstance();
		
		
		
		LOGGER.info("Setting up renderer");
		
		
		// create or get the frame buffer
		if (OPTIFINE_ACCESSOR != null)
		{
			// use MC/Optifine's default Framebuffer so shaders won't remove the LODs
			int currentFramebufferId = MC_RENDER.getTargetFramebuffer();
			this.framebuffer = new GlDhFramebuffer(currentFramebufferId);
			this.usingMcFramebuffer = true;
		}
		else
		{
			// normal use case
			this.framebuffer = new GlDhFramebuffer();
			this.usingMcFramebuffer = false;
		}
		
		// create and bind the necessary textures
		this.createAndBindTextures();
		
		if(this.framebuffer.getStatus() != GL30.GL_FRAMEBUFFER_COMPLETE)
		{
			// This generally means something wasn't bound, IE missing either the color or depth texture
			LOGGER.warn("Framebuffer ["+this.framebuffer.getId()+"] isn't complete.");
			return false;
		}
		
		
		
		LOGGER.info("Renderer setup complete");
		return true;
	}
	
	@SuppressWarnings( "deprecation" ) // done to ignore DhApiColorDepthTextureCreatedEvent
	private void createAndBindTextures()
	{
		int oldWidth = this.textureWidth;
		int oldHeight = this.textureHeight;
		this.textureWidth = MC_RENDER.getTargetFramebufferViewportWidth();
		this.textureHeight = MC_RENDER.getTargetFramebufferViewportHeight();
		
		DhApiTextureCreatedParam textureCreatedParam = new DhApiTextureCreatedParam(
			oldWidth, oldHeight,
			this.textureWidth, this.textureHeight
		);
		
		
		// DhApiColorDepthTextureCreatedEvent needs to be kept around since old versions of Iris need it
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiColorDepthTextureCreatedEvent.class, new DhApiColorDepthTextureCreatedEvent.EventParam(textureCreatedParam));
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiBeforeColorDepthTextureCreatedEvent.class, textureCreatedParam);
		
		
		// also update the framebuffer override if present
		IDhApiFramebuffer framebufferOverride = OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class);
		
		
		GlDhDepthTexture oldDepthTexture = this.depthTexture;
		this.depthTexture = new GlDhDepthTexture(this.textureWidth, this.textureHeight, EGlDhDepthBufferFormat.DEPTH32F);
		this.framebuffer.addDepthAttachment(this.depthTexture.getTextureId(), EGlDhDepthBufferFormat.DEPTH32F.isCombinedStencil());
		if (framebufferOverride != null)
		{
			framebufferOverride.addDepthAttachment(this.depthTexture.getTextureId(), EGlDhDepthBufferFormat.DEPTH32F.isCombinedStencil());
		}
		// The old texture needs to be deleted after the new one is created to ensure
		// GL creates a new depth texture ID.
		// This is necessary to fix an Iris bug where it caches on the texture ID,
		// but GL may return the same texture ID, preventing the new texture from being bound properly.
		if (oldDepthTexture != null)
		{
			oldDepthTexture.destroy();
		}
		
		
		// if we are using MC's frame buffer, a color texture is already present and shouldn't need to be bound
		if (!this.usingMcFramebuffer)
		{
			if (this.nullableColorTexture != null)
			{
				this.nullableColorTexture.destroy();
			}
			
			this.nullableColorTexture = GlDhColorTexture.builder()
				.setDimensions(this.textureWidth, this.textureHeight)
				.setInternalFormat(EGlDhInternalTextureFormat.RGBA8)
				.setPixelType(EGlDhPixelType.UNSIGNED_BYTE)
				.setPixelFormat(EGlDhPixelFormat.RGBA)
				.build();
			
			this.framebuffer.addColorAttachment(0, this.nullableColorTexture.getTextureId());
			if (framebufferOverride != null)
			{
				framebufferOverride.addColorAttachment(0, this.nullableColorTexture.getTextureId());
			}
		}
		else
		{
			this.nullableColorTexture = null;
		}
		
		
		ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterColorDepthTextureCreatedEvent.class, textureCreatedParam);
	}
	
	//endregion
	
	
	
	//=============//
	// post render //
	//=============//
	//region
	
	@Override
	public void runRenderPassCleanup(RenderParams renderParams)
	{
		boolean runningDeferredPass = (renderParams.renderPass == EDhApiRenderPass.TRANSPARENT);
		if (!runningDeferredPass)
		{
			//===================//
			// optifine clean up //
			//===================//
			
			if (this.usingMcFramebuffer)
			{
				// If MC's framebuffer is being used the depth needs to be cleared to prevent rendering on top of MC.
				// This should only happen when Optifine shaders are being used.
				LWJGL.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			}
		}
		
		// Restore GL states that 1.12.2 vanilla expects
		#if MC_VER <= MC_1_12_2
		GLMC.glDepthFunc(previousDepthFunc);
		LWJGL.glClearDepth(this.previousClearDepth[0]);
		GLMC.glBlendFuncSeparate(this.previousBlendSrcRgb, this.previousBlendDstRgb, this.previousBlendSrcAlpha, this.previousBlendDstAlpha);
		if (this.previousBlend)
		{
			GLMC.enableBlend();
		}
		else
		{
			GLMC.disableBlend();
		}
		if (this.previousDepthMask)
		{
			GLMC.enableDepthMask();
		}
		else
		{
			GLMC.disableDepthMask();
		}
		#endif
		this.unbindLightmap();
		if (Config.Client.Advanced.Graphics.Texture.enableTexturedLods.get())
		{
			GlBlockTextureAtlas.INSTANCE.unbind();
		}
		this.shaderProgramForThisFrame.unbind();
	}
	
	@Override
	public void copyToMcTexture(RenderParams renderParams) { GlDhCopyShader.INSTANCE.render(renderParams); }
	
	//endregion
	
	
	
	//================//
	// clear textures //
	//================//
	//region
	
	@Override
	public void clearDhDepthAndColorTextures(RenderParams renderParams) 
	{
		IDhApiFramebuffer framebufferOverride = OverrideInjector.INSTANCE.get(IDhApiFramebuffer.class);
		
		boolean firstPass =
			(renderParams.renderPass == EDhApiRenderPass.OPAQUE
			|| renderParams.renderPass == EDhApiRenderPass.OPAQUE_AND_TRANSPARENT);
		
		
		
		float clearDepth = RENDER_DEF.getDepthDirection().farDepth;
		LWJGL.glClearDepth(clearDepth);
		
		float[] clearColorValues = new float[4];
		LWJGL.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorValues);
		// alpha of 0 done as a check to make sure DH is only applied to MC's framebuffer
		// where DH pixels were drawn
		LWJGL.glClearColor(clearColorValues[0], clearColorValues[1], clearColorValues[2], 0.0f);
		
		if (this.usingMcFramebuffer 
			&& framebufferOverride == null)
		{
			//// Due to using MC/Optifine's framebuffer we need to re-bind the depth texture,
			//// otherwise we'll be writing to MC/Optifine's depth texture which causes rendering issues
			//this.framebuffer.addDepthAttachment(this.depthTexture.getTextureId(), EDhDepthBufferFormat.DEPTH32F.isCombinedStencil());
			
			
			// don't clear the color texture, that removes the sky 
			LWJGL.glClear(GL11.GL_DEPTH_BUFFER_BIT);
		}
		else if (firstPass)
		{
			LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
		}
		
	}
	
	//endregion
	
	
	
	//===============//
	// API functions //
	//===============//
	//region
	
	public void setActiveFramebufferId(int id) { this.activeFramebufferId = id; }
	/** @return -1 if no frame buffer has been bound yet */
	public int getActiveFramebufferId() { return this.activeFramebufferId; }
	
	public void setActiveColorTextureId(int id)
	{
		this.activeColorTextureId = id;
		DhApiRenderProxy.activeOpenGlDhColorTextureId = id;
	}
	/** @return -1 if no texture has been bound yet */
	public int getActiveColorTextureId() { return this.activeColorTextureId; }
	
	public void setActiveDepthTextureId(int id)
	{
		this.activeDepthTextureId = id;
		DhApiRenderProxy.activeOpenGlDhDepthTextureId = id;
	}
	/** @return -1 if no texture has been bound yet */
	public int getActiveDepthTextureId() { return this.activeDepthTextureId; }
	
	//endregion
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	public void bindLightmap(ILightMapWrapper lightMapWrapper)
	{
		LightMapWrapper lightMap = (LightMapWrapper)lightMapWrapper;
		GLMC.glActiveTexture(GL13.GL_TEXTURE0 + LightMapWrapper.GL_DH_BOUND_INDEX);
		GLMC.glBindTexture(lightMap.getOpenGlId());
		
		#if MC_VER <= MC_1_21_11
		#else
		// This is only needed for MC 26.1.2 and newer
		// for older MC versions it causes issues due to overwriting existing Iris textures
		
		// Iris expects the lightmap in a different index on certain minecraft versions, so handle that here
		GLMC.glActiveTexture(GL13.GL_TEXTURE0 + LightMapWrapper.GL_IRIS_BOUND_INDEX);
		GLMC.glBindTexture(lightMap.getOpenGlId());
		#endif
	}
	
	public void unbindLightmap() 
	{
		// strange that we don't call "glActiveTexture" here but since it's working James isn't going to change it right now (2026-03-10)
		#if MC_VER <= MC_1_12_2
		GLMC.glBindTexture(previousBoundTextureId);
		#else
		GLMC.glBindTexture(0);
		#endif
	}
	
	private boolean irisShadersInactive()
	{
		return !(IRIS_ACCESSOR != null
			&& IRIS_ACCESSOR.isShaderPackInUse());
	}
	
	//endregion
	
	
}
