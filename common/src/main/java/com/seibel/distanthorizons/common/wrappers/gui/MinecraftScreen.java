package com.seibel.distanthorizons.common.wrappers.gui;

#if MC_VER <= MC_1_12_2
import org.lwjgl.opengl.Display;
#else
import com.mojang.blaze3d.platform.Window;
#endif

import com.seibel.distanthorizons.core.config.gui.AbstractScreen;

import net.minecraft.client.Minecraft;

#if MC_VER > MC_1_12_2
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
#endif

import org.jetbrains.annotations.NotNull;

#if MC_VER <= MC_1_12_2
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
#if MC_VER <= MC_1_7_10
import net.minecraft.util.StatCollector;
#endif
#elif MC_VER < MC_1_20_1
import com.mojang.blaze3d.vertex.PoseStack;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
#endif

import java.nio.file.Path;
import java.util.*;

public class MinecraftScreen
{
	#if MC_VER <= MC_1_12_2
	public static GuiScreen getScreen(GuiScreen parent, AbstractScreen screen, String translationName)
	#else
	public static Screen getScreen(Screen parent, AbstractScreen screen, String translationName)
	#endif
	{
		return new ConfigScreenRenderer(parent, screen, translationName);
	}
	
	
	
	//=========//
	// screens //
	//=========//
	//region
	
	private static class ConfigScreenRenderer extends DhScreen
	{
		#if MC_VER <= MC_1_12_2
		private final GuiScreen parent;
		#else
		private final Screen parent;
		#endif
		private ConfigListWidget configListWidget;
		private AbstractScreen screen;
		
		#if MC_VER <= MC_1_7_10
		public static String translate(String str, Object... args)
		{ return StatCollector.translateToLocalFormatted(str, args); }
		#elif MC_VER <= MC_1_12_2
		public static net.minecraft.util.text.TextComponentTranslation translate(String str, Object... args)
		{ return new net.minecraft.util.text.TextComponentTranslation(str, args); }
		#elif MC_VER < MC_1_19_2
		public static net.minecraft.network.chat.TranslatableComponent translate(String str, Object... args)
		{ return new net.minecraft.network.chat.TranslatableComponent(str, args); }
		#else
		public static net.minecraft.network.chat.MutableComponent translate(String str, Object... args)
		{ return net.minecraft.network.chat.Component.translatable(str, args); }
        #endif
		
