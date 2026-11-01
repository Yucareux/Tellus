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

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * 
 * Sharpens the image to offset the TAA blur
 * and draws onto DH's FrameBuffer. <br><br>
 * 
 * See Also: <br>
 * {@link GlDhTaaRenderer} - Parent to this shader. <br>
 * {@link GlDhTaaShader} - draws the AntiAliasing texture. <br>
 */
public class GlDhTaaSharpenShader extends GlAbstractShaderRenderer
{
	public static GlDhTaaSharpenShader INSTANCE = new GlDhTaaSharpenShader();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	// uniforms
	public int uViewWidth;
	public int uViewHeight;
	public int uCasAmount;
	
	public int uCurrentColorSampler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/antialias/gl/sharpen.frag",
			"vPosition"
		);
		
		// uniform setup
		this.uViewWidth = this.shader.getUniformLocation("uViewWidth");
		this.uViewHeight = this.shader.getUniformLocation("uViewHeight");
		this.uCasAmount = this.shader.getUniformLocation("uCasAmount");
		
		this.uCurrentColorSampler = this.shader.getUniformLocation("uCurrentColorSampler");
		
	}
	
	
	
	//=============//
	// render prep //
	//=============//
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		int height = MC_RENDER.getTargetFramebufferViewportHeight();
		int width = MC_RENDER.getTargetFramebufferViewportWidth();
		this.shader.setUniform(this.uViewWidth, (float) width);
		this.shader.setUniform(this.uViewHeight, (float) height);
		this.shader.setUniform(this.uCasAmount, 0.3f);
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(GlDhTaaShader.INSTANCE.outputColorTextureId);
		LWJGL.glUniform1i(this.uCurrentColorSampler, 0);
		
	}
	
	
	
	//========//
	// render //
	//========//
	
	@Override
	protected void onRender()
	{
		GLMC.enableBlend();
		LWJGL.glBlendEquation(GL14.GL_FUNC_ADD);
		GLMC.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		
		// Depth testing must be disabled otherwise this application shader won't apply anything.
		// setting this isn't necessary in vanilla, but some mods may change this, requiring it to be set manually, 
		// it should be automatically restored after rendering is complete.
		GLMC.disableDepthTest();
		
		
		// apply the rendered AntiAliasing to DH's framebuffer
		GLMC.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, GlDhTaaShader.INSTANCE.frameBuffer);
		GLMC.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, GlDhMetaRenderer.INSTANCE.getActiveFramebufferId());
		
		GlScreenQuad.INSTANCE.render();
		
		GLMC.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
	}
	
}
