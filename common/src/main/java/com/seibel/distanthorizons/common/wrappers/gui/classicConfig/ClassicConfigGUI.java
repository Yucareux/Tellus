package com.seibel.distanthorizons.common.wrappers.gui.classicConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.seibel.distanthorizons.core.config.types.*;

import com.seibel.distanthorizons.core.config.types.enums.EConfigCommentTextPosition;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.config.IConfigGui;
import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_12_2
import net.minecraft.client.gui.*;
import net.minecraft.world.World;
#if MC_VER > MC_1_7_10
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.text.ITextComponent;
#endif
#else
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
#endif
import com.seibel.distanthorizons.core.logging.DhLogger;

import org.jetbrains.annotations.NotNull;

#if MC_VER <= MC_1_7_10
import net.minecraft.client.renderer.Tessellator;
#elif MC_VER <= MC_1_12_2
#elif MC_VER < MC_1_20_1
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
#endif

#if MC_VER >= MC_1_17_1
import net.minecraft.client.gui.narration.NarratableEntry;
#endif

#if MC_VER <= MC_1_21_10
#else
import net.minecraft.resources.Identifier;
#endif

import static com.seibel.distanthorizons.common.wrappers.gui.GuiHelper.*;


/*
 * Based upon TinyConfig but is highly modified
 * https://github.com/Minenash/TinyConfig
 *
 * @author coolGi
 * @author Motschen
 * @author James Seibel
 * @version 5-21-2022
 */
