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

package com.seibel.distanthorizons.forge112.mixins.client;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.forge112.ForgeMain;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.Objects;

import static com.seibel.distanthorizons.forge112.RenderHelper.getModelViewMatrix;
import static com.seibel.distanthorizons.forge112.RenderHelper.getProjectionMatrix;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

@Mixin(value = RenderGlobal.class, priority = 900)
public class MixinRenderGlobal
{
	@Shadow private WorldClient world;
	
	@Unique private static final boolean DEBUG_GL_STATE = false;
	
	@Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"), cancellable = true)
	private void renderChunkLayerHead(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir)
	{
		// Cancelling CUTOUT RenderLayer will cause crash
		if (Config.Client.Advanced.Debugging.lodOnlyMode.get() && blockLayerIn != BlockRenderLayer.CUTOUT)
		{
			cir.cancel();
		}
		
		if (blockLayerIn == BlockRenderLayer.SOLID)
		{
			distantHorizons$captureRenderState((float) partialTicks);
			GLStateSnapshot before = DEBUG_GL_STATE ? GLStateSnapshot.capture() : null;
			
			ClientApi.INSTANCE.renderLods();
			distantHorizons$unbindBuffers();
			
			if (DEBUG_GL_STATE)
			{
				GLStateSnapshot after = GLStateSnapshot.capture();
				GLStateSnapshot.diffAndPrint("renderLods() [SOLID]", before, after);
			}
		}
		else if (blockLayerIn == BlockRenderLayer.TRANSLUCENT)
		{
			GlStateManager.depthMask(true); // Water will be rendered black otherwise
		}
	}
	
