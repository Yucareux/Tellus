package com.seibel.distanthorizons.common.wrappers.gui.updater;
import com.seibel.distanthorizons.common.wrappers.gui.DhScreen;
import com.seibel.distanthorizons.common.wrappers.gui.DhScreenUtil;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.IVersionConstants;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.jar.installer.MarkdownFormatter;
import com.seibel.distanthorizons.core.jar.installer.ModrinthGetter;
import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_12_2
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
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


#if MC_VER >= MC_1_17_1
import net.minecraft.client.gui.narration.NarratableEntry;
#endif

#if MC_VER <= MC_1_7_10
import net.minecraft.client.renderer.Tessellator;
#elif MC_VER <= MC_1_12_2
#elif MC_VER < MC_1_20_1
import net.minecraft.client.gui.GuiComponent;
import com.mojang.blaze3d.vertex.PoseStack;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
#endif


import static com.seibel.distanthorizons.common.wrappers.gui.GuiHelper.*;

import java.util.*;

/**
 * The screen that pops up if the mod has an update.
 *
 * @author coolGi
 */
public class ChangelogScreen extends DhScreen
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	#if MC_VER <= MC_1_12_2
	private GuiScreen parent;
	#else
	private Screen parent;
	#endif
	private String versionID;
	private List<String> changelog;
	private TextArea changelogArea;
	
	public boolean usable = false;
	
	#if MC_VER <= MC_1_12_2
	public ChangelogScreen(GuiScreen parent)
	#else
	public ChangelogScreen(Screen parent)
	#endif
	{
		this(parent, null);
		
		if (!ModrinthGetter.initted) // Make sure the modrinth stuff is initted
		{
			ModrinthGetter.init();
		}
		if (!ModrinthGetter.initted) // If its not initted, then this isnt usable
		{
			return;
		}
		
		if (!ModrinthGetter.mcVersions.contains(SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion()))
		{
			return;
		}
		
		String versionID = ModrinthGetter.getLatestIDForVersion(SingletonInjector.INSTANCE.get(IVersionConstants.class).getMinecraftVersion());
		if (versionID == null)
		{
			return;
		}
		
		try
		{
			this.setupChangelog(versionID);
			this.usable = true;
		}
		catch (Exception e)
		{
			LOGGER.error("failed to setup changelog, error: ["+e.getMessage()+"].", e);
		}
	}
	
	#if MC_VER <= MC_1_12_2
	public ChangelogScreen(GuiScreen parent, String versionID)
	#else
	public ChangelogScreen(Screen parent, String versionID)
	#endif
	{
		super(Translatable(ModInfo.ID + ".updater.title"));
		this.parent = parent;
		this.versionID = versionID;
		
		
		if (versionID == null)
		{
			return;
		}
		try
		{
			this.setupChangelog(versionID);
			this.usable = true;
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}
	
	private void setupChangelog(String versionID)
	{
		this.changelog = new ArrayList<>();
		
		// Put the new version name at the very top of the change log
		this.changelog.add("§lChangelog for " + ModrinthGetter.releaseNames.get(versionID) + "§r");
		this.changelog.add("");
		this.changelog.add("");
		
		String changelog = ModrinthGetter.changeLogs.get(versionID);
		if (changelog == null)
		{
			// in case something goes wrong this will prevent null pointers
			changelog = "";
		}
		
		// Get the release changelog and split it by the new lines
		String[] unwrappedChangelog = // Arrays.asList could be used if a list object is desired here vs List.of which is only available for Java 9+
				// This formats markdown to minecraft's "§" charactersnew MarkdownFormatter.MinecraftFormat().convertTo(
				new MarkdownFormatter.MinecraftFormat().convertTo(changelog).split("\\n");
		// Makes the words wrap around to not go off the screen
		for (String str : unwrappedChangelog)
		{
			this.changelog.addAll(
					MarkdownFormatter.splitString(str, 75)
			);
		}
		// Debugging
//        System.out.println(this.changelog);
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
		
		if (!this.usable)
		{
			return;
		}
		
		
		this.addBtn( // Close
				MakeBtn(Translatable(ModInfo.ID + ".general.back"), 5, this.height - 25, 100, 20, (btn) -> {
					#if MC_VER <= MC_1_12_2
					DhScreenUtil.setScreen(this.parent);
					#else
					this.onClose();
					#endif
				})
		);
		
		#if MC_VER <= MC_1_12_2
		this.changelogArea = new TextArea(this.mc, this.width * 2, this.height, 32, 32, 10);
		#else
		this.changelogArea = new TextArea(this.minecraft, this.width * 2, this.height, 32, 32, 10);
		#endif
		for (int i = 0; i < this.changelog.size(); i++)
		{
			this.changelogArea.addButton(TextOrLiteral(this.changelog.get(i)));
//            drawString(matrices, this.font, changelog.get(i), this.width / 2 - 175, this.height / 2 - 100 + i*10, 0xFFFFFF);
		}
		
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
		this.drawDefaultBackground(); // Render background
		#elif MC_VER < MC_1_20_2
		this.renderBackground(matrices); // Render background
		#elif MC_VER < MC_1_21_6
		this.renderBackground(matrices, mouseX, mouseY, delta); // Render background
		#else
		// background blur is already being rendered, rendering again causes the game to crash
		#endif
		
		if (!this.usable)
		{
			return;
		}
		
		int maxScroll;
		#if MC_VER <= MC_1_7_10
		maxScroll = this.changelogArea.func_148135_f();
		#elif MC_VER <= MC_1_21_3
		maxScroll = this.changelogArea.getMaxScroll();
		#else
		maxScroll = this.changelogArea.maxScrollAmount();
		#endif
		
		// Set the scroll position to the mouse height relative to the screen
		// This is a bit of a hack as we cannot scroll on this area
		double scrollAmount = ((double) mouseY) / ((double) this.height) * 1.1 * maxScroll;
		
		#if MC_VER <= MC_1_12_2
		this.changelogArea.amountScrolled = (float)scrollAmount;
	    #elif MC_VER == MC_1_16_5 || MC_VER == MC_1_17_1
		this.changelogArea.setScrollAmount(scrollAmount);
		#elif MC_VER <= MC_1_21_3
		this.changelogArea.scrollAmount = scrollAmount;
		#else
		this.changelogArea.setScrollAmount(scrollAmount);
		#endif
		
		// render order matters, otherwise on
		// 1.12.2- buttons won't render
		// 1.20.6+ the blurred background will render on top of the text
		#if MC_VER <= MC_1_12_2
		this.changelogArea.drawScreen(mouseX, mouseY, delta); // Render the changelog
		super.drawScreen(mouseX, mouseY, delta); // Render the buttons
		#elif MC_VER <= MC_1_21_11
		super.render(matrices, mouseX, mouseY, delta); // Render the buttons
		this.changelogArea.render(matrices, mouseX, mouseY, delta); // Render the changelog
		#else
		super.extractRenderState(matrices, mouseX, mouseY, delta); // Render the buttons
		this.changelogArea.extractRenderState(matrices, mouseX, mouseY, delta); // Render the changelog
	    #endif
		
		// Render title
		#if MC_VER <= MC_1_12_2
		this.DhDrawCenteredString(this.title, this.width / 2, 15, 0xFFFFFF); 
		#else
		this.DhDrawCenteredString(matrices, this.font, this.title, this.width / 2, 15, 0xFFFFFF);
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	@Override
	public void onGuiClosed()
	#else
	@Override
	public void onClose()
	#endif
	{
		// Go to the parent screen
		#if MC_VER <= MC_1_12_2
		// Handled by button to avoid recursive loop
		#else
		DhScreenUtil.setScreen(this.parent);
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	public static class TextArea extends GuiListExtended
	#else
	public static class TextArea extends ContainerObjectSelectionList<ButtonEntry>
	#endif
	{
		#if MC_VER <= MC_1_12_2
		public List<ButtonEntry> children = new ArrayList<>();
		#endif

		#if MC_VER <= MC_1_12_2
		FontRenderer textRenderer;
		#else
		Font textRenderer;
		#endif
		
		public TextArea(Minecraft minecraftClient, int canvasWidth, int canvasHeight, int topMargin, int botMargin, int itemSpacing)
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
		
		#if MC_VER <= MC_1_7_10
		public void addButton(String text)
		#elif MC_VER <= MC_1_12_2
		public void addButton(ITextComponent text)
		#else
		public void addButton(Component text)
		#endif
		{
			#if MC_VER <= MC_1_12_2
			this.children.add(ButtonEntry.create(text));
			#else
			this.addEntry(ButtonEntry.create(text));
			#endif
		}
		
		@Override
		#if MC_VER <= MC_1_12_2
		public int getListWidth()
		#else
		public int getRowWidth()
		#endif
		{
			return 10_000;
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
		#endif
		
	}
	
	#if MC_VER <= MC_1_12_2
	public static class ButtonEntry implements GuiListExtended.IGuiListEntry
	#else
	public static class ButtonEntry extends ContainerObjectSelectionList.Entry<ButtonEntry>
	#endif
	{
		#if MC_VER <= MC_1_7_10
		private static final FontRenderer textRenderer = Minecraft.getMinecraft().fontRendererObj;
		#elif MC_VER <= MC_1_12_2
		private static final FontRenderer textRenderer = Minecraft.getMinecraft().fontRenderer;
		#else
		private static final Font textRenderer = Minecraft.getInstance().font;
		#endif
		
		#if MC_VER <= MC_1_7_10
		private final String text;
		#elif MC_VER <= MC_1_12_2
		private final ITextComponent text;
		#else
		private final Component text;
		#endif
		
		#if MC_VER <= MC_1_12_2
		private final List<Gui> children = new ArrayList<>();
		#else
		private final List<AbstractWidget> children = new ArrayList<>();
		#endif
		
		#if MC_VER <= MC_1_7_10
		private ButtonEntry(String text)
		#elif MC_VER <= MC_1_12_2
		private ButtonEntry(ITextComponent text)
		#else
		private ButtonEntry(Component text)
		#endif
		{ this.text = text; }
		
		#if MC_VER <= MC_1_7_10
		public static ButtonEntry create(String text)
		#elif MC_VER <= MC_1_12_2
		public static ButtonEntry create(ITextComponent text)
		#else
		public static ButtonEntry create(Component text)
		#endif
		{ return new ButtonEntry(text); }
		
		@Override
		#if MC_VER <= MC_1_7_10
		public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, Tessellator tessellator, int mouseX, int mouseY, boolean isSelected)
		{ textRenderer.drawString(this.text, 12, y + 5, 0xFFFFFF); }
		#elif MC_VER <= MC_1_12_2
		public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected, float tickDelta)
		{ textRenderer.drawString(text.getFormattedText(), 12, y + 5, 0xFFFFFF); }
        #elif MC_VER < MC_1_20_1
		public void render(PoseStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta)
		{ GuiComponent.drawString(matrices, textRenderer, text, 12, y + 5, 0xFFFFFF); }
		#elif MC_VER < MC_1_21_9
		public void render(GuiGraphics matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta)
		{ matrices.drawString(textRenderer, this.text, 12, y + 5, 0xFFFFFF); }
		#elif MC_VER <= MC_1_21_11
		public void renderContent(GuiGraphics matrices, int y, int x, boolean hovered, float tickDelta)
		{ matrices.drawString(textRenderer, this.text, 12, y + 5, 0xFFFFFF); }
		#else
		public void extractContent(GuiGraphicsExtractor matrices, int y, int x, boolean hovered, float tickDelta)
		{ matrices.text(textRenderer, this.text, 12, y + 5, 0xFFFFFF); }
        #endif
		
		#if MC_VER > MC_1_12_2
		@Override
		public List<? extends GuiEventListener> children() { return this.children; }
		#endif
		
		#if MC_VER >= MC_1_17_1
		@Override
		public List<? extends NarratableEntry> narratables() { return this.children; }
		#endif
		
		#if MC_VER <= MC_1_12_2
		
		#if MC_VER <= MC_1_7_10
		#else
		@Override
		public void updatePosition(int slotIndex, int x, int y, float partialTicks) { }
		#endif

		@Override
		public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX, int relativeY) { return false; }

		@Override
		public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY) { }
		#endif
	}
	
}
