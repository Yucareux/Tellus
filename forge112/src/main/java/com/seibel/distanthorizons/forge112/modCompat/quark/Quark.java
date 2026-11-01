package com.seibel.distanthorizons.forge112.modCompat.quark;

import vazkii.quark.client.feature.GreenerGrass;

public class Quark
{
	public static int applyQuarksGreenerGrassFoliageTint(int tintColor){
		int r = (tintColor >> 16) & 0xFF;
		int g = (tintColor >> 8) & 0xFF;
		int b = tintColor & 0xFF;
		
		r = Math.min(255, Math.max(0, r + GreenerGrass.redShift));
		g = Math.min(255, Math.max(0, g + GreenerGrass.greenShift));
		b = Math.min(255, Math.max(0, b + GreenerGrass.blueShift));
		
		return (r << 16) | (g << 8) | b;
	}
}
