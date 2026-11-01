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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GlDhTaaShader extends GlAbstractShaderRenderer
{
	public static final GlDhTaaShader INSTANCE = new GlDhTaaShader();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	
	public int frameBuffer;
	public int outputColorTextureId;
	private int historyColorTextureId;
	
	
	// Previous-frame state, captured at the END of render() for use NEXT frame.
	private final DhMat4f previousDhProjMvmMatrix = new DhMat4f();
	private final DhVec3d previousCameraPos = new DhVec3d(0,0,0);
	
	
	
	//==========//
	// Uniforms //
	//==========//
	//region
	
	public int uDhProjectionInverse;
	public int uDhModelViewInverse;
	public int uDhPrevProjMvm;
	
	public int uCameraOffsetX;
	public int uCameraOffsetY;
	public int uCameraOffsetZ;
	
	public int uViewWidth;
	public int uViewHeight;
	
	public int uDepthIsZeroToPositiveOne;
	
	public int uCurrentColorSampler;
	public int uCurrentDepthSampler;
	
	public int uHistoryColorSampler;
	
	//endregion
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlDhTaaShader() { }
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/antialias/gl/taa.frag",
			"vPosition"
		);
		
		// all uniforms should be tryGet...
		// because disabling AntiAliasing can cause the GLSL to optimize out most (if not all) uniforms
		
		this.uDhProjectionInverse = this.shader.getUniformLocation("uDhProjectionInverse");
		this.uDhModelViewInverse = this.shader.getUniformLocation("uDhModelViewInverse");
		this.uDhPrevProjMvm = this.shader.getUniformLocation("uDhPrevProjMvm");
		
		this.uCameraOffsetX = this.shader.getUniformLocation("uCameraOffsetX");
		this.uCameraOffsetY = this.shader.getUniformLocation("uCameraOffsetY");
		this.uCameraOffsetZ = this.shader.getUniformLocation("uCameraOffsetZ");
		
		this.uViewWidth = this.shader.getUniformLocation("uViewWidth");
		this.uViewHeight = this.shader.getUniformLocation("uViewHeight");
		
		this.uDepthIsZeroToPositiveOne = this.shader.getUniformLocation("uDepthIsZeroToPositiveOne");
		
		this.uCurrentColorSampler = this.shader.getUniformLocation("uCurrentColorSampler");
		this.uCurrentDepthSampler = this.shader.getUniformLocation("uCurrentDepthSampler");
		
		this.uHistoryColorSampler = this.shader.getUniformLocation("uHistoryColorSampler");
		
	}
	
	//endregion
	
	
	
	//=============//
	// render prep //
	//=============//
	//region
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		DhMat4f dhProjectionInverse = new DhMat4f(renderParams.dhProjectionMatrix);
		dhProjectionInverse.invert();
		
		DhMat4f dhModelViewInverse = new DhMat4f(renderParams.dhModelViewMatrix);
		dhModelViewInverse.invert();
		
		double cameraOffsetX = renderParams.exactCameraPosition.x - this.previousCameraPos.x;
		double cameraOffsetY = renderParams.exactCameraPosition.y - this.previousCameraPos.y;
		double cameraOffsetZ = renderParams.exactCameraPosition.z - this.previousCameraPos.z;
		
		
		// apply uniforms
		{
			this.shader.setUniform(this.uDhProjectionInverse, dhProjectionInverse);
			this.shader.setUniform(this.uDhModelViewInverse, dhModelViewInverse);
			this.shader.setUniform(this.uDhPrevProjMvm, this.previousDhProjMvmMatrix);
			
			// individual camera items because vec3 byte alignment is cursed and breaks
			this.shader.setUniform(this.uCameraOffsetX, (float) cameraOffsetX);
			this.shader.setUniform(this.uCameraOffsetY, (float) cameraOffsetY);
			this.shader.setUniform(this.uCameraOffsetZ, (float) cameraOffsetZ);
			
			
			int width = MC_RENDER.getTargetFramebufferViewportWidth();
			int height = MC_RENDER.getTargetFramebufferViewportHeight();
			this.shader.setUniform(this.uViewWidth, (float) width);
			this.shader.setUniform(this.uViewHeight, (float) height);
			
			this.shader.setUniform(this.uDepthIsZeroToPositiveOne, (RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0);
		}
	}
	
	public void savePostRenderUniformObjects(RenderParams renderParams)
	{
		// Save this frame's data for next frame's reprojection
		
		this.previousDhProjMvmMatrix.set(renderParams.dhProjectionMatrix);
		this.previousDhProjMvmMatrix.multiply(renderParams.dhModelViewMatrix);
		
		this.previousCameraPos.set(renderParams.exactCameraPosition);
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	public void renderPrep(
		int frameBuffer,
		int historyColorTextureId, int outputColorTextureId)
	{
		this.frameBuffer = frameBuffer;
		this.historyColorTextureId = historyColorTextureId;
		this.outputColorTextureId = outputColorTextureId;
	}
	
	@Override
	protected void onRender()
	{
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBuffer);
		GLMC.disableScissorTest();
		GLMC.disableDepthTest();
		GLMC.disableBlend();
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveColorTextureId());
		LWJGL.glUniform1i(this.uCurrentColorSampler, 0);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE1);
		GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
		LWJGL.glUniform1i(this.uCurrentDepthSampler, 1);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE2);
		GLMC.glBindTexture(this.historyColorTextureId);
		LWJGL.glUniform1i(this.uHistoryColorSampler, 2);
		
		GlScreenQuad.INSTANCE.render();
	}
	
	//endregion
	
	
}
