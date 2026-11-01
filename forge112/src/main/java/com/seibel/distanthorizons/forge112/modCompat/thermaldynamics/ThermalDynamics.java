package com.seibel.distanthorizons.forge112.modCompat.thermaldynamics;

import cofh.thermaldynamics.duct.TDDucts;
import com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import static com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache.calculateColorFromTexture;

public class ThermalDynamics
{
	public static TextureAtlasSprite getThermalDynamicDuctTexture(IBlockState blockState)
	{
		int meta = blockState.getBlock().getMetaFromState(blockState);
		int idOffset = 0;
		
		String name = blockState.getBlock().getRegistryName().toString();
		
		if (name.contains("thermaldynamics:duct_32"))
		{
			idOffset = TDDucts.OFFSET_ITEM;
			return TDDucts.getType(meta + idOffset).iconBaseTexture;
		}
		else if (name.contains("thermaldynamics:duct_64"))
		{
			idOffset = TDDucts.OFFSET_TRANSPORT;
			return TDDucts.getType(meta + idOffset).iconBaseTexture;
		}
		else if (name.contains("thermaldynamics:duct_16"))
		{
			idOffset = TDDucts.OFFSET_FLUID;
			return TDDucts.getType(meta + idOffset).iconBaseTexture;
		}
		else if (name.contains("thermaldynamics:duct_80"))
		{
			idOffset = TDDucts.OFFSET_ENDER;
			return TDDucts.getType(meta + idOffset).iconBaseTexture;
		}
		else
		{
			return TDDucts.getType(meta + idOffset).iconBaseTexture;
		}
	}
	
}