package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.common.backports.FakeBlockState;
import com.seibel.distanthorizons.common.backports.IBlockState;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IRpleCommonAccessor;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.DynamicTexture;

import com.falsepattern.rple.api.client.RPLELightMapAPI;
import com.falsepattern.rple.api.common.ServerColorHelper;
import com.falsepattern.rple.api.common.block.RPLEBlock;

/**
 * 
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting
 */
public class RpleAccessor implements IRpleCommonAccessor
{
    private final DynamicTexture lightmapTexture;
	private final int[] lightmapColors;
	
	@Override 
	public String getModName() { return "rple"; }
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
    public RpleAccessor() 
    {
        this.lightmapTexture = new DynamicTexture(16, 16);
	    this.lightmapColors = this.lightmapTexture.getTextureData();
    }
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	@Override
    public int getColor(Block block, int meta) 
    {
        short color = RPLEBlock.of(block)
            .rple$getBrightnessColor(meta);
        return ServerColorHelper.lightValueFromRGB16(color);
    }
	
	//endregion
	
	
	
	//==========//
	// lightmap //
	//==========//
	//region
	
	@Override
	public void updateLightmap() 
    {
        RPLELightMapAPI.getMixedLightMapData(this.lightmapColors);
	    this.lightmapTexture.updateDynamicTexture();
    }
	
	@Override
	public int getLightmapTextureId() { return this.lightmapTexture.getGlTextureId(); }
	
	//endregion
	
	
	
}
