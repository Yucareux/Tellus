package com.seibel.distanthorizons.forge17.modAccessor.exceptions;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiErrorScreen;

import cpw.mods.fml.client.CustomModLoadingErrorDisplayException;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class AngelicaVersionGuiException extends CustomModLoadingErrorDisplayException 
{
    //private static final long serialVersionUID = 1L;
	
    private final String installedVersion;
    private final String requiredVersion;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
    public AngelicaVersionGuiException(String installedVersion, String requiredVersion) 
    {
        this.installedVersion = installedVersion;
        this.requiredVersion = requiredVersion;
    }
	
	//endregion
	
	
	
	//=====//
	// GUI //
	//=====//
	//region
	
    @Override
    public void initGui(GuiErrorScreen errorScreen, FontRenderer fontRenderer) {}
	
    @Override
    public void drawScreen(GuiErrorScreen errorScreen, FontRenderer fontRenderer, int mouseRelX, int mouseRelY,
        float tickTime) 
    {
        int centerX = errorScreen.width / 2;
        int y = 70;

        errorScreen.drawCenteredString(
            fontRenderer,
            "Forge Mod Loader has found a problem with your minecraft installation",
            centerX,
            y,
            0xFFFFFF); // white
		
        y += 20;
        errorScreen.drawCenteredString(
            fontRenderer,
            "Distant Horizons requires a newer version of Angelica",
            centerX,
            y,
            0xFFFFFF); // white
		
        y += 20;
        errorScreen.drawCenteredString(
            fontRenderer,
            "Installed Angelica version: " + this.installedVersion,
            centerX,
            y,
            0xEEEEEE); // gray
		
        y += 12;
        errorScreen.drawCenteredString(
            fontRenderer,
            "Minimum required version: " + this.requiredVersion,
            centerX,
            y,
            0xEEEEEE); // gray
		
        y += 24;
        errorScreen.drawCenteredString(
            fontRenderer,
            "The file 'logs/fml-client-latest.log' contains more information",
            centerX,
            y,
            0xFFFFFF); // white
    }
	
	//endregion
	
	
	
}
