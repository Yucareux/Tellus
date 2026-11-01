package com.seibel.distanthorizons.common.wrappers.gui.updater;
import com.seibel.distanthorizons.api.enums.config.EDhApiUpdateBranch;
import com.seibel.distanthorizons.common.wrappers.gui.DhScreen;
import com.seibel.distanthorizons.common.wrappers.gui.DhScreenUtil;
import com.seibel.distanthorizons.common.wrappers.gui.TexturedButtonWidget;
import com.seibel.distanthorizons.core.jar.ModJarInfo;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.jar.installer.ModrinthGetter;
import com.seibel.distanthorizons.core.jar.updater.SelfUpdater;
import com.seibel.distanthorizons.core.logging.DhLogger;
#if MC_VER <= MC_1_12_2
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
#else
import net.minecraft.client.gui.screens.Screen;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER < MC_1_20_1
import com.mojang.blaze3d.vertex.PoseStack;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
#endif

#if MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation;
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

import static com.seibel.distanthorizons.common.wrappers.gui.GuiHelper.*;

/**
 * The screen that pops up if the mod has an update.
 *
 * @author coolGi
 */
@SuppressWarnings("deprecation") // ResourceLocation constructor is deprecated on some MC versions
public class UpdateModScreen extends DhScreen
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	private GuiScreen parent;
	#else
	private Screen parent;
	#endif
	private String newVersionID;
	
	private String currentVer;
	private String nextVer;
	
	#if MC_VER <= MC_1_12_2
	private static final int logoButton_id = 100;
	private static final int changelogButton_id = 101;
	#endif

	#if MC_VER <= MC_1_12_2
	public UpdateModScreen(GuiScreen parent, String newVersionID) throws IllegalArgumentException
	#else
	public UpdateModScreen(Screen parent, String newVersionID) throws IllegalArgumentException
	#endif
	{
		super(Translatable(ModInfo.ID + ".updater.title"));
		this.parent = parent;
		this.newVersionID = newVersionID;
		
		
		EDhApiUpdateBranch updateBranch = EDhApiUpdateBranch.convertAutoToStableOrNightly(Config.Client.Advanced.AutoUpdater.updateBranch.get());
		if (updateBranch == EDhApiUpdateBranch.STABLE)
        {
	        this.currentVer = ModInfo.VERSION;
	        this.nextVer = ModrinthGetter.releaseNames.get(this.newVersionID);
        }
	    else
        {
	        this.currentVer = ModJarInfo.Git_Commit.substring(0,7);
	        this.nextVer = this.newVersionID.substring(0,7);
        }
		
		// done to prevent trying to update to "null"
		// (this can happen if no versions are available to check/download from modrinth/gitlab)
		if (this.nextVer == null)
		{
			throw new IllegalArgumentException("No new version found with the ID ["+newVersionID+"].");
		}
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
		
		try
		{
			
			
			// Logo image
			this.addBtn(new TexturedButtonWidget(
					#if MC_VER <= MC_1_12_2
					logoButton_id,
					#endif
					// Where the button is on the screen
					this.width / 2 - 95, this.height / 2 - 110,
					// Width and height of the button
					195, 65,
					// Offset
					0, 0,
					// Some textuary stuff
					0, 
					#if MC_VER <= MC_1_20_6
					new ResourceLocation(ModInfo.ID, "logo.png"),
					#elif MC_VER <= MC_1_21_10
					ResourceLocation.fromNamespaceAndPath(ModInfo.ID, "logo.png"),
					#else
					Identifier.fromNamespaceAndPath(ModInfo.ID, "logo.png"),
					#endif
					195, 65,
					// Create the button and tell it where to go
					// For now it goes to the client option by default
					#if MC_VER > MC_1_12_2
					(buttonWidget) -> LOGGER.info("Nice, you found an Easter egg :)"),
					#endif
					// Add a title to the button
					#if MC_VER == MC_1_12_2
					Translatable(ModInfo.ID + ".updater.title").getFormattedText(),
					#else
					Translatable(ModInfo.ID + ".updater.title"),
					#endif
					// Dont render the background of the button
					false
			));
		}
		catch (Exception e)
		{
			LOGGER.error("Failed to setup update mod screen, error: ["+e.getMessage()+"].", e);
		}
		
		if (!ModInfo.IS_DEV_BUILD)
		{
			this.addBtn(new TexturedButtonWidget(
				#if MC_VER <= MC_1_12_2
				changelogButton_id,
				#endif
				// Where the button is on the screen
				this.width / 2 - 97, this.height / 2 + 8,
				// Width and height of the button
				20, 20,
				// Offset
				0, 0,
				// Some textuary stuff
				0, 
				#if MC_VER < MC_1_21_1
				new ResourceLocation(ModInfo.ID, "textures/gui/changelog.png"),
				#elif MC_VER <= MC_1_21_10
				ResourceLocation.fromNamespaceAndPath(ModInfo.ID, "textures/gui/changelog.png"),
				#else
				Identifier.fromNamespaceAndPath(ModInfo.ID, "textures/gui/changelog.png"),
				#endif
				20, 20,
				// Create the button and tell it where to go
				#if MC_VER > MC_1_12_2
				(buttonWidget) -> DhScreenUtil.setScreen(new ChangelogScreen(this, this.newVersionID)),
				#endif
				// Add a title to the button
				#if MC_VER == MC_1_12_2
				Translatable(ModInfo.ID + ".updater.title").getFormattedText()
				#else
				Translatable(ModInfo.ID + ".updater.title")
				#endif
			));
		}
		
		
		this.addBtn( // Update
				MakeBtn(Translatable(ModInfo.ID + ".updater.update"), this.width / 2 - 75, this.height / 2 + 8, 150, 20, (btn) -> {
					SelfUpdater.updateMod();
					#if MC_VER <= MC_1_12_2
					DhScreenUtil.setScreen(this.parent);
					#else
					this.onClose();
					#endif
				})
		);
		this.addBtn( // Silent update
				MakeBtn(Translatable(ModInfo.ID + ".updater.silent"), this.width / 2 - 75, this.height / 2 + 30, 150, 20, (btn) -> {
					Config.Client.Advanced.AutoUpdater.enableSilentUpdates.set(true);
					SelfUpdater.updateMod();
					#if MC_VER <= MC_1_12_2
					DhScreenUtil.setScreen(this.parent);
					#else
					this.onClose();
					#endif
				})
		);
		this.addBtn( // Later (not now)
				MakeBtn(Translatable(ModInfo.ID + ".updater.later"), this.width / 2 + 2, this.height / 2 + 70, 100, 20, (btn) -> {
					#if MC_VER <= MC_1_12_2
					DhScreenUtil.setScreen(this.parent);
					#else
					this.onClose();
					#endif
				})
		);
		this.addBtn( // Never
				MakeBtn(Translatable(ModInfo.ID + ".updater.never"), this.width / 2 - 102, this.height / 2 + 70, 100, 20, (btn) -> {
					Config.Client.Advanced.AutoUpdater.enableAutoUpdater.set(false);
					#if MC_VER <= MC_1_12_2
					DhScreenUtil.setScreen(this.parent);
					#else
					this.onClose();
					#endif
				})
		);
		
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
		
		#if MC_VER <= MC_1_12_2
		super.drawScreen(mouseX, mouseY, delta); // Render the buttons
		#elif MC_VER <= MC_1_21_11
		super.render(matrices, mouseX, mouseY, delta); // Render the buttons
		#else
		super.extractRenderState(matrices, mouseX, mouseY, delta);
		#endif
		 
		// Render the text's
		this.DhDrawCenteredString(
				#if MC_VER > MC_1_12_2	
				matrices, this.font,
				#endif
				Translatable(ModInfo.ID + ".updater.updateAvailable"), 
				this.width / 2, this.height / 2 - 35,
				#if MC_VER < MC_1_21_6
				0xFFFFFF // RGB
				#else
				0xFFFFFFFF // ARGB
				#endif
		);
		this.DhDrawCenteredString(
				#if MC_VER > MC_1_12_2	
				matrices, this.font,
				#endif
				Translatable(ModInfo.ID + ".updater.updateConfirmation", this.currentVer, this.nextVer), 
				this.width / 2, this.height / 2 - 20, 
				#if MC_VER < MC_1_21_6
				0x52FD52 // RGB
				#else
				0xFF52FD52 // ARGB
				#endif
		);
	}
	
	@Override
	#if MC_VER <= MC_1_12_2
	public void onGuiClosed()
	#else
	public void onClose()
	#endif
	{
		// Go to the parent screen
		#if MC_VER <= MC_1_12_2
		// Handled by button to avoid recursive loop
		#else
		DhScreenUtil.setScreen(this.parent); // Go to the parent screen
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	@Override
	protected void actionPerformed(GuiButton button)
	{
		super.actionPerformed(button);
		if(button.id == changelogButton_id)
		{
			ChangelogScreen changelogScreen = new ChangelogScreen(this);
			if (changelogScreen.usable)
			{
				DhScreenUtil.setScreen(changelogScreen);
			}
			else
			{
				LOGGER.warn("Changelog was not able to open");
			}
		}
	}
	#endif
	
}
