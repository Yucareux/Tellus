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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.fog;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.common.render.openGl.util.GlAbstractShaderRenderer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GlDhFogShader extends GlAbstractShaderRenderer
{
	public static final GlDhFogShader INSTANCE = new GlDhFogShader();
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	
	
	public int frameBuffer;
	private DhMat4f inverseMvmProjMatrix; 
	private DhApiFogRenderParam fogRenderParams;
	
	
	
	//==========//
	// Uniforms //
	//==========//
	//region
	
	public int uDhDepthTexture;
	/** Inverted Model View Projection matrix */
	public int uInvMvmProj;
	
	public int uIsReverseZDepth;
	public int uDepthIsZeroToPositiveOne;
	
	// fog uniforms
	public int uFogColor;
	public int uFogScale;
	public int uFogVerticalScale;
	public int uFogDebugMode;
	public int uFogFalloffType;
	
	// far fog
	public int uFarFogStart;
	public int uFarFogLength;
	public int uFarFogMin;
	public int uFarFogRange;
	public int uFarFogDensity;
	
	// height fog
	public int uHeightFogStart;
	public int uHeightFogLength;
	public int uHeightFogMin;
	public int uHeightFogRange;
	public int uHeightFogDensity;
	
	public int uHeightFogEnabled;
	public int uHeightFogFalloffType;
	public int uHeightBasedOnCamera;
	public int uHeightFogBaseHeight;
	public int uHeightFogAppliesUp;
	public int uHeightFogAppliesDown;
	public int uUseSphericalFog;
	public int uHeightFogMixingMode;
	public int uCameraBlockYPos;
	
	//endregion
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public GlDhFogShader() { }
	
	@Override
	public void onInit()
	{
		this.shader = new GlShaderProgram(
			"assets/distanthorizons/shaders/shared/gl/quad_apply.vert",
			"assets/distanthorizons/shaders/fog/gl/fog.frag",
			"vPosition"
		);
		
		this.uDhDepthTexture = this.shader.getUniformLocation("uDhDepthTexture");
		this.uInvMvmProj = this.shader.getUniformLocation("uInvMvmProj");
		
		this.uIsReverseZDepth = this.shader.getUniformLocation("uIsReverseZDepth");
		this.uDepthIsZeroToPositiveOne = this.shader.getUniformLocation("uDepthIsZeroToPositiveOne");
		
		// Fog uniforms
		this.uFogScale = this.shader.getUniformLocation("uFogScale");
		this.uFogVerticalScale = this.shader.getUniformLocation("uFogVerticalScale");
		this.uFogColor = this.shader.getUniformLocation("uFogColor");
		this.uFogDebugMode = this.shader.getUniformLocation("uFogDebugMode");
		this.uFogFalloffType = this.shader.getUniformLocation("uFogFalloffType");
		
		// fog config
		this.uFarFogStart = this.shader.getUniformLocation("uFarFogStart");
		this.uFarFogLength = this.shader.getUniformLocation("uFarFogLength");
		this.uFarFogMin = this.shader.getUniformLocation("uFarFogMin");
		this.uFarFogRange = this.shader.getUniformLocation("uFarFogRange");
		this.uFarFogDensity = this.shader.getUniformLocation("uFarFogDensity");
		
		// height fog
		this.uHeightFogStart = this.shader.getUniformLocation("uHeightFogStart");
		this.uHeightFogLength = this.shader.getUniformLocation("uHeightFogLength");
		this.uHeightFogMin = this.shader.getUniformLocation("uHeightFogMin");
		this.uHeightFogRange = this.shader.getUniformLocation("uHeightFogRange");
		this.uHeightFogDensity = this.shader.getUniformLocation("uHeightFogDensity");
		
		this.uHeightFogEnabled = this.shader.getUniformLocation("uHeightFogEnabled");
		this.uHeightFogFalloffType = this.shader.getUniformLocation("uHeightFogFalloffType");
		this.uHeightBasedOnCamera = this.shader.getUniformLocation("uHeightBasedOnCamera");
		this.uHeightFogBaseHeight = this.shader.getUniformLocation("uHeightFogBaseHeight");
		this.uHeightFogAppliesUp = this.shader.getUniformLocation("uHeightFogAppliesUp");
		this.uHeightFogAppliesDown = this.shader.getUniformLocation("uHeightFogAppliesDown");
		this.uUseSphericalFog = this.shader.getUniformLocation("uUseSphericalFog");
		this.uHeightFogMixingMode = this.shader.getUniformLocation("uHeightFogMixingMode");
		this.uCameraBlockYPos = this.shader.getUniformLocation("uCameraBlockYPos");
			
	}
	
	//endregion
	
	
	
	//=============//
	// render prep //
	//=============//
	//region
	
	@Override
	protected void onApplyUniforms(RenderParams renderParams)
	{
		int lodDrawDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * LodUtil.CHUNK_WIDTH;
		
		this.shader.setUniform(this.uInvMvmProj, this.inverseMvmProjMatrix);
		
		this.shader.setUniform(this.uIsReverseZDepth, (RENDER_DEF.getDepthDirection() == EDhApiDepthDirection.REVERSE_Z) ? 1 : 0);
		this.shader.setUniform(this.uDepthIsZeroToPositiveOne, (RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE) ? 1 : 0);
		
		
		// Fog uniforms
		this.shader.setUniform(this.uFogColor, this.fogRenderParams.getFogColor());
		this.shader.setUniform(this.uFogScale, 1.f / lodDrawDistance);
		this.shader.setUniform(this.uFogVerticalScale, 1.f / renderParams.clientLevelWrapper.getMaxHeight());
		this.shader.setUniform(this.uFogDebugMode, 0); // 0 = normal // 1 = render everything with fog color // 7 = use debug rendering
		this.shader.setUniform(this.uFogFalloffType, this.fogRenderParams.getFarFogFalloff().value);
		
		
		// fog config
		this.shader.setUniform(this.uFarFogStart, this.fogRenderParams.getFarFogStartPercent());
		this.shader.setUniform(this.uFarFogLength, this.fogRenderParams.getFarFogEndPercent() - this.fogRenderParams.getFarFogStartPercent());
		this.shader.setUniform(this.uFarFogMin, this.fogRenderParams.getFarFogMinThickness());
		this.shader.setUniform(this.uFarFogRange, this.fogRenderParams.getFarFogMaxThickness() - this.fogRenderParams.getFarFogMinThickness());
		this.shader.setUniform(this.uFarFogDensity, this.fogRenderParams.getFarFogDensity());
		
		
		// height config
		EDhApiHeightFogMixMode heightFogMixingMode = this.fogRenderParams.getHeightFogMixingMode();
		boolean heightFogEnabled = 
			heightFogMixingMode != EDhApiHeightFogMixMode.SPHERICAL 
			&& heightFogMixingMode != EDhApiHeightFogMixMode.CYLINDRICAL;
		boolean useSphericalFog = heightFogMixingMode == EDhApiHeightFogMixMode.SPHERICAL;
		EDhApiHeightFogDirection heightFogDirection = this.fogRenderParams.getHeightFogDirection();
		
		this.shader.setUniform(this.uHeightFogStart, this.fogRenderParams.getHeightFogStartPercent());
		this.shader.setUniform(this.uHeightFogLength, this.fogRenderParams.getHeightFogEndPercent() - this.fogRenderParams.getHeightFogStartPercent());
		this.shader.setUniform(this.uHeightFogMin, this.fogRenderParams.getFarFogMinThickness());
		this.shader.setUniform(this.uHeightFogRange, this.fogRenderParams.getFarFogMaxThickness() - this.fogRenderParams.getFarFogMinThickness());
		this.shader.setUniform(this.uHeightFogDensity, this.fogRenderParams.getFarFogDensity());
		
		
		this.shader.setUniform(this.uHeightFogEnabled, heightFogEnabled);
		this.shader.setUniform(this.uHeightFogFalloffType, this.fogRenderParams.getHeightFogFalloff().value);
		this.shader.setUniform(this.uHeightFogBaseHeight, this.fogRenderParams.getHeightFogBaseHeight());
		this.shader.setUniform(this.uHeightBasedOnCamera, heightFogDirection.basedOnCamera);
		this.shader.setUniform(this.uHeightFogAppliesUp, heightFogDirection.fogAppliesUp);
		this.shader.setUniform(this.uHeightFogAppliesDown, heightFogDirection.fogAppliesDown);
		this.shader.setUniform(this.uUseSphericalFog, useSphericalFog);
		this.shader.setUniform(this.uHeightFogMixingMode, heightFogMixingMode.value);
		this.shader.setUniform(this.uCameraBlockYPos, (float)renderParams.exactCameraPosition.y);
		
	}
	
	public void prepUniformObjects(DhApiMat4f modelViewProjectionMatrix, DhApiFogRenderParam fogRenderParams)
	{
		this.inverseMvmProjMatrix = new DhMat4f(modelViewProjectionMatrix);
		this.inverseMvmProjMatrix.invert();
		
		this.fogRenderParams = fogRenderParams;
	}
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	protected void onRender()
	{
		GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.frameBuffer);
		GLMC.disableScissorTest();
		GLMC.disableDepthTest();
		GLMC.disableBlend();
		
		GLMC.glActiveTexture(GL13.GL_TEXTURE0);
		GLMC.glBindTexture(GlDhMetaRenderer.INSTANCE.getActiveDepthTextureId());
		LWJGL.glUniform1i(this.uDhDepthTexture, 0);
		
		// this is necessary for MC 1.16 (IE Legacy OpenGL)
		// otherwise the framebuffer isn't cleared correctly and the fog smears across the screen
		if (MC_RENDER.runningLegacyOpenGL())
		{
			// in another part of the DH code we set the fog color to opaque, here it needs to be transparent
			float[] clearColorValues = new float[4];
			LWJGL.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColorValues);
			LWJGL.glClearColor(clearColorValues[0], clearColorValues[1], clearColorValues[2], 0.0f);
			
			LWJGL.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
		}
		
		
		GlScreenQuad.INSTANCE.render();
	}
	
	//endregion
	
	
}