@SuppressWarnings("unchecked")
public class ClassicConfigGUI
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	public static final DhLogger RATE_LIMITED_LOGGER = new DhLoggerBuilder()
			.maxCountPerSecond(1)
			.build();
	
	public static final ConfigCoreInterface CONFIG_CORE_INTERFACE = new ConfigCoreInterface();
	
	
	
	//==============//
	// Initializers //
	//==============//
	//region
	
	// Some regexes to check if an input is valid
	public static final Pattern INTEGER_ONLY_REGEX = Pattern.compile("(-?[0-9]*)");
	public static final Pattern DECIMAL_ONLY_REGEX = Pattern.compile("-?([\\d]+\\.?[\\d]*|[\\d]*\\.?[\\d]+|\\.)");
	
	public static class ConfigScreenConfigs
	{
		// This contains all the configs for the configs
		public static final int SPACE_FROM_RIGHT_SCREEN = 10;
		public static final int SPACE_BETWEEN_TEXT_AND_OPTION_FIELD = 8;
		public static final int BUTTON_WIDTH_SPACING = 5;
		public static final int RESET_BUTTON_WIDTH = 60;
		public static final int RESET_BUTTON_HEIGHT = 20;
		public static final int OPTION_FIELD_WIDTH = 150;
		public static final int OPTION_FIELD_HEIGHT = 20;
		public static final int CATEGORY_BUTTON_WIDTH = 200;
		public static final int CATEGORY_BUTTON_HEIGHT = 20;
		
	}
	
	//endregion
	
	
	
	//==============//
	// GUI handling //
	//==============//
	//region
	/** if you want to get this config gui's screen call this */
	#if MC_VER <= MC_1_12_2
	public static GuiScreen getScreen(GuiScreen parent, String category)
	#else
	public static Screen getScreen(Screen parent, String category)
	#endif
	{ return new DhConfigScreen(parent, category); }
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	#if MC_VER <= MC_1_12_2
	public static class ConfigListWidget extends GuiListExtended
	#else
	public static class ConfigListWidget extends ContainerObjectSelectionList<DhButtonEntry>
	#endif
	{
		#if MC_VER <= MC_1_12_2
		public List<DhButtonEntry> children = new ArrayList<>();
		#endif
		
		#if MC_VER <= MC_1_12_2
		FontRenderer textRenderer;
		#else
		Font textRenderer;
		#endif
		
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
			
			#if MC_VER <= MC_1_7_10
			this.textRenderer = minecraftClient.fontRendererObj;
			#elif MC_VER <= MC_1_12_2
			this.textRenderer = minecraftClient.fontRenderer;
			#else
			this.textRenderer = minecraftClient.font;
			#endif
		}
		
		#if MC_VER <= MC_1_12_2
		@Override
		protected int getSize()
		{
			return this.children.size();
		}
		
		@Override
		public IGuiListEntry getListEntry(int index)
		{
			return this.children.get(index);
		}
		
		@Override
		protected void drawContainerBackground(Tessellator tessellator)
		{
			#if MC_VER <= MC_1_7_10
			World world = Minecraft.getMinecraft().theWorld;
			#else
			World world = this.mc.world;
			#endif
			if (world != null)
			{
				return; // in-game don't draw dirt background
			}
			super.drawContainerBackground(tessellator);
		}
		#endif
		
		#if MC_VER <= MC_1_7_10
		public void addButton(DhConfigScreen gui, AbstractConfigBase dhConfigType, Gui button, GuiButton resetButton, GuiButton indexButton, String text)
		#elif MC_VER <= MC_1_12_2
		public void addButton(DhConfigScreen gui, AbstractConfigBase dhConfigType, Gui button, GuiButton resetButton, GuiButton indexButton, ITextComponent text)
		#else
		public void addButton(DhConfigScreen gui, AbstractConfigBase dhConfigType, AbstractWidget button, AbstractWidget resetButton, AbstractWidget indexButton, Component text)
		#endif
		{
			#if MC_VER <= MC_1_12_2
			this.children.add(new DhButtonEntry(gui, dhConfigType, button, text, resetButton, indexButton));
		    #else
			this.addEntry(new DhButtonEntry(gui, dhConfigType, button, text, resetButton, indexButton));
			#endif
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public int getListWidth()
		#else
		public int getRowWidth()
		#endif
		{ return 10_000; }
		
		#if MC_VER <= MC_1_12_2
		public Gui getHoveredButton(double mouseX, double mouseY)
		#else
		public AbstractWidget getHoveredButton(double mouseX, double mouseY)
		#endif
		{
			#if MC_VER <= MC_1_12_2
			for (DhButtonEntry buttonEntry : this.children)
			#else
			for (DhButtonEntry buttonEntry : this.children())
			#endif
			{
				#if MC_VER <= MC_1_12_2
				Gui gui = buttonEntry.button;
				if (gui == null) continue;
				
				double minX, minY, maxX, maxY;
				
				if (gui instanceof GuiButton)
				{
					GuiButton button = (GuiButton) gui;
					if (!button.visible)
					{
						continue;
					}
					
					minX = #if MC_VER <= MC_1_7_10 button.xPosition #else button.x #endif;
					minY = #if MC_VER <= MC_1_7_10 button.yPosition #else button.y #endif;
					maxX = minX + button.width;
					maxY = minY + button.height;
				}
				else if (gui instanceof GuiTextField)
				{
					GuiTextField field = (GuiTextField) gui;	
					if (!field.getVisible())
					{
						continue;
					}
					
					minX = #if MC_VER <= MC_1_7_10 field.xPosition #else field.x #endif;
					minY = #if MC_VER <= MC_1_7_10 field.yPosition #else field.y #endif;
					maxX = minX + field.width;
					maxY = minY + field.height;
				}
				else
				{
					continue;
				}
				
				if (mouseX >= minX && mouseX < maxX && mouseY >= minY && mouseY < maxY)
				{
					return gui;
				}
				#else
				AbstractWidget button = (AbstractWidget) buttonEntry.button;
                if (button == null || !button.visible) continue;

                #if MC_VER < MC_1_19_4
                double minX = button.x;
                double minY = button.y;
                #else
                double minX = button.getX();
                double minY = button.getY();
                #endif

                double maxX = minX + button.getWidth();
                double maxY = minY + button.getHeight();

                if (mouseX >= minX && mouseX < maxX && mouseY >= minY && mouseY < maxY)
				{
                    return button;
				}
                #endif
			}
			
			return null;
		}
		
	}
	
	#if MC_VER <= MC_1_12_2
	public static class DhButtonEntry implements GuiListExtended.IGuiListEntry
	#else
	public static class DhButtonEntry extends ContainerObjectSelectionList.Entry<DhButtonEntry>
	#endif
	{
		#if MC_VER <= MC_1_7_10
		private static final FontRenderer textRenderer = Minecraft.getMinecraft().fontRendererObj;
		#elif MC_VER <= MC_1_12_2
		private static final FontRenderer textRenderer = Minecraft.getMinecraft().fontRenderer;
		#else
		private static final Font textRenderer = Minecraft.getInstance().font;
		#endif
		
		private final DhConfigScreen gui;
		#if MC_VER <= MC_1_12_2
		public final Gui button;
		public final Gui resetButton;
		public final Gui indexButton;
		#else
		private final AbstractWidget indexButton;
		private final AbstractWidget resetButton;
		private final AbstractWidget button;
		#endif
		
		#if MC_VER <= MC_1_12_2
		private final #if MC_VER <= MC_1_7_10 String #else ITextComponent #endif text;
		#else
		private final Component text;
		#endif
		
		#if MC_VER <= MC_1_12_2
		private final List<Gui> children = new ArrayList<>();
		#else
		private final List<AbstractWidget> children = new ArrayList<>();
		#endif
		
		@NotNull
		private final EConfigCommentTextPosition textPosition;
		public final AbstractConfigBase dhConfigType;
		
		#if MC_VER <= MC_1_12_2
		public static final Map<Gui, #if MC_VER <= MC_1_7_10 String #else ITextComponent #endif> TEXT_BY_WIDGET = new HashMap<>();
		public static final Map<Gui, DhButtonEntry> BUTTON_BY_WIDGET = new HashMap<>();
		#else
		public static final Map<AbstractWidget, Component> TEXT_BY_WIDGET = new HashMap<>();
		public static final Map<AbstractWidget, DhButtonEntry> BUTTON_BY_WIDGET = new HashMap<>();
		#endif
		
		
		
		#if MC_VER <= MC_1_12_2
		public DhButtonEntry(DhConfigScreen gui, AbstractConfigBase dhConfigType, Gui button, #if MC_VER <= MC_1_7_10 String #else ITextComponent #endif text, GuiButton resetButton, GuiButton indexButton)
		#else
		public DhButtonEntry(DhConfigScreen gui, AbstractConfigBase dhConfigType, AbstractWidget button, Component text, AbstractWidget resetButton, AbstractWidget indexButton)
		#endif
		{
			TEXT_BY_WIDGET.put(button, text);
			BUTTON_BY_WIDGET.put(button, this);
			
			this.gui = gui;
			this.dhConfigType = dhConfigType;
			
			this.button = button;
			this.resetButton = resetButton;
			this.text = text;
			this.indexButton = indexButton;
			
			if (button != null) { this.children.add(button); }
			if (resetButton != null) { this.children.add(resetButton); }
			if (indexButton != null) { this.children.add(indexButton); }
			
			
			EConfigCommentTextPosition textPosition = null;
			if (this.dhConfigType instanceof ConfigUIComment)
			{
				textPosition = ((ConfigUIComment)this.dhConfigType).textPosition;
			}
			
			if (textPosition == null)
			{
				if (this.button != null)
				{
					// if a button is present
					textPosition = EConfigCommentTextPosition.RIGHT_JUSTIFIED;
				}
				else
				{
					textPosition = EConfigCommentTextPosition.CENTERED_OVER_BUTTONS;
				}
			}
			this.textPosition = textPosition;
			
		}
		
		
		
		@Override
		#if MC_VER <= MC_1_7_10
		public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, Tessellator tessellator, int mouseX, int mouseY, boolean isSelected)
		#elif MC_VER <= MC_1_12_2
		public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected, float tickDelta)
        #elif MC_VER < MC_1_20_1
		public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta)
        #elif MC_VER < MC_1_21_9
		public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta)
		#elif MC_VER <= MC_1_21_11
		public void renderContent(GuiGraphics matrices, int mouseX, int mouseY, boolean hovered, float tickDelta)
		#else
		public void extractContent(GuiGraphicsExtractor matrices, int mouseX, int mouseY, boolean hovered, float tickDelta)
		#endif
		{
			try
			{
				// setting the "y" variable is necessary so each child item
				// renders at the correct height,
				// if not set they will render off-screen.
				#if MC_VER < MC_1_21_9
				// Y value passed in from method args
				#else
				int y = this.getY();
				#endif
				
				
				
				if (this.button != null)
				{
					#if MC_VER <= MC_1_12_2
					if (this.button instanceof GuiButton)
					{
						GuiButton guiButton = (GuiButton) this.button;
						
						SetY(guiButton, y);
						#if MC_VER <= MC_1_7_10
						guiButton.drawButton(Minecraft.getMinecraft(), mouseX, mouseY);
						#else
						guiButton.drawButton(Minecraft.getMinecraft(), mouseX, mouseY, tickDelta);
						#endif
					}
					if (this.button instanceof GuiTextField)
					{
						GuiTextField guiTextField = (GuiTextField) this.button;
						SetY(guiTextField, y);
						guiTextField.drawTextBox();
					}
					#else
					SetY(this.button, y);
					{
						#if MC_VER <= MC_1_21_11
						this.button.render(matrices, mouseX, mouseY, tickDelta);
						#else
						this.button.extractRenderState(matrices, mouseX, mouseY, tickDelta);
						#endif
					}	
					#endif
				}
				
				if (this.resetButton != null)
				{
					#if MC_VER <= MC_1_12_2
					SetY((GuiButton) this.resetButton, y);
					#else
					SetY(this.resetButton, y);
					#endif
					
					#if MC_VER <= MC_1_7_10
					((GuiButton) this.resetButton).drawButton(Minecraft.getMinecraft(), mouseX, mouseY);
					#elif MC_VER <= MC_1_12_2
					((GuiButton) this.resetButton).drawButton(Minecraft.getMinecraft(), mouseX, mouseY, tickDelta);
					#elif MC_VER <= MC_1_21_11
					this.resetButton.render(matrices, mouseX, mouseY, tickDelta);
					#else
					this.resetButton.extractRenderState(matrices, mouseX, mouseY, tickDelta);
					#endif
				}
				
				if (this.indexButton != null)
				{
					#if MC_VER <= MC_1_12_2
					SetY((GuiButton) this.indexButton, y);
					#else
					SetY(this.indexButton, y);
					#endif
					
					#if MC_VER <= MC_1_7_10
					((GuiButton) this.indexButton).drawButton(Minecraft.getMinecraft(), mouseX, mouseY);
					#elif MC_VER <= MC_1_12_2
					((GuiButton) this.indexButton).drawButton(Minecraft.getMinecraft(), mouseX, mouseY, tickDelta);
					#elif MC_VER <= MC_1_21_11
					this.indexButton.render(matrices, mouseX, mouseY, tickDelta);
					#else
					this.indexButton.extractRenderState(matrices, mouseX, mouseY, tickDelta);
					#endif
				}
				
				if (this.text != null)
				{
					#if MC_VER <= MC_1_7_10
					int translatedLength = textRenderer.getStringWidth(this.text);
					#elif MC_VER <= MC_1_12_2
					int translatedLength = textRenderer.getStringWidth(this.text.getFormattedText());
					#else
					int translatedLength = textRenderer.width(this.text);
					#endif
					
					int textXPos;
					if (this.textPosition == EConfigCommentTextPosition.RIGHT_JUSTIFIED)
					{
						// text right justified aligned against the buttons
						textXPos = this.gui.width
								- translatedLength
								- ConfigScreenConfigs.SPACE_BETWEEN_TEXT_AND_OPTION_FIELD
								- ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN
								- ConfigScreenConfigs.OPTION_FIELD_WIDTH
								- ConfigScreenConfigs.BUTTON_WIDTH_SPACING
								- ConfigScreenConfigs.RESET_BUTTON_WIDTH;
					}
					else if (this.textPosition == EConfigCommentTextPosition.CENTERED_OVER_BUTTONS)
					{
						// have button centered relative to a category button
						textXPos = this.gui.width
								- (translatedLength / 2)
								- (ConfigScreenConfigs.CATEGORY_BUTTON_WIDTH / 2)
								- ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN;
					}
					else if (this.textPosition == EConfigCommentTextPosition.CENTER_OF_SCREEN)
					{
						// have button centered in the screen
						textXPos = (this.gui.width / 2)
								- (translatedLength / 2);
					}
					else
					{
						throw new UnsupportedOperationException("No text position render defined for [" + this.textPosition + "]");
					}
				
				#if MC_VER <= MC_1_12_2
				textRenderer.drawString(
						#if MC_VER <= MC_1_7_10 this.text #else this.text.getFormattedText() #endif,
						textXPos, y + 5, 
						0xFFFFFF);
                #elif MC_VER < MC_1_20_1
				GuiComponent.drawString(matrices, textRenderer, 
						this.text, 
						textXPos, y + 5, 
						0xFFFFFF);
				#elif MC_VER < MC_1_21_6
				matrices.drawString(textRenderer,
						this.text,
						textXPos, y + 5,
						0xFFFFFF);
				#elif MC_VER <= MC_1_21_11
				matrices.drawString(textRenderer, 
						this.text,
						textXPos, y + 5, 
						0xFFFFFFFF);
				#else
				matrices.text(textRenderer, 
						this.text,
						textXPos, y + 5, 
						0xFFFFFFFF);
				#endif
				}
			}
			catch (Exception e)
			{
				// should prevent crashing the game if there's an issue
				RATE_LIMITED_LOGGER.error("Unexpected gui rendering issue: ["+e.getMessage()+"]", e);
			}
		}
		
		
		#if MC_VER <= MC_1_12_2
		@Override
		public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX, int relativeY)
		{ return false; /* handled in DhConfigScreen.mouseClicked */ }
		
		@Override
		public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY) { }
		#endif
		
		#if MC_VER <= MC_1_7_10
		#elif MC_VER <= MC_1_12_2
		@Override
		public void updatePosition(int slotIndex, int x, int y, float partialTicks) { }
		#endif
		
		#if MC_VER >= MC_1_16_5
		@Override
		public @NotNull List<? extends GuiEventListener> children()
		{ return this.children; }
		#endif
		
		#if MC_VER >= MC_1_17_1
		@Override
		public @NotNull List<? extends NarratableEntry> narratables()
		{ return this.children; }
		#endif
		
		
		
	}
	
	//endregion
	
	
	
	//================//
	// event handling //
	//================//
	//region
	
	public static class ConfigCoreInterface implements IConfigGui
	{
		/**
		 * in the future it would be good to pass in the current page and other variables, 
		 * but for now just knowing when the page is closed is good enough 
		 */
		public final ArrayList<Runnable> onScreenChangeListenerList = new ArrayList<>();
		
		
		
		@Override
		public void addOnScreenChangeListener(Runnable newListener) { this.onScreenChangeListenerList.add(newListener); }
		@Override
		public void removeOnScreenChangeListener(Runnable oldListener) { this.onScreenChangeListenerList.remove(oldListener); }
		
	}
	
	//endregion
}