		#if MC_VER <= MC_1_12_2
		protected ConfigScreenRenderer(GuiScreen parent, AbstractScreen screen, String translationName)
		#else
		protected ConfigScreenRenderer(Screen parent, AbstractScreen screen, String translationName)
		#endif
		{
			super(translate(translationName));
			this.parent = parent;
			this.screen = screen;
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public void initGui()	
		#else
		protected void init()
		#endif
		{
			#if MC_VER <= MC_1_12_2
			super.initGui();
			#else
			super.init();
			#endif
			
			#if MC_VER <= MC_1_12_2
			this.screen.width = Display.getWidth();
			this.screen.height = Display.getHeight();
			#else
			Window mcWindow = this.minecraft.getWindow();
			this.screen.width = mcWindow.getWidth();
			this.screen.height = mcWindow.getHeight();
			#endif
			this.screen.scaledWidth = this.width;
			this.screen.scaledHeight = this.height;
			this.screen.init(); // Init our own config screen
			
			#if MC_VER <= MC_1_12_2
			this.configListWidget = new ConfigListWidget(this.mc, this.width, this.height, 0, 0, 25); // Select the area to tint
			#else
			this.configListWidget = new ConfigListWidget(this.minecraft, this.width, this.height, 0, 0, 25); // Select the area to tint
			#endif
			
			#if MC_VER <= MC_1_12_2
			#elif MC_VER < MC_1_20_6 // no background is rendered in MC 1.20.6+
			if (this.minecraft != null && this.minecraft.level != null) // Check if in game
			{
				this.configListWidget.setRenderBackground(false); // Disable from rendering
			}
			#endif
			
			#if MC_VER > MC_1_12_2
			this.addWidget(this.configListWidget); // Add the tint to the things to be rendered
			#endif
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public void drawScreen(int mouseX, int mouseY, float delta)	
        #elif MC_VER < MC_1_20_1
		public void render(PoseStack matrices, int mouseX, int mouseY, float delta)
        #elif MC_VER <= MC_1_21_11
		public void render(GuiGraphics matrices, int mouseX, int mouseY, float delta)
        #else
		public void extractRenderState(GuiGraphicsExtractor matrices, int mouseX, int mouseY, float delta)
        #endif
		{
			#if MC_VER <= MC_1_12_2
			this.drawDefaultBackground();
			#elif MC_VER < MC_1_20_2
			this.renderBackground(matrices); // Render background
			#elif MC_VER < MC_1_21_6
			this.renderBackground(matrices, mouseX, mouseY, delta); // Render background
			#else
			// background blur is already being rendered, rendering again causes the game to crash
			#endif
			
			#if MC_VER <= MC_1_12_2
			this.configListWidget.drawScreen(mouseX, mouseY, delta);
			#elif MC_VER <= MC_1_21_11
			this.configListWidget.render(matrices, mouseX, mouseY, delta); // Renders the items in the render list (currently only used to tint background darker)
			#else
			this.configListWidget.extractRenderState(matrices, mouseX, mouseY, delta); // Renders the items in the render list (currently only used to tint background darker)
			#endif
		    
			this.screen.mouseX = mouseX;
			this.screen.mouseY = mouseY;
			this.screen.render(delta); // Render everything on the main screen
			
			#if MC_VER <= MC_1_12_2
			super.drawScreen(mouseX, mouseY, delta); // Render the vanilla stuff (currently only used for the background and tint)
			#elif MC_VER <= MC_1_21_11
			super.render(matrices, mouseX, mouseY, delta); // Render the vanilla stuff (currently only used for the background and tint)
			#else
			super.extractRenderState(matrices, mouseX, mouseY, delta); // Renders the items in the render list (currently only used to tint background darker)
			#endif
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public void setWorldAndResolution(Minecraft mc, int width, int height)
		#elif MC_VER <= MC_1_21_10
		public void resize(Minecraft mc, int width, int height)
		#else
		public void resize(int width, int height)
		#endif
		{
			// Resize Minecraft's screen
			#if MC_VER <= MC_1_12_2
			super.setWorldAndResolution(mc, width, height);
			#elif MC_VER <= MC_1_21_10
			super.resize(mc, width, height);
			#else
			super.resize(width, height);
			#endif
			
			#if MC_VER <= MC_1_12_2
			this.screen.width = Display.getWidth();
			this.screen.height = Display.getHeight();
			#else
			Window mcWindow = this.minecraft.getWindow();
			this.screen.width = mcWindow.getWidth();
			this.screen.height = mcWindow.getHeight();
			#endif
			this.screen.scaledWidth = this.width;
			this.screen.scaledHeight = this.height;
			this.screen.onResize(); // Resize our screen
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public void updateScreen()
		#else
		public void tick()
		#endif
		{
			#if MC_VER <= MC_1_12_2
			super.updateScreen(); // Tick Minecraft's screen
			#else
			super.tick(); // Tick Minecraft's screen
			#endif
			
			this.screen.tick(); // Tick our screen
			if (this.screen.close) // If we decide to close the screen, then actually close the screen
			{
				#if MC_VER <= MC_1_12_2
				this.onGuiClosed();
				#else
				this.onClose();
				#endif
			}
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public void onGuiClosed()
		#else
		public void onClose()
		#endif
		{
			this.screen.onClose(); // Close our screen
			DhScreenUtil.setScreen(this.parent); // Goto the parent screen
		}
		
		#if MC_VER > MC_1_12_2
		@Override
		public void onFilesDrop(@NotNull List<Path> files)
		{ this.screen.onFilesDrop(files); }
		
		// For checking if it should close when you press the escape key
		@Override
		public boolean shouldCloseOnEsc()
		{ return this.screen.shouldCloseOnEsc; }
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	public static class ConfigListWidget extends GuiListExtended
	#else
	public static class ConfigListWidget extends ContainerObjectSelectionList
	#endif
	{
		public ConfigListWidget(Minecraft minecraftClient, int canvasWidth, int canvasHeight, int topMargin, int botMargin, int itemSpacing)
		{
			#if MC_VER < MC_1_20_4
			super(minecraftClient, canvasWidth, canvasHeight, topMargin, canvasHeight - botMargin, itemSpacing);
			#else
			super(minecraftClient, canvasWidth, canvasHeight - (topMargin + botMargin), topMargin, itemSpacing);
			#endif
			
			#if MC_VER <= MC_1_7_10
			this.field_148163_i = false;
			#else
			this.centerListVertically = false;
			#endif
		}
		
		#if MC_VER <= MC_1_12_2
		@Override
		protected int getSize()
		{
			return 0;
		}
		@Override
		public IGuiListEntry getListEntry(int index)
		{
			return null;
		}
		#endif
	}
	
	//endregion
	
	
	
}
