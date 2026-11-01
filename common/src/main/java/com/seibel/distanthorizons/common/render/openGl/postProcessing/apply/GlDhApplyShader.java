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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.apply;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Applies {@link com.seibel.distanthorizons.core.render.renderer.LodRenderer}'s 
 * currently active color texture to Minecraft's framebuffer,
 * ignoring any depth that wasn't drawn. 
 */
public class GlDhApplyShader extends GlAbstractShaderRenderer
{
	public static GlDhApplyShader INSTANCE = new GlDhApplyShader();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	// uniforms
	public int uSourceColorTexture;
	public int uSourceDepthTexture;
	public int uIsReverseZDepth;
	
	
	
	//=======//
 	// setup //
 	//=======//
	//region
	
	private GlDhApplyShader() { }
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/apply/gl/apply.frag",
			"vPosition"
		);
		
		// uniform setup
		this.uSourceColorTexture = this.shader.getUniformLocation("uSourceColorTexture");
		this.uSourceDepthTexture = this.shader.getUniformLocation("uSourceDepthTexture");
		this.uIsReverseZDepth = this.shader.getUniformLocation("uIsReverseZDepth");
		
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	protected void onRender()
	{
		if (MC_RENDER.mcRendersToFrameBuffer())
		{
			this.renderToFrameBuffer();
		}
		else
		{
			this.renderToMcTexture();
		}
	}
	private void renderToFrameBuffer()
	{
		int targetFrameBuffer = MC_RENDER.getTargetFramebuffer();
		if (targetFrameBuffer == -1)
		{
			return;
		}
		
		
		try (GLState state = new GLState())
		{
			
			GLMC.disableDepthTest();
			
			// blending isn't needed, we're manually merging the MC and DH textures
			// Note: this prevents the sun/moon and stars from rendering through transparent LODs,
			// however this also fixes transparent LODs from glowing when rendered against the sky during the day
			GLMC.disableBlend();
			
			// old blending logic in case it's ever needed:
			//GLMC.enableBlend();
			//GL33.glBlendEquation(GL33.GL_FUNC_ADD);
			//GLMC.glBlendFunc(GL33.GL_ONE, GL33.GL_ONE_MINUS_SRC_ALPHA);
			
			GLMC.glActiveTexture(GL13.GL_TEXTURE0);
			GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveColorTextureId());
			LWJGL.glUniform1i(this.uSourceColorTexture, 0);
			
			GLMC.glActiveTexture(GL13.GL_TEXTURE1);
			GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
			LWJGL.glUniform1i(this.uSourceDepthTexture, 1);
			
			LWJGL.glUniform1i(this.uIsReverseZDepth, (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0);
			
			// Copy to MC's framebuffer
			GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFrameBuffer);
			
			GlScreenQuad.INSTANCE.render();
		}
		// everything's been restored, except at this point the MC framebuffer should now be used instead
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFrameBuffer);
		
	}
	private void renderToMcTexture()
	{
		int targetColorTextureId = MC_RENDER.getGlColorTextureId();
		if (targetColorTextureId == -1)
		{
			return;
		}
		
		int dhFrameBufferId = GlDhMetaRenderer.INSTANCE.getActiveFramebufferId();
		if (dhFrameBufferId == -1)
		{
			return;
		}
		
		int mcFrameBufferId = MC_RENDER.getTargetFramebuffer();
		if (mcFrameBufferId == -1)
		{
			return;
		}
		
		
		
		try (GLState state = new GLState())
		{
			GLMC.disableDepthTest();
			
			// blending isn't needed, we're just directly merging the MC and DH textures
			// Note: this prevents the sun/moon and stars from rendering through transparent LODs,
			// but it also resolves some other issues, so it's likely not an issue
			GLMC.disableBlend();
			
			GLMC.glActiveTexture(GL13.GL_TEXTURE0);
			GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveColorTextureId());
			LWJGL.glUniform1i(this.uSourceColorTexture, 0);
			
			GLMC.glActiveTexture(GL13.GL_TEXTURE1);
			GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
			LWJGL.glUniform1i(this.uSourceDepthTexture, 1);
			
			LWJGL.glUniform1i(this.uIsReverseZDepth, (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0);
			
			
			
			LWJGL.glFramebufferTexture(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, targetColorTextureId, 0);
			
			// Copy to MC's texture via MC's framebuffer
			GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, dhFrameBufferId);
			
			GlScreenQuad.INSTANCE.render();
		}
		// everything's been restored, except at this point the MC framebuffer should now be used instead
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mcFrameBufferId);
		
	}
	
	//endregion
	
	
	
}
