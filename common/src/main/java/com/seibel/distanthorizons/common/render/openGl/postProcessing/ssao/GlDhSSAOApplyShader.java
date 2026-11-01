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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.ssao;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Draws the SSAO texture onto DH's FrameBuffer. <br><br>
 * 
 * See Also: <br>
 * {@link GlDhSSAORenderer} - Parent to this shader. <br>
 * {@link GlDhSSAOShader} - draws the SSAO texture. <br>
 */
public class GlDhSSAOApplyShader extends GlAbstractShaderRenderer
{
	public static GlDhSSAOApplyShader INSTANCE = new GlDhSSAOApplyShader();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	public int ssaoTexture;
	
	// uniforms
	public int uSourceColorTexture;
	public int uSourceDepthTexture;
	public int uViewSize;
	public int uBlurRadius;
	public int uNearClipPlane;
	public int uFarClipPlane;
	public int uIsReverseZDepth;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/ssao/gl/apply.frag",
			"vPosition"
		);
		
		// uniform setup
		this.uSourceColorTexture = this.shader.getUniformLocation("uSourceColorTexture");
		this.uSourceDepthTexture = this.shader.getUniformLocation("uSourceDepthTexture");
		this.uViewSize = this.shader.getUniformLocation("uViewSize");
		this.uBlurRadius = this.shader.getUniformLocation("uBlurRadius");
		this.uNearClipPlane = this.shader.getUniformLocation("uNearClipPlane");
		this.uFarClipPlane = this.shader.getUniformLocation("uFarClipPlane");
		this.uIsReverseZDepth = this.shader.getUniformLocation("uIsReverseZDepth");
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
		LWJGL.glUniform1i(this.uSourceDepthTexture, 0);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE1);
		GLMC.glBindTexture(this.ssaoTexture);
		LWJGL.glUniform1i(this.uSourceColorTexture, 1);
		
		LWJGL.glUniform1i(this.uBlurRadius, 2);
		
		if (this.uViewSize >= 0)
		{
			LWJGL.glUniform2f(this.uViewSize,
					MC_RENDER.getTargetFramebufferViewportWidth(),
					MC_RENDER.getTargetFramebufferViewportHeight());
		}
		
		if (this.uNearClipPlane  >= 0)
		{
			LWJGL.glUniform1f(this.uNearClipPlane,
					RenderUtil.getNearClipPlaneInBlocks());
		}
		
		if (this.uFarClipPlane >= 0)
		{
			float farClipPlane = RenderUtil.getFarClipPlaneDistanceInBlocks();
			LWJGL.glUniform1f(this.uFarClipPlane, farClipPlane);
		}
		
		LWJGL.glUniform1i(this.uIsReverseZDepth, (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0);
	}
	
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GLMC.enableBlend();
		LWJGL.glBlendEquation(GL14.GL_FUNC_ADD);
		GLMC.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);

		// Depth testing must be disabled otherwise this application shader won't apply anything.
		// setting this isn't necessary in vanilla, but some mods may change this, requiring it to be set manually, 
		// it should be automatically restored after rendering is complete.
		GLMC.disableDepthTest();
		
		// apply the rendered SSAO to the LODs 
		GLMC.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GlDhSSAOShader.INSTANCE.frameBuffer);
		GLMC.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, GlDhMetaRenderer.INSTANCE.getActiveFramebufferId());
		
		
		GlScreenQuad.INSTANCE.render();
	}
	
	
	
}