	@Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("TAIL"))
	private void renderChunkLayerTail(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir)
	{
		if (blockLayerIn == BlockRenderLayer.SOLID)
		{
			distantHorizons$captureRenderState((float) partialTicks);
			GLStateSnapshot before = DEBUG_GL_STATE ? GLStateSnapshot.capture() : null;
			
			if (ForgeMain.IRIS_ACCESSOR == null)
			{
				GlStateManager.disableAlpha();
			}
			
			ClientApi.INSTANCE.renderFadeOpaque();
			
			if (ForgeMain.IRIS_ACCESSOR == null)
			{
				GlStateManager.enableAlpha();
			}
			
			GlStateManager.depthFunc(GL11.GL_LEQUAL);
			
			if (DEBUG_GL_STATE)
			{
				GLStateSnapshot after = GLStateSnapshot.capture();
				GLStateSnapshot.diffAndPrint("FADE SOL() [SOLID]", before, after);
			}
		}
		else if (blockLayerIn == BlockRenderLayer.TRANSLUCENT)
		{
			distantHorizons$captureRenderState((float) partialTicks);
			GLStateSnapshot before = DEBUG_GL_STATE ? GLStateSnapshot.capture() : null;
			
			if (ForgeMain.IRIS_ACCESSOR == null)
			{
				GlStateManager.disableAlpha();
			}
			
			ClientApi.INSTANCE.renderFadeTransparent();
			
			if (ForgeMain.IRIS_ACCESSOR == null)
			{
				GlStateManager.enableAlpha();
			}
			
			GlStateManager.depthFunc(GL11.GL_LEQUAL);
			
			if (DEBUG_GL_STATE)
			{
				GLStateSnapshot after = GLStateSnapshot.capture();
				GLStateSnapshot.diffAndPrint("FADE TRAN() [TRANSLUCENT]", before, after);
			}
		}
	}
	
	@Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/EntityRenderer;enableLightmap()V", shift = At.Shift.AFTER))
	void renderDeferredLodsDuringTranslucentSetup(BlockRenderLayer blockLayerIn, CallbackInfo ci)
	{
		if (ForgeMain.IRIS_ACCESSOR == null)
		{
			return;
		}
		
		if (blockLayerIn == BlockRenderLayer.TRANSLUCENT)
		{
			distantHorizons$captureRenderState(MinecraftRenderWrapper.INSTANCE.getPartialTickTime());
			GLStateSnapshot before = DEBUG_GL_STATE ? GLStateSnapshot.capture() : null;
			
			ClientApi.INSTANCE.renderDeferredLodsForShaders();
			
			if (DEBUG_GL_STATE)
			{
				GLStateSnapshot after = GLStateSnapshot.capture();
				GLStateSnapshot.diffAndPrint("renderDeferredLodsForShaders() [TRANSLUCENT]", before, after);
			}
		}
	}
	
	@Unique
	private void distantHorizons$captureRenderState(float partialTicks)
	{
		ClientApi.RENDER_STATE.mcModelViewMatrix = getModelViewMatrix();
		ClientApi.RENDER_STATE.mcProjectionMatrix = getProjectionMatrix();
		
		ClientApi.RENDER_STATE.partialTickTime = partialTicks;
		ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.world);
	}
	
	@Unique
	private static void distantHorizons$unbindBuffers()
	{
		//Some 1.12.2 rendering mods breaks if we don't unbind buffers
		LWJGL.glBindVertexArray(0);
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
		LWJGL.glUseProgram(0);
	}
	
	private static class GLStateSnapshot
	{
		// ---- depth ----
		boolean depthTest;
		boolean depthMask;
		int depthFunc;
		
		// ---- blend ----
		boolean blend;
		int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
		int blendEquationRgb, blendEquationAlpha;
		
		// ---- stencil ----
		boolean stencilTest;
		int stencilFunc, stencilValueMask, stencilRef;
		int stencilWriteMask;
		int stencilFail, stencilPassDepthFail, stencilPassDepthPass;
		
		// ---- color mask ----
		boolean colorMaskR, colorMaskG, colorMaskB, colorMaskA;
		
		// ---- culling ----
		boolean cullFace;
		int cullFaceMode;
		int frontFace;
		
		// ---- scissor ----
		boolean scissorTest;
		int[] scissorBox = new int[4];
		
		// ---- viewport (rarely corrupted but cheap to check) ----
		int[] viewport = new int[4];
		
		// ---- texture / program bindings ----
		int activeTexture;
		int textureBinding2D;
		int currentProgram;
		
		// ---- buffer bindings (mixin already resets these, but verify) ----
		int arrayBufferBinding;
		int elementArrayBufferBinding;
		int vertexArrayBinding;
		
		// ---- polygon mode (wireframe leaks are a classic one too) ----
		int polygonModeFront;
		int polygonModeBack;
		
		static GLStateSnapshot capture()
		{
			GLStateSnapshot s = new GLStateSnapshot();
			
			s.depthTest = LWJGL.glGetBoolean(GL11.GL_DEPTH_TEST);
			s.depthMask = LWJGL.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
			s.depthFunc = LWJGL.glGetInteger(GL11.GL_DEPTH_FUNC);
			
			s.blend = LWJGL.glGetBoolean(GL11.GL_BLEND);
			s.blendSrcRgb = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_RGB);
			s.blendDstRgb = LWJGL.glGetInteger(GL14.GL_BLEND_DST_RGB);
			s.blendSrcAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
			s.blendDstAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
			s.blendEquationRgb = LWJGL.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
			s.blendEquationAlpha = LWJGL.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
			
			s.stencilTest = LWJGL.glGetBoolean(GL11.GL_STENCIL_TEST);
			s.stencilFunc = LWJGL.glGetInteger(GL11.GL_STENCIL_FUNC);
			s.stencilValueMask = LWJGL.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
			s.stencilRef = LWJGL.glGetInteger(GL11.GL_STENCIL_REF);
			s.stencilWriteMask = LWJGL.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
			s.stencilFail = LWJGL.glGetInteger(GL11.GL_STENCIL_FAIL);
			s.stencilPassDepthFail = LWJGL.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
			s.stencilPassDepthPass = LWJGL.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
			
			s.cullFace = LWJGL.glGetBoolean(GL11.GL_CULL_FACE);
			s.cullFaceMode = LWJGL.glGetInteger(GL11.GL_CULL_FACE_MODE);
			s.frontFace = LWJGL.glGetInteger(GL11.GL_FRONT_FACE);
			
			s.scissorTest = LWJGL.glGetBoolean(GL11.GL_SCISSOR_TEST);
			LWJGL.glGetIntegerv(GL11.GL_SCISSOR_BOX, s.scissorBox);
			
			LWJGL.glGetIntegerv(GL11.GL_VIEWPORT, s.viewport);
			
			s.activeTexture = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			s.textureBinding2D = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			s.currentProgram = LWJGL.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			
			s.arrayBufferBinding = LWJGL.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
			s.elementArrayBufferBinding = LWJGL.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
			s.vertexArrayBinding = LWJGL.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
			
			int[] polyMode = new int[2];
			LWJGL.glGetIntegerv(GL11.GL_POLYGON_MODE, polyMode);
			s.polygonModeFront = polyMode[0];
			s.polygonModeBack = polyMode[1];
			
			return s;
		}
		
		/** Prints every field that differs between before/after. Returns true if anything changed. */
		static boolean diffAndPrint(String label, GLStateSnapshot before, GLStateSnapshot after)
		{
			StringBuilder sb = new StringBuilder();
			sb.append("[DH-GLDIFF] ").append(label).append(":\n");
			boolean changed = false;
			
			changed |= field(sb, "depthTest", before.depthTest, after.depthTest);
			changed |= field(sb, "depthMask", before.depthMask, after.depthMask);
			changed |= field(sb, "depthFunc", glName(before.depthFunc), glName(after.depthFunc));
			
			changed |= field(sb, "blend", before.blend, after.blend);
			changed |= field(sb, "blendSrcRgb", glName(before.blendSrcRgb), glName(after.blendSrcRgb));
			changed |= field(sb, "blendDstRgb", glName(before.blendDstRgb), glName(after.blendDstRgb));
			changed |= field(sb, "blendSrcAlpha", glName(before.blendSrcAlpha), glName(after.blendSrcAlpha));
			changed |= field(sb, "blendDstAlpha", glName(before.blendDstAlpha), glName(after.blendDstAlpha));
			changed |= field(sb, "blendEquationRgb", glName(before.blendEquationRgb), glName(after.blendEquationRgb));
			changed |= field(sb, "blendEquationAlpha", glName(before.blendEquationAlpha), glName(after.blendEquationAlpha));
			
			changed |= field(sb, "stencilTest", before.stencilTest, after.stencilTest);
			changed |= field(sb, "stencilFunc", glName(before.stencilFunc), glName(after.stencilFunc));
			changed |= field(sb, "stencilValueMask", before.stencilValueMask, after.stencilValueMask);
			changed |= field(sb, "stencilRef", before.stencilRef, after.stencilRef);
			changed |= field(sb, "stencilWriteMask", before.stencilWriteMask, after.stencilWriteMask);
			changed |= field(sb, "stencilFail", glName(before.stencilFail), glName(after.stencilFail));
			changed |= field(sb, "stencilPassDepthFail", glName(before.stencilPassDepthFail), glName(after.stencilPassDepthFail));
			changed |= field(sb, "stencilPassDepthPass", glName(before.stencilPassDepthPass), glName(after.stencilPassDepthPass));
			
			changed |= field(sb, "colorMaskR", before.colorMaskR, after.colorMaskR);
			changed |= field(sb, "colorMaskG", before.colorMaskG, after.colorMaskG);
			changed |= field(sb, "colorMaskB", before.colorMaskB, after.colorMaskB);
			changed |= field(sb, "colorMaskA", before.colorMaskA, after.colorMaskA);
			
			changed |= field(sb, "cullFace", before.cullFace, after.cullFace);
			changed |= field(sb, "cullFaceMode", glName(before.cullFaceMode), glName(after.cullFaceMode));
			changed |= field(sb, "frontFace", glName(before.frontFace), glName(after.frontFace));
			
			changed |= field(sb, "scissorTest", before.scissorTest, after.scissorTest);
			changed |= field(sb, "scissorBox", Arrays.toString(before.scissorBox), Arrays.toString(after.scissorBox));
			
			changed |= field(sb, "viewport", Arrays.toString(before.viewport), Arrays.toString(after.viewport));
			
			changed |= field(sb, "activeTexture", before.activeTexture, after.activeTexture);
			changed |= field(sb, "textureBinding2D", before.textureBinding2D, after.textureBinding2D);
			changed |= field(sb, "currentProgram", before.currentProgram, after.currentProgram);
			
			changed |= field(sb, "arrayBufferBinding", before.arrayBufferBinding, after.arrayBufferBinding);
			changed |= field(sb, "elementArrayBufferBinding", before.elementArrayBufferBinding, after.elementArrayBufferBinding);
			changed |= field(sb, "vertexArrayBinding", before.vertexArrayBinding, after.vertexArrayBinding);
			
			changed |= field(sb, "polygonModeFront", glName(before.polygonModeFront), glName(after.polygonModeFront));
			changed |= field(sb, "polygonModeBack", glName(before.polygonModeBack), glName(after.polygonModeBack));
			
			if (changed)
			{
				System.out.println(sb.toString());
			}
			return changed;
		}
		
		private static boolean field(StringBuilder sb, String name, Object before, Object after)
		{
			if (!Objects.equals(before, after))
			{
				sb.append("    ").append(name).append(": ").append(before).append("  ->  ").append(after).append("\n");
				return true;
			}
			return false;
		}
		
		/** Best-effort readable name for common GL enum values so the log isn't just raw ints. */
		private static String glName(int value)
		{
			switch (value)
			{
				case GL11.GL_NEVER:
					return "GL_NEVER";
				case GL11.GL_LESS:
					return "GL_LESS";
				case GL11.GL_EQUAL:
					return "GL_EQUAL";
				case GL11.GL_LEQUAL:
					return "GL_LEQUAL";
				case GL11.GL_GREATER:
					return "GL_GREATER";
				case GL11.GL_NOTEQUAL:
					return "GL_NOTEQUAL";
				case GL11.GL_GEQUAL:
					return "GL_GEQUAL";
				case GL11.GL_ALWAYS:
					return "GL_ALWAYS";
				case GL11.GL_ZERO:
					return "GL_ZERO";
				case GL11.GL_ONE:
					return "GL_ONE";
				case GL11.GL_SRC_COLOR:
					return "GL_SRC_COLOR";
				case GL11.GL_ONE_MINUS_SRC_COLOR:
					return "GL_ONE_MINUS_SRC_COLOR";
				case GL11.GL_SRC_ALPHA:
					return "GL_SRC_ALPHA";
				case GL11.GL_ONE_MINUS_SRC_ALPHA:
					return "GL_ONE_MINUS_SRC_ALPHA";
				case GL11.GL_DST_ALPHA:
					return "GL_DST_ALPHA";
				case GL11.GL_ONE_MINUS_DST_ALPHA:
					return "GL_ONE_MINUS_DST_ALPHA";
				case GL11.GL_DST_COLOR:
					return "GL_DST_COLOR";
				case GL11.GL_ONE_MINUS_DST_COLOR:
					return "GL_ONE_MINUS_DST_COLOR";
				case GL14.GL_MIN:
					return "GL_MIN";
				case GL14.GL_MAX:
					return "GL_MAX";
				case GL11.GL_KEEP:
					return "GL_KEEP";
				case GL11.GL_REPLACE:
					return "GL_REPLACE";
				case GL11.GL_INCR:
					return "GL_INCR";
				case GL11.GL_DECR:
					return "GL_DECR";
				case GL11.GL_INVERT:
					return "GL_INVERT";
				case GL11.GL_FRONT:
					return "GL_FRONT";
				case GL11.GL_BACK:
					return "GL_BACK";
				case GL11.GL_FRONT_AND_BACK:
					return "GL_FRONT_AND_BACK";
				case GL11.GL_CW:
					return "GL_CW";
				case GL11.GL_CCW:
					return "GL_CCW";
				case GL11.GL_FILL:
					return "GL_FILL";
				case GL11.GL_LINE:
					return "GL_LINE";
				case GL11.GL_POINT:
					return "GL_POINT";
				default:
					return "0x" + Integer.toHexString(value);
			}
		}
		
	}
	
}