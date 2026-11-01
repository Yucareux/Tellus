package com.seibel.distanthorizons;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.seibel.distanthorizons.forge17.Mixins;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Defines our mixins.
 */
public class DistantHorizonsTweaker implements IEarlyMixinLoader, IFMLLoadingPlugin
{
	@Override
	public String getMixinConfig() { return "DistantHorizons.forge.mixins.json"; }

	@Override
	public List<String> getMixins(Set<String> loadedCoreMods) { return IMixins.getEarlyMixins(Mixins.class, loadedCoreMods); }

	@Override
	public String[] getASMTransformerClass() { return new String[0]; }

	@Override
	public String getModContainerClass() { return null; }

	@Override
	public String getSetupClass() { return null; }

	@Override
	public void injectData(Map<String, Object> data) { }

	@Override
	public String getAccessTransformerClass() { return null; }
}
