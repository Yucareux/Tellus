package com.seibel.distanthorizons.forge112.modCompat.sereneseasons;

import net.minecraft.world.biome.Biome;
import sereneseasons.api.season.ISeasonColorProvider;
import sereneseasons.config.BiomeConfig;
import sereneseasons.handler.season.SeasonHandler;
import sereneseasons.season.SeasonTime;
import sereneseasons.util.SeasonColourUtil;

public class SereneSeasons
{
	
	public static int applySereneSeasonsGrassTint(Biome biome, int baseColor)
	{
		SeasonTime calendar = SeasonHandler.getClientSeasonTime();
		ISeasonColorProvider colorProvider = BiomeConfig.usesTropicalSeasons(biome) ? calendar.getTropicalSeason() : calendar.getSubSeason();
		return SeasonColourUtil.applySeasonalGrassColouring(colorProvider, biome, baseColor);
	}
	
	public static int applySereneSeasonsFoliageTint(Biome biome, int baseColor)
	{
		SeasonTime calendar = SeasonHandler.getClientSeasonTime();
		ISeasonColorProvider colorProvider = BiomeConfig.usesTropicalSeasons(biome) ? calendar.getTropicalSeason() : calendar.getSubSeason();
		return SeasonColourUtil.applySeasonalFoliageColouring(colorProvider, biome, baseColor);
	}
	
}