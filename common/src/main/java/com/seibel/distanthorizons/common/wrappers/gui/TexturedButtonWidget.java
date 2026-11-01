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

package com.seibel.distanthorizons.common.wrappers.gui;

#if MC_VER > MC_1_12_2
import net.minecraft.network.chat.Component;
#endif

#if MC_VER >= MC_1_17_1
import net.minecraft.client.gui.components.Button;
#endif

#if MC_VER <= MC_1_7_10
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.OpenGlHelper;
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;
#elif MC_VER <= MC_1_12_2
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
#elif MC_VER <= MC_1_16_5
import net.minecraft.client.gui.components.ImageButton;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
#elif MC_VER < MC_1_20_1
import net.minecraft.client.gui.components.ImageButton;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
#elif MC_VER < MC_1_20_2
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.GuiGraphics;
#elif MC_VER < MC_1_21_6
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
#elif MC_VER <= MC_1_21_10
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderPipelines;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
#endif

#if MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

/**
 * Creates a button with a texture on it (and a background) that works with all mc versions
 *
 * @author coolGi
 * @version 2023-10-03
 */
@SuppressWarnings({ "RedundantSuppression", "deprecation"}) // we use a few deprecated Mojang functions (as expected when running on old MC versions)
public class TexturedButtonWidget 
	#if MC_VER <= MC_1_12_2
	extends GuiButton
	#elif MC_VER < MC_1_20_2
	extends ImageButton
	#else
	extends Button
	#endif
{
	public final boolean renderBackground;
	
	private final int u;
	private final int v;
	private final int hoveredVOffset;
	
	private final int textureWidth;
	private final int textureHeight;
	
	#if MC_VER <= MC_1_21_10
	private final ResourceLocation textureResourceLocation;
	#else
	private final Identifier textureResourceLocation;
	#endif
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	#if MC_VER <= MC_1_12_2
	public TexturedButtonWidget(int id, int x, int y, int width, int height, int u, int v, int hoveredVOffset, ResourceLocation textureResourceLocation, int textureWidth, int textureHeight, String text)
	{ this(id, x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, text, true); }
	#elif MC_VER <= MC_1_21_10
	public TexturedButtonWidget(int x, int y, int width, int height, int u, int v, int hoveredVOffset, ResourceLocation textureResourceLocation, int textureWidth, int textureHeight, OnPress pressAction, Component text) 
	{ this(x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, pressAction, text, true); }
	#else
	public TexturedButtonWidget(int x, int y, int width, int height, int u, int v, int hoveredVOffset, Identifier textureResourceLocation, int textureWidth, int textureHeight, OnPress pressAction, Component text)
	{ this(x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, pressAction, text, true); }
	#endif
	
	#if MC_VER <= MC_1_12_2
	public TexturedButtonWidget(int id, int x, int y, int width, int height, int u, int v, int hoveredVOffset, ResourceLocation textureResourceLocation, int textureWidth, int textureHeight, String text, boolean renderBackground)
	#elif MC_VER <= MC_1_21_10
	public TexturedButtonWidget(int x, int y, int width, int height, int u, int v, int hoveredVOffset, ResourceLocation textureResourceLocation, int textureWidth, int textureHeight, OnPress pressAction, Component text, boolean renderBackground)
	#else
	public TexturedButtonWidget(int x, int y, int width, int height, int u, int v, int hoveredVOffset, Identifier textureResourceLocation, int textureWidth, int textureHeight, OnPress pressAction, Component text, boolean renderBackground)
	#endif
	{
		#if MC_VER <= MC_1_12_2
		super(id, x, y, width, height, text);
		#elif MC_VER < MC_1_20_2
		super(x, y, width, height, u, v, hoveredVOffset, textureResourceLocation, textureWidth, textureHeight, pressAction, text);
		#else
		// We don't pass in the text option since it will render (we normally pass it in for narration)
		super(x, y, width, height, Component.empty(), pressAction, DEFAULT_NARRATION);
		#endif

		this.u = u;
		this.v = v;
		this.hoveredVOffset = hoveredVOffset;
		
		this.textureResourceLocation = textureResourceLocation;
		
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
		
		this.renderBackground = renderBackground;
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	#if MC_VER <= MC_1_7_10
	@Override
	public void drawButton(Minecraft mc, int mouseX, int mouseY)
	{
		if (this.visible)
		{
			this.hovered =
				mouseX >= this.xPosition
					&& mouseY >= this.yPosition
					&& mouseX < this.xPosition + this.width
					&& mouseY < this.yPosition + this.height;
			int hoverState = this.getHoverState(this.hovered);
			
			LWJGL.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
			LWJGL.glEnable(GL11.GL_BLEND);
			OpenGlHelper.glBlendFunc(770, 771, 1, 0);
			LWJGL.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			
			if (this.renderBackground)
			{
				mc.getTextureManager().bindTexture(buttonTextures);
				this.drawTexturedModalRect(
					this.xPosition, this.yPosition,
					0, 46 + hoverState * 20,
					this.width / 2, this.height);
				this.drawTexturedModalRect(
					this.xPosition + this.width / 2, this.yPosition,
					200 - this.width / 2, 46 + hoverState * 20,
					this.width / 2, this.height);
			}
			
			mc.getTextureManager().bindTexture(this.textureResourceLocation);
			drawModalRectWithCustomSizedTexture(this.xPosition, this.yPosition, this.u, this.v + (this.hoveredVOffset * this.getIconHoverState(this.hovered)), this.width, this.height, this.textureWidth, this.textureHeight);
		}
	}
	private int getIconHoverState(boolean mouseOver)
	{
		if (!this.enabled || mouseOver)
		{
			return 1;
		}
		return 0;
	}
	
	#elif MC_VER <= MC_1_12_2
	public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks)
	{
		if (this.visible)
		{
			this.hovered =
				mouseX >= this.x && mouseX < this.x + this.width
				&& mouseY >= this.y && mouseY < this.y + this.height;
			int i = this.getHoverState(this.hovered);
			
			GlStateManager.enableBlend();
			GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
			GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
			GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
			
			if (this.renderBackground)
			{
				// Render vanilla background
				mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
				this.drawTexturedModalRect(this.x, this.y, 0, 46 + i * 20, this.width / 2, this.height);
				this.drawTexturedModalRect(this.x + this.width / 2, this.y, 200 - this.width / 2, 46 + i * 20, this.width / 2, this.height);
			}
			
			// Render DH texture
			mc.getTextureManager().bindTexture(this.textureResourceLocation);
			drawModalRectWithCustomSizedTexture(this.x, this.y, this.u, (this.hoveredVOffset * (i - 1)), this.width, this.height, this.textureWidth, this.textureHeight);
		}
	}
	
	#elif MC_VER <= MC_1_16_5
	@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground) // Renders the background of the button
		{
			Minecraft.getInstance().getTextureManager().bind(WIDGETS_LOCATION);
			RenderSystem.color4f(1.0F, 1.0F, 1.0F, this.alpha);
			
			int i = this.getYImage(this.isHovered);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			this.blit(matrices, this.x, this.y, 0, 46 + i * 20, this.width / 2, this.height);
			this.blit(matrices, this.x + this.width / 2, this.y, 200 - this.width / 2, 46 + i * 20, this.width / 2, this.height);
		}
		
		super.renderButton(matrices, mouseX, mouseY, delta);
	}
	
	#elif MC_VER <= MC_1_19_2
	@Override
	public void renderButton(PoseStack matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground) // Renders the background of the button
		{
			RenderSystem.setShader(GameRenderer::getPositionTexShader);
			RenderSystem.setShaderTexture(0, WIDGETS_LOCATION);
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
			
			int i = this.getYImage(this.isHovered);
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			this.blit(matrices, this.x, this.y, 0, 46 + i * 20, this.width / 2, this.height);
			this.blit(matrices, this.x + this.width / 2, this.y, 200 - this.width / 2, 46 + i * 20, this.width / 2, this.height);
		}
		
		super.renderButton(matrices, mouseX, mouseY, delta);
	}
	
	#elif MC_VER <= MC_1_19_4
	@Override
	public void renderWidget(PoseStack matrices, int mouseX, int mouseY, float delta)
	{
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.setShaderTexture(0, WIDGETS_LOCATION);
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, this.alpha);
		
		if (this.renderBackground) // Renders the background of the button
		{
			int i = 1;
			if (!this.active)           { i = 0; }
			else if (this.isHovered)    { i = 2; }
			
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			
			this.blit(matrices, this.getX(), this.getY(), 0, 46 + i * 20, this.getWidth() / 2, this.getHeight());
			this.blit(matrices, this.getX() + this.getWidth() / 2, this.getY(), 200 - this.width / 2, 46 + i * 20, this.getWidth() / 2, this.getHeight());
		}
		
		super.renderWidget(matrices, mouseX, mouseY, delta);
	}
	
	#elif MC_VER <= MC_1_20_1
	@Override
	public void renderWidget(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground) // Renders the background of the button
		{
			int i = 1;
			if (!this.active)           { i = 0; }
			else if (this.isHovered)    { i = 2; }
			
			matrices.blit(WIDGETS_LOCATION, this.getX(), this.getY(), 0, 46 + i * 20, this.getWidth() / 2, this.getHeight());
			matrices.blit(WIDGETS_LOCATION, this.getX() + this.getWidth() / 2, this.getY(), 200 - this.width / 2, 46 + i * 20, this.getWidth() / 2, this.getHeight());
		}
		
		super.renderWidget(matrices, mouseX, mouseY, delta);
	}
	
	#elif MC_VER <= MC_1_21_1
	@Override
	public void renderWidget(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground)
		{
			matrices.blitSprite(SPRITES.get(this.active, this.isHoveredOrFocused()), this.getX(), this.getY(), this.getWidth(), this.getHeight());
		}
		
		// Renders the sprite
		int i = 0;
		if (!this.active)        { i = 2; }
		else if (this.isHovered) { i = 1; }
		
		matrices.blit(this.textureResourceLocation, this.getX(), this.getY(), this.u, this.v + (this.hoveredVOffset * i), this.width, this.height, this.textureWidth, this.textureHeight);
	}
	
	#elif MC_VER <= MC_1_21_5
	@Override
	public void renderWidget(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground)
		{
			matrices.blitSprite(
					RenderType::guiTextured,
					SPRITES.get(this.active, this.isHoveredOrFocused()),
					this.getX(), this.getY(),
					this.getWidth(), this.getHeight());
		}
		
		// Renders the sprite
		int i = 0;
		if (!this.active)        { i = 2; }
		else if (this.isHovered) { i = 1; }
		
		matrices.blit(
				RenderType::guiTextured,
				this.textureResourceLocation,
				this.getX(), this.getY(),
				this.u, this.v + (this.hoveredVOffset * i),
				this.width, this.height,
				this.textureWidth, this.textureHeight);
	}
	
	#elif MC_VER <= MC_1_21_10
	@Override
	public void renderWidget(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground)
		{
			matrices.blitSprite(
					RenderPipelines.GUI_TEXTURED,
					SPRITES.get(this.active, this.isHoveredOrFocused()),
					this.getX(), this.getY(),
					this.getWidth(), this.getHeight());
		}
		
		// Renders the sprite
		int i = 0;
		if (!this.active)        { i = 2; }
		else if (this.isHovered) { i = 1; }
		
		matrices.blit(
				RenderPipelines.GUI_TEXTURED,
				this.textureResourceLocation,
				this.getX(), this.getY(),
				this.u, this.v + (this.hoveredVOffset * i),
				this.width, this.height,
				this.textureWidth, this.textureHeight);
	}
	
	#elif MC_VER <= MC_1_21_11
	@Override
	protected void renderContents(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground)
		{
			matrices.blitSprite(
				RenderPipelines.GUI_TEXTURED,
				SPRITES.get(this.active, this.isHoveredOrFocused()),
				this.getX(), this.getY(),
				this.getWidth(), this.getHeight());
		}
		
		// Renders the sprite
		int i = 0;
		if (!this.active)        { i = 2; }
		else if (this.isHovered) { i = 1; }
		
		matrices.blit(
			RenderPipelines.GUI_TEXTURED,
			this.textureResourceLocation,
			this.getX(), this.getY(),
			this.u, this.v + (this.hoveredVOffset * i),
			this.width, this.height,
			this.textureWidth, this.textureHeight);
	}
	
	#else
	@Override
	protected void extractContents(GuiGraphicsExtractor matrices, int mouseX, int mouseY, float delta)
	{
		if (this.renderBackground)
		{
			matrices.blitSprite(
					RenderPipelines.GUI_TEXTURED,
					SPRITES.get(this.active, this.isHoveredOrFocused()),
					this.getX(), this.getY(),
					this.getWidth(), this.getHeight());
		}
		
		// Renders the sprite
		int i = 0;
		if (!this.active)        { i = 2; }
		else if (this.isHovered) { i = 1; }
		
		matrices.blit(
				RenderPipelines.GUI_TEXTURED,
				this.textureResourceLocation,
				this.getX(), this.getY(),
				this.u, this.v + (this.hoveredVOffset * i),
				this.width, this.height,
				this.textureWidth, this.textureHeight);
	}
	#endif
	
	//endregion
	
	
	
}
