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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.fade;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GlDhFarFadeShader extends GlAbstractShaderRenderer
{
	public static GlDhFarFadeShader INSTANCE = new GlDhFarFadeShader();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	public int frameBuffer = -1;
	
	private DhApiMat4f inverseDhMvmProjMatrix;
	
	
	// Uniforms
	
	/** Inverted Model View Projection matrix */
	public int uDhInvMvmProj = -1;
	
	public int uDhDepthTexture = -1;
	public int uMcColorTexture = -1;
	public int uDhColorTexture = -1;
	
	public int uStartFadeBlockDistance = -1;
	public int uEndFadeBlockDistance = -1;
	
	public int uDepthIsZeroToPositiveOne = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public GlDhFarFadeShader() {  }

	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/fade/gl/dh_fade.frag",
			"vPosition"
		);
		
		// all uniforms should be tryGet...
		// because disabling fade can cause the GLSL to optimize out most (if not all) uniforms
		
		// near fade
		this.uDhInvMvmProj = this.shader.tryGetUniformLocation("uDhInvMvmProj");
		
		this.uDhDepthTexture = this.shader.tryGetUniformLocation("uDhDepthTexture");
		this.uMcColorTexture = this.shader.tryGetUniformLocation("uMcColorTexture");
		this.uDhColorTexture = this.shader.tryGetUniformLocation("uDhColorTexture");
		
		this.uStartFadeBlockDistance = this.shader.tryGetUniformLocation("uStartFadeBlockDistance");
		this.uEndFadeBlockDistance = this.shader.tryGetUniformLocation("uEndFadeBlockDistance");
		
		this.uDepthIsZeroToPositiveOne = this.shader.tryGetUniformLocation("uDepthIsZeroToPositiveOne");
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		this.shader.setUniform(this.uDhInvMvmProj, this.inverseDhMvmProjMatrix);
		
		
		float dhFarClipDistance = RenderUtil.getFarClipPlaneDistanceInBlocks();
		float fadeStartDistance = dhFarClipDistance * 0.5f;
		float fadeEndDistance = dhFarClipDistance * 0.9f;
		
		this.shader.setUniform(this.uStartFadeBlockDistance, fadeStartDistance);
		this.shader.setUniform(this.uEndFadeBlockDistance, fadeEndDistance);
		
		this.shader.setUniform(this.uDepthIsZeroToPositiveOne, (RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0);
		
	}
	
	public void setProjectionMatrix(RenderParams renderParams)
	{
		this.inverseDhMvmProjMatrix = renderParams.dhInverseMvmProjectionMatrix;
	}
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		int depthTextureId = GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId();
		int colorTextureId = GlDhMetaRenderer.INSTANCE.getActiveColorTextureId();
		
		if (depthTextureId == -1
			|| colorTextureId == -1)
		{
			// the renderer is currently being re-built and/or inactive,
			// we don't need to/can't render fading
			return;
		}
		
		
		
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBuffer);
		GLMC.disableScissorTest();
		GLMC.disableDepthTest();
		GLMC.disableBlend();
		
		// If we don't restore the textures some mods can break
		int prevActiveTexture = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		int prevTexture0 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GLMC.glActiveTexture(GL13.GL_TEXTURE1);
		int prevTexture1 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GLMC.glActiveTexture(GL13.GL_TEXTURE2);
		int prevTexture2 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(depthTextureId);
		LWJGL.glUniform1i(this.uDhDepthTexture, 0);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE1);
		GLMC.glBindTexture(MC_RENDER.getGlColorTextureId());
		LWJGL.glUniform1i(this.uMcColorTexture, 1);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE2);
		GLMC.glBindTexture(colorTextureId);
		LWJGL.glUniform1i(this.uDhColorTexture, 2);
		
		
		GlScreenQuad.INSTANCE.render();
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(prevTexture0);
		GLMC.glActiveTexture(GL13.GL_TEXTURE1);
		GLMC.glBindTexture(prevTexture1);
		GLMC.glActiveTexture(GL13.GL_TEXTURE2);
		GLMC.glBindTexture(prevTexture2);
		GLMC.glActiveTexture(prevActiveTexture);
	}
	
}
