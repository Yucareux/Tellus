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
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Draws the SSAO to a texture. <br><br>
 *
 * See Also: <br>
 * {@link GlDhSSAORenderer} - Parent to this shader. <br>
 * {@link GlDhSSAOApplyShader} - draws the SSAO texture to DH's FrameBuffer. <br>
 */
public class GlDhSSAOShader extends GlAbstractShaderRenderer
{
	public static GlDhSSAOShader INSTANCE = new GlDhSSAOShader();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	public int frameBuffer;
	
	private DhMat4f projection;
	private DhMat4f invertedProjection;
	
	
	// uniforms
	public int uProj;
	public int uInvProj;
	public int uSampleCount;
	public int uRadius;
	public int uStrength;
	public int uMinLight;
	public int uBias;
	public int uDhDepthTexture;
	public int uFadeDistanceInBlocks;
	public int uIsReverseZDepth;
	public int uDepthIsZeroToPositiveOne;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/ssao/gl/ao.frag",
			"vPosition"
		);
		
		// uniform setup
		this.uProj = this.shader.getUniformLocation("uProj");
		this.uInvProj = this.shader.getUniformLocation("uInvProj");
		this.uSampleCount = this.shader.getUniformLocation("uSampleCount");
		this.uRadius = this.shader.getUniformLocation("uRadius");
		this.uStrength = this.shader.getUniformLocation("uStrength");
		this.uMinLight = this.shader.getUniformLocation("uMinLight");
		this.uBias = this.shader.getUniformLocation("uBias");
		this.uDhDepthTexture = this.shader.getUniformLocation("uDhDepthTexture");
		this.uFadeDistanceInBlocks = this.shader.getUniformLocation("uFadeDistanceInBlocks");
		this.uIsReverseZDepth = this.shader.getUniformLocation("uIsReverseZDepth");
		this.uDepthIsZeroToPositiveOne = this.shader.getUniformLocation("uDepthIsZeroToPositiveOne");
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	public void setProjectionMatrix(DhApiMat4f projectionMatrix)
	{
		this.projection = new DhMat4f(projectionMatrix);
		
		this.invertedProjection = new DhMat4f(projectionMatrix);
		this.invertedProjection.invert();
	}
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		this.shader.setUniform(this.uProj, this.projection);
		
		this.shader.setUniform(this.uInvProj, this.invertedProjection);
		
		this.shader.setUniform(this.uSampleCount, 6);
		this.shader.setUniform(this.uRadius, 4.0f);
		this.shader.setUniform(this.uStrength, 0.2f);
		this.shader.setUniform(this.uMinLight, 0.25f);
		this.shader.setUniform(this.uBias, 0.02f);
		this.shader.setUniform(this.uFadeDistanceInBlocks, 1_600.0f);
		
		this.shader.setUniform(this.uIsReverseZDepth, (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0);
		this.shader.setUniform(this.uDepthIsZeroToPositiveOne, (RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0);
		
	}
	
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBuffer);
		GLMC.disableScissorTest();
		GLMC.disableDepthTest();
		GLMC.disableBlend();
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
		
		GlScreenQuad.INSTANCE.render();
	}
	
	
	
}
