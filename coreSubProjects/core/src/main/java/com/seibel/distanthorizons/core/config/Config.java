/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.config;

import com.seibel.distanthorizons.api.enums.config.*;
import com.seibel.distanthorizons.api.enums.config.quickOptions.*;
import com.seibel.distanthorizons.api.enums.rendering.*;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorProgressDisplayLocation;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.eventHandlers.*;
import com.seibel.distanthorizons.core.config.eventHandlers.presets.*;
import com.seibel.distanthorizons.core.config.types.*;
import com.seibel.distanthorizons.core.config.types.enums.*;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.io.File;
import java.util.*;
import java.util.List;

/**
 * This handles any configuration the user has access to.
 *
 * @author coolGi
 * @see ConfigHandler
 */
@SuppressWarnings({"ConcatenationWithEmptyString","unused"})
public class Config
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static ConfigCategory client = new ConfigCategory.Builder().set(Client.class).build();
	
	
	
	public static class Client
	{
		public static ConfigEntry<Boolean> quickEnableRendering = new ConfigEntry.Builder<Boolean>()
			.set(true)
			.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
			.build();
		
		public static ConfigUiLinkedEntry quickLodChunkRenderDistance = new ConfigUiLinkedEntry(Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius);
		
		public static ConfigEntry<EDhApiQualityPreset> qualityPresetSetting = new ConfigEntry.Builder<EDhApiQualityPreset>()
			.set(EDhApiQualityPreset.MEDIUM) // the default value is set via the listener when accessed
			.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
			.addListener(RenderQualityPresetConfigEventHandler.INSTANCE)
			.build();
		
		public static ConfigEntry<EDhApiThreadPreset> threadPresetSetting = new ConfigEntry.Builder<EDhApiThreadPreset>()
			.setChatCommandName("common.threadPreset")
			.set(EDhApiThreadPreset.BALANCED) // the default value is set via the listener when accessed
			.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
			.addListener(ThreadPresetConfigEventHandler.INSTANCE)
			.build();
		
		public static ConfigUiLinkedEntry quickWorldGeneratorPlan = new ConfigUiLinkedEntry(Common.WorldGenerator.generatorPlan);
		
		public static ConfigUiLinkedEntry quickShowWorldGenProgress = new ConfigUiLinkedEntry(Common.WorldGenerator.showGenerationProgress);
		
		public static ConfigUiLinkedEntry quickLodCloudRendering = new ConfigUiLinkedEntry(Advanced.Graphics.GenericRendering.enableCloudRendering);
		
		public static ConfigEntry<Boolean> showDhOptionsButtonInMinecraftUi = new ConfigEntry.Builder<Boolean>()
			.set(true)
			.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
			.comment("" +
				"Should Distant Horizon's config button appear in Minecraft's options screen next to the fov slider?")
			.build();
		
		
		public static ConfigCategory advanced = new ConfigCategory.Builder().set(Advanced.class).build();
		
		
		
		public static class Advanced
		{
			// common config links need to have their destination
			// since they aren't part of "client" config class
			
			public static ConfigUIComment advancedHeader = new ConfigUIComment.Builder().setParentConfigClass(Advanced.class).build();
			
			public static ConfigCategory graphics = new ConfigCategory.Builder().set(Graphics.class).build();
			public static ConfigCategory worldGenerator = new ConfigCategory.Builder().set(Common.WorldGenerator.class).setDestination("common.worldGenerator").build();
			public static ConfigCategory multiplayer = new ConfigCategory.Builder().set(Multiplayer.class).build();
			public static ConfigCategory server = new ConfigCategory.Builder().set(Server.class).setDestination("server").build();
			public static ConfigCategory lodBuilding = new ConfigCategory.Builder().set(Common.LodBuilding.class).setDestination("common.lodBuilding").build();
			public static ConfigCategory multiThreading = new ConfigCategory.Builder().set(Common.MultiThreading.class).setDestination("common.multiThreading").build();
			public static ConfigCategory autoUpdater = new ConfigCategory.Builder().set(AutoUpdater.class).build();
			
			public static ConfigCategory logging = new ConfigCategory.Builder().set(Common.Logging.class).setDestination("common.logging").build();
			public static ConfigCategory debugging = new ConfigCategory.Builder().set(Debugging.class).build();
			
			
			
			public static class Graphics
			{
				public static ConfigUIComment advancedGraphicsHeader = new ConfigUIComment.Builder().setParentConfigClass(Graphics.class).build();
				
				public static ConfigCategory quality = new ConfigCategory.Builder().set(Quality.class).build();
				public static ConfigUISpacer qualitySpacer = new ConfigUISpacer.Builder().build();
				
				
				public static ConfigEntry<Boolean> enableSsao = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment("Enable Screen Space Ambient Occlusion")
					.build();
				public static ConfigEntry<Boolean> enableAntiAliasing = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment("Enabling Anti-Aliasing smooths edges and reduces flickering when moving.")
					.build();
				public static ConfigUISpacer antiAliasingSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigUiLinkedEntry quickEnableGenericRendering = new ConfigUiLinkedEntry(GenericRendering.enableGenericRendering);
				public static ConfigCategory genericRendering = new ConfigCategory.Builder().set(GenericRendering.class).build();
				public static ConfigUISpacer genericRenderingSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigUiLinkedEntry quickEnableDhFog = new ConfigUiLinkedEntry(Fog.enableDhFog);
				public static ConfigUiLinkedEntry quickEnableMcFog = new ConfigUiLinkedEntry(Fog.enableVanillaFog);
				public static ConfigCategory fog = new ConfigCategory.Builder().set(Fog.class).build();
				public static ConfigUISpacer fogSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigUiLinkedEntry quickEnableTexturedLods = new ConfigUiLinkedEntry(Texture.enableTexturedLods);
				public static ConfigCategory texture = new ConfigCategory.Builder().set(Texture.class).build();
				public static ConfigUiLinkedEntry quickEnableNoiseTexture = new ConfigUiLinkedEntry(NoiseTexture.enableNoiseTexture);
				public static ConfigCategory noiseTexture = new ConfigCategory.Builder().set(NoiseTexture.class).build();
				public static ConfigUISpacer noiseTextureSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigUiLinkedEntry quickEnableCaveCulling = new ConfigUiLinkedEntry(Culling.enableCaveCulling);
				public static ConfigCategory culling = new ConfigCategory.Builder().set(Culling.class).build();
				public static ConfigUISpacer cullingSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigEntry<Boolean> overrideVanillaGraphicsSettings = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment("" +
						"If true some vanilla graphics settings will be automatically changed \n" +
						"during DH setup to provide a better experience. \n" +
						" \n" +
						"IE disabling vanilla clouds (which render on top of DH LODs), \n" +
						"   and chunk fading (DH already fades MC chunks) \n" +
						"")
					.build();
				public static ConfigUISpacer overrideVanillaSpacer = new ConfigUISpacer.Builder().build();
				
				
				
				public static ConfigCategory experimental = new ConfigCategory.Builder().set(Experimental.class).build();
				
				
				
				public static class Quality
				{
					public static ConfigUIComment qualityHeader = new ConfigUIComment.Builder().setParentConfigClass(Quality.class).build();
					
					public static ConfigEntry<Integer> lodChunkRenderDistanceRadius = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(32, 256, 4096)
						.comment("" +
							"The radius of the mod's render distance. (measured in chunks)\n" +
							"\n" +
							"Note: this is a best effort number. \n" +
							"The actual render distance may be above or below this number \n" +
							"depending on your other graphic settings. \n" +
							"")
						.build();
					
					public static ConfigUISpacer qualityDropoffSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<EDhApiHorizontalQuality> horizontalQuality = new ConfigEntry.Builder<EDhApiHorizontalQuality>()
						.set(EDhApiHorizontalQuality.MEDIUM)
						.comment(""
							+ "This indicates how far apart drops in LOD quality are. \n"
							+ "\n"
							+ "Higher settings will increase the distance between drops \n"
							+ "but will increase memory and GPU usage.")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<EDhApiMaxHorizontalResolution> maxHorizontalResolution = new ConfigEntry.Builder<EDhApiMaxHorizontalResolution>()
						.set(EDhApiMaxHorizontalResolution.BLOCK)
						.comment(""
							+ "What is the maximum detail LODs can render at? \n"
							+ "Higher settings will increase memory and GPU usage. \n"
							+ "\n"
							+ EDhApiMaxHorizontalResolution.CHUNK + ": render 1 LOD for each Chunk. \n"
							+ EDhApiMaxHorizontalResolution.HALF_CHUNK + ": render 4 LODs for each Chunk. \n"
							+ EDhApiMaxHorizontalResolution.FOUR_BLOCKS + ": render 16 LODs for each Chunk. \n"
							+ EDhApiMaxHorizontalResolution.TWO_BLOCKS + ": render 64 LODs for each Chunk. \n"
							+ EDhApiMaxHorizontalResolution.BLOCK + ": render 256 LODs for each Chunk (width of one block). \n"
							+ "\n"
							+ "Fastest: " + EDhApiMaxHorizontalResolution.CHUNK + "\n"
							+ "Fanciest: " + EDhApiMaxHorizontalResolution.BLOCK)
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<EDhApiVerticalQuality> verticalQuality = new ConfigEntry.Builder<EDhApiVerticalQuality>()
						.set(EDhApiVerticalQuality.MEDIUM)
						.comment(""
							+ "This indicates how well LODs will represent \n"
							+ "overhangs, caves, floating islands, etc. \n"
							+ "Higher options will make the world more accurate, but"
							+ "will increase memory and GPU usage. \n"
							+ "\n"
							+ "Lowest Quality: " + EDhApiVerticalQuality.HEIGHT_MAP + "\n"
							+ "Highest Quality: " + EDhApiVerticalQuality.PIXEL_ART)
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigUISpacer cameraZoomSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Boolean> useCameraPositionForQualityDropOff = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true DH will try to use the camera position when \n"
							+ "determining LOD quality drop-off. \n"
							+ "If false DH will use the player's position. \n"
							+ " \n"
							+ "Enabling helps free-cam mods render correctly. \n"
							+ "Disabling helps multi-camera mods render correctly (ie Immersive Portals or camera mods). \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> increaseQualityWhenZoomedIn = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true LOD quality will increase when the camera is zoomed in, \n"
							+ "IE when using a spyglass or zoom mod. \n"
							+ "\n"
							+ "Only LODs visible through the camera view are affected. \n"
							+ "\n"
							+ "When zoomed in LODs will load to the same detail level \n"
							+ "they would have if you were close to them. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Integer> maxZoomQualityIncrease = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(1, 4, 6)
						.comment(""
							+ "How many detail levels zooming in can increase LOD quality by. \n"
							+ "Higher numbers allow stronger zooms to render crisper terrain, \n"
							+ "but will increase memory and GPU usage while zoomed in. \n"
							+ " \n"
							+ "A vanilla spyglass needs 4 detail levels to reach its full quality. \n"
							+ "")
						.build();
					
					public static ConfigUISpacer qualitySpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<EDhApiTransparency> transparency = new ConfigEntry.Builder<EDhApiTransparency>()
						.set(EDhApiTransparency.COMPLETE)
						.comment(""
							+ "How should LOD transparency be handled. \n"
							+ "\n"
							+ EDhApiTransparency.COMPLETE + ": LODs will render transparent. \n"
							+ EDhApiTransparency.DISABLED + ": LODs will be opaque. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<EDhApiLodShading> lodShading = new ConfigEntry.Builder<EDhApiLodShading>()
						.set(EDhApiLodShading.AUTO)
						.comment(""
							+ "How should LODs be shaded? \n"
							+ "\n"
							+ EDhApiLodShading.AUTO + ": Uses the same side shading as vanilla Minecraft blocks. \n"
							+ EDhApiLodShading.ENABLED + ": Simulates Minecraft's block shading for LODs. \n"
							+ "              Can be used to force LOD shading when using some shaders. \n"
							+ EDhApiLodShading.DISABLED + ": All LOD sides will be rendered with the same brightness. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<EDhApiGrassSideRendering> grassSideRendering = new ConfigEntry.Builder<EDhApiGrassSideRendering>()
						.set(EDhApiGrassSideRendering.FADE_TO_DIRT)
						.comment(""
							+ "How should the sides and bottom of grass block LODs render? \n"
							+ "\n"
							+ EDhApiGrassSideRendering.AS_GRASS + ": all sides of dirt LOD's render using the top (green) color. \n"
							+ EDhApiGrassSideRendering.FADE_TO_DIRT + ": sides fade from grass to dirt. \n"
							+ EDhApiGrassSideRendering.AS_DIRT + ": sides render entirely as dirt. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigUISpacer fadeSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Boolean> ditherDhFade = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true LODs will fade away as you get closer to them. \n"
							+ "If false LODs will cut off abruptly at a set distance from the camera. \n"
							+ "This setting is affected by the vanilla overdraw prevention config. \n"
							+ "")
						.build();
					
					public static ConfigEntry<EDhApiMcRenderingFadeMode> vanillaFadeMode = new ConfigEntry.Builder<EDhApiMcRenderingFadeMode>()
						.set(EDhApiMcRenderingFadeMode.DOUBLE_PASS)
						.comment(""
							+ "How should vanilla Minecraft fade into Distant Horizons LODs? \n"
							+ "\n"
							+ EDhApiMcRenderingFadeMode.NONE + ": Fastest, there will be a pronounced border between DH and MC rendering. \n"
							+ EDhApiMcRenderingFadeMode.SINGLE_PASS + ": Fades after MC's transparent pass, opaque blocks underwater won't be faded. \n"
							+ EDhApiMcRenderingFadeMode.DOUBLE_PASS + ": Slowest, fades after both MC's opaque and transparent passes, provides the smoothest transition. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Integer> lodBiomeBlending = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(0, 3, 3) // going higher than 3 causes banding issues for blending across LOD borders and an exponential increase in load times
						.comment(""
							+ "This is the same as vanilla Biome Blending settings for Lod area. \n"
							+ "    Note: anything above '0' will slow down LOD loading time. \n"
							+ "\n"
							+ "    '0' equals to Vanilla Biome Blending of '1x1' or 'OFF', \n"
							+ "    '1' equals to Vanilla Biome Blending of '3x3', \n"
							+ "    '2' equals to Vanilla Biome Blending of '5x5'...")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigUISpacer multiplierSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Float> brightnessMultiplier = new ConfigEntry.Builder<Float>()
						.set(1.0f)
						.comment(""
							+ "How bright LOD colors are. \n"
							+ "\n"
							+ "0 = black \n"
							+ "1 = normal \n"
							+ "2 = near white")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<Float> saturationMultiplier = new ConfigEntry.Builder<Float>()
						.set(1.0f)
						.comment(""
							+ "How saturated LOD colors are. \n"
							+ "\n"
							+ "0 = black and white \n"
							+ "1 = normal \n"
							+ "2 = very saturated")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
				}
				
				public static class GenericRendering
				{
					public static ConfigUIComment genericRendererHeader = new ConfigUIComment.Builder().setParentConfigClass(GenericRendering.class).build();
					
					public static ConfigEntry<Boolean> enableGenericRendering = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true non terrain objects will be rendered by DH. \n"
							+ "i.e. beacon beams and clouds. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> enableBeaconRendering = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true LOD beacon beams will be rendered. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Integer> beaconRenderHeight = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(1, 6000, 6_000_000)
						.comment(""
							+ "Sets the maximum height beacons will render up to. \n"
							+ "\n"
							+ "Requires a world re-load to take affect. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> expandDistantBeacons = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true LOD beacon beams will be rendered wider at extreme distances, \n"
							+ "making them easier to see. \n"
							+ "If false all LOD beacon beams will only ever be 1 block wide. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> enableCloudRendering = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true LOD clouds will be rendered. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> enableMultiLayerClouds = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "False = DH will render a single layer of clouds, like vanilla Minecraft. \n"
							+ "True = DH will render 3 layers of clouds at different heights. \n"
							+ "")
						.build();
					
					public static ConfigEntry<String> dimensionEnabledCloudRenderingCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:overworld")
						.setAppearance(EConfigEntryAppearance.ALL)
						.comment(""
							+ "A comma separated separated list of dimension resource locations where DH clouds will render.\n"
							+ "\n"
							+ "Example: \"minecraft:overworld,minecraft:the_end\"\n"
							+ "\n"
							+ "Changes require a world re-load.\n"
							+ "")
						.build();
					
				}
				
				public static class Fog
				{
					private static final Float FOG_RANGE_MIN = 0.0f;
					private static final Float FOG_RANGE_MAX = (float) Math.sqrt(2.0);
					
					
					
					public static ConfigUIComment fogHeader = new ConfigUIComment.Builder().setParentConfigClass(Fog.class).build();
					
					public static ConfigEntry<Boolean> enableDhFog = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "Determines if fog is drawn on DH LODs. \n"
							+ "")
						.build();
					
					public static ConfigEntry<EDhApiFogColorMode> colorMode = new ConfigEntry.Builder<EDhApiFogColorMode>()
						.set(EDhApiFogColorMode.USE_WORLD_FOG_COLOR)
						.comment(""
							+ "What color should fog use? \n"
							+ "\n"
							+ EDhApiFogColorMode.USE_WORLD_FOG_COLOR + ": Use the world's fog color. \n"
							+ EDhApiFogColorMode.USE_SKY_COLOR + ": Use the sky's color.")
						.build();
					
					public static ConfigEntry<Boolean> enableVanillaFog = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment(""
							+ "Should Minecraft's fog render? \n"
							+ "Note: Other mods may conflict with this setting. \n"
							+ "")
						.build();
					
					
					
					public static ConfigEntry<Float> farFogStart = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(FOG_RANGE_MIN, 0.4f, FOG_RANGE_MAX)
						.comment(""
							+ "At what distance should the far fog start? \n"
							+ "\n"
							+ "0.0: Fog starts at the player's position. \n"
							+ "1.0: Fog starts at the closest edge of the vanilla render distance. \n"
							+ "1.414: Fog starts at the corner of the vanilla render distance.")
						.build();
					
					public static ConfigEntry<Float> farFogEnd = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(FOG_RANGE_MIN, 1.0f, FOG_RANGE_MAX)
						.comment(""
							+ "Where should the far fog end? \n"
							+ "\n"
							+ "0.0: Fog ends at player's position.\n"
							+ "1.0: Fog ends at the closest edge of the vanilla render distance. \n"
							+ "1.414: Fog ends at the corner of the vanilla render distance.")
						.build();
					
					public static ConfigEntry<Float> farFogMin = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(-5.0f, 0.0f, FOG_RANGE_MAX)
						.comment(""
							+ "What is the minimum fog thickness? \n"
							+ "\n"
							+ "0.0: No fog. \n"
							+ "1.0: Fully opaque fog.")
						.build();
					
					public static ConfigEntry<Float> farFogMax = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(FOG_RANGE_MIN, 1.0f, 5.0f)
						.comment(""
							+ "What is the maximum fog thickness? \n"
							+ "\n"
							+ "0.0: No fog. \n"
							+ "1.0: Fully opaque fog.")
						.build();
					
					public static ConfigEntry<EDhApiFogFalloff> farFogFalloff = new ConfigEntry.Builder<EDhApiFogFalloff>()
						.set(EDhApiFogFalloff.EXPONENTIAL_SQUARED)
						.comment(""
							+ "How should the fog thickness should be calculated? \n"
							+ "\n"
							+ EDhApiFogFalloff.LINEAR + ": Linear based on distance (will ignore 'density')\n"
							+ EDhApiFogFalloff.EXPONENTIAL + ": 1/(e^(distance*density)) \n"
							+ EDhApiFogFalloff.EXPONENTIAL_SQUARED + ": 1/(e^((distance*density)^2)")
						.build();
					
					public static ConfigEntry<Float> farFogDensity = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(0.01f, 2.5f, 50.0f)
						.comment(""
							+ "Used in conjunction with the Fog Falloff.")
						.build();
					
					public static ConfigCategory heightFog = new ConfigCategory.Builder().set(HeightFog.class).build();
					
					
					
					public static class HeightFog
					{
						public static ConfigUIComment heightFogHeader = new ConfigUIComment.Builder().setParentConfigClass(HeightFog.class).build();
						
						public static ConfigEntry<EDhApiHeightFogMixMode> heightFogMixMode = new ConfigEntry.Builder<EDhApiHeightFogMixMode>()
							.set(EDhApiHeightFogMixMode.SPHERICAL)
							.comment(""
								+ "How should height effect the fog thickness? \n"
								+ "Note: height fog is combined with the other fog settings. \n"
								+ "\n"
								+ EDhApiHeightFogMixMode.SPHERICAL + ": Fog is calculated based on camera distance. \n"
								+ EDhApiHeightFogMixMode.CYLINDRICAL + ": Ignore height, fog is calculated based on horizontal distance. \n"
								+ "\n"
								+ EDhApiHeightFogMixMode.MAX + ": max(heightFog, farFog) \n"
								+ EDhApiHeightFogMixMode.ADDITION + ": heightFog + farFog \n"
								+ EDhApiHeightFogMixMode.MULTIPLY + ": heightFog * farFog \n"
								+ EDhApiHeightFogMixMode.INVERSE_MULTIPLY + ": 1 - (1-heightFog) * (1-farFog) \n"
								+ EDhApiHeightFogMixMode.LIMITED_ADDITION + ": farFog + max(farFog, heightFog) \n"
								+ EDhApiHeightFogMixMode.MULTIPLY_ADDITION + ": farFog + farFog * heightFog \n"
								+ EDhApiHeightFogMixMode.INVERSE_MULTIPLY_ADDITION + ": farFog + 1 - (1-heightFog) * (1-farFog) \n"
								+ EDhApiHeightFogMixMode.AVERAGE + ": farFog*0.5 + heightFog*0.5 \n"
								+ "\n")
							.build();
						
						public static ConfigEntry<EDhApiHeightFogDirection> heightFogDirection = new ConfigEntry.Builder<EDhApiHeightFogDirection>()
							.set(EDhApiHeightFogDirection.BELOW_SET_HEIGHT)
							.comment(""
								+ "Where should the height fog start? \n"
								+ "\n"
								+ EDhApiHeightFogDirection.ABOVE_CAMERA + ": Height fog starts at the camera and goes towards the sky \n"
								+ EDhApiHeightFogDirection.BELOW_CAMERA + ": Height fog starts at the camera and goes towards the void \n"
								+ EDhApiHeightFogDirection.ABOVE_AND_BELOW_CAMERA + ": Height fog starts from the camera to goes towards both the sky and void \n"
								+ EDhApiHeightFogDirection.ABOVE_SET_HEIGHT + ": Height fog starts from a set height and goes towards the sky \n"
								+ EDhApiHeightFogDirection.BELOW_SET_HEIGHT + ": Height fog starts from a set height and goes towards the void \n"
								+ EDhApiHeightFogDirection.ABOVE_AND_BELOW_SET_HEIGHT + ": Height fog starts from a set height and goes towards both the sky and void")
							.build();
						
						public static ConfigEntry<Float> heightFogBaseHeight = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(-3_000_000.0f, 80.0f, 3_000_000.0f)
							.comment("If the height fog is calculated around a set height, what is that height position?")
							.build();
						
						public static ConfigEntry<Float> heightFogStart = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(FOG_RANGE_MIN, 0.0f, FOG_RANGE_MAX)
							.comment(""
								+ "Should the start of the height fog be offset? \n"
								+ "\n"
								+ "0.0: Fog start with no offset.\n"
								+ "1.0: Fog start with offset of the entire world's height. (Includes depth)")
							.build();
						
						public static ConfigEntry<Float> heightFogEnd = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(FOG_RANGE_MIN, 0.6f, FOG_RANGE_MAX)
							.comment(""
								+ "Should the end of the height fog be offset? \n"
								+ "\n"
								+ "0.0: Fog end with no offset.\n"
								+ "1.0: Fog end with offset of the entire world's height. (Include depth)")
							.build();
						
						public static ConfigEntry<Float> heightFogMin = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(0.0f, 0.0f, FOG_RANGE_MAX)
							.comment(""
								+ "What is the minimum fog thickness? \n"
								+ "\n"
								+ "0.0: No fog. \n"
								+ "1.0: Fully opaque fog.")
							.build();
						
						public static ConfigEntry<Float> heightFogMax = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(FOG_RANGE_MIN, 1.0f, 5.0f)
							.comment(""
								+ "What is the maximum fog thickness? \n"
								+ "\n"
								+ "0.0: No fog. \n"
								+ "1.0: Fully opaque fog.")
							.build();
						
						public static ConfigEntry<EDhApiFogFalloff> heightFogFalloff = new ConfigEntry.Builder<EDhApiFogFalloff>()
							.set(EDhApiFogFalloff.EXPONENTIAL_SQUARED)
							.comment(""
								+ "How should the height fog thickness should be calculated? \n"
								+ "\n"
								+ EDhApiFogFalloff.LINEAR + ": Linear based on height (will ignore 'density')\n"
								+ EDhApiFogFalloff.EXPONENTIAL + ": 1/(e^(height*density)) \n"
								+ EDhApiFogFalloff.EXPONENTIAL_SQUARED + ": 1/(e^((height*density)^2)")
							.build();
						
						public static ConfigEntry<Float> heightFogDensity = new ConfigEntry.Builder<Float>()
							.setMinDefaultMax(0.01f, 20.0f, 50.0f)
							.comment("What is the height fog's density?")
							.build();
						
					}
					
				}
				
				public static class NoiseTexture
				{
					public static ConfigUIComment noiseTextureHeader = new ConfigUIComment.Builder().setParentConfigClass(NoiseTexture.class).build();
					
					public static ConfigEntry<Boolean> enableNoiseTexture = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "Should a noise texture be applied to LODs? \n"
							+ "\n"
							+ "This is done to simulate textures and make the LODs appear more detailed. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Integer> noiseSteps = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(1, 4, null)
						.comment(""
							+ "How many steps of noise should be applied to LODs?"
							+ "")
						.build();
					
					public static ConfigEntry<Float> noiseIntensity = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(0f, 0.05f, 1f)
						.comment(""
							+ "How intense should the noise should be?")
						.build();
					
					public static ConfigEntry<Integer> noiseDropoff = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(0, 1024, null)
						.comment(""
							+ "Defines how far should the noise texture render before it fades away. (in blocks) \n"
							+ "Set to 0 to disable noise from fading away \n"
							+ "")
						.build();
					
				}
				
				public static class Texture
				{
					public static ConfigEntry<Boolean> enableTexturedLods = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true nearby and zoomed-in LODs will render with their block's \n"
							+ "actual texture instead of a single flat color. \n"
							+ " \n"
							+ "Only applies to high detail LODs where the texture is actually visible, \n"
							+ "distant LODs always use flat colors. \n"
							+ "Some shader packs nay not have an affect. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<Integer> maxTexturedLodDetailLevel = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(0, 2, 8)
						.comment(""
							+ "The highest detail level number that can render with block textures. \n"
							+ "At higher detail levels each LOD covers multiple blocks, \n"
							+ "which can essentially make textures smaller than a pixel and therefore invisible. \n"
							+ " \n"
							+ "Larger numbers texture more distant LODs \n"
							+ "but increase memory usage. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<String> blocksDontRenderTextureCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:bamboo")
						.setAppearance(EConfigEntryAppearance.ALL)
						.comment(""
							+ "A comma separated list of block resource locations \n"
							+ "that DH won't render textures on. \n"
							+ "Partial matches/incomplete resource locations will also match. \n"
							+ "\n"
							+ "Example: \"minecraft:grass_block,nylium\" \n"
							+ "\n"
							+ "Changes require a restart. \n"
							+ "")
						.build();
					
					public static ConfigEntry<String> blocksDontUseSideTextureCsv = new ConfigEntry.Builder<String>()
						.set("grass_block," +
							"mycelium," +
							"nylium," +
							"dirt_path," +
							"grass" +
							"")
						.setAppearance(EConfigEntryAppearance.ALL)
						.comment(""
							+ "A comma separated list of block resource locations \n"
							+ "that DH will render their sides using the bottom texture. \n"
							+ "Partial matches/incomplete resource locations will also match. \n"
							+ "\n"
							+ "Example: \"minecraft:grass_block,nylium\" \n"
							+ "\n"
							+ "Changes require a restart. \n"
							+ "")
						.build();
					
					public static ConfigEntry<String> blockTagsDontUseSideTextureCsv = new ConfigEntry.Builder<String>()
						.set("grass_blocks," +
							"nylium")
						.setAppearance(EConfigEntryAppearance.ALL)
						.comment(""
							+ "A comma separated list of tag names \n"
							+ "that DH will render their sides using the bottom texture. \n"
							+ "\n"
							+ "Example: \"grass_blocks,nylium\" \n"
							+ "\n"
							+ "Changes require a restart. \n"
							+ "")
						.build();
					
					public static ConfigEntry<String> blocksAlwaysRasterizeTextureCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:beacon")
						.setAppearance(EConfigEntryAppearance.ALL)
						.comment(""
							+ "A comma separated list of block resource locations \n"
							+ "that DH will use rasterization to determine \n"
							+ "the face textures.\n"
							+ "\n"
							+ "Can be used to fix issues with mods/blocks where\n"
							+ "a side uses just part of a modeled block's texture. \n"
							+ "\n"
							+ "Example: \"minecraft:beacon\" \n"
							+ "\n"
							+ "Changes require a restart. \n"
							+ "")
						.build();
					
				}
				
				public static class Culling
				{
					public static ConfigUIComment cullingHeader = new ConfigUIComment.Builder().setParentConfigClass(Culling.class).build();
					
					public static ConfigEntry<Float> overdrawPrevention = new ConfigEntry.Builder<Float>()
						.setMinDefaultMax(-1.0f, -1.0f, 1.0f)
						.comment(""
							+ "Determines how far from the camera Distant Horizons will start rendering. \n"
							+ "Measured as a percentage of the vanilla render distance.\n"
							+ "\n"
							+ "-1 = auto, overdraw will change based on the vanilla render distance.\n"
							+ "\n"
							+ "Higher values will prevent LODs from rendering behind vanilla blocks at a higher distance,\n"
							+ "but may cause holes in the world. \n"
							+ "Holes are most likely to appear when flying through unloaded terrain. \n"
							+ "\n"
							+ "Increasing the vanilla render distance increases the effectiveness of this setting."
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> reduceOverdrawWithFastMovement = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If set to true the overdraw prevention radius will get closer\n"
							+ "to the camera when flying/moving quickly.\n"
							+ "\n"
							+ "This helps reduce issues where Minecraft can't load or\n"
							+ "generate chunks fast enough to keep up with DH.\n"
							+ "")
						.build();
					
					public static ConfigUISpacer speedSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Boolean> enableCaveCulling = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If enabled caves won't be rendered. \n"
							+ "\n"
							+ " Note: for some world types this can cause \n"
							+ " overhangs or walls for floating objects. \n"
							+ " Tweaking the caveCullingHeight, can resolve some \n"
							+ " of those issues. \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigEntry<Integer> caveCullingHeight = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(-4096, 60, 4096)
						.comment(""
							+ "At what Y value should cave culling start? \n"
							+ "Lower this value if you get walls for areas with 0 light.")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
					public static ConfigUISpacer caveCullingSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Boolean> disableBeaconDistanceCulling = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If false all beacons near the camera won't be drawn to prevent vanilla overdraw. \n"
							+ "If true all beacons will be rendered. \n"
							+ "\n"
							+ "Generally this should be left as true. It's main purpose is for debugging\n"
							+ "beacon updating/rendering.\n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> disableFrustumCulling = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment(""
							+ "If true LODs outside the player's camera \n"
							+ "aren't drawn, increasing GPU performance. \n"
							+ "\n"
							+ "If false all LODs are drawn, even those behind \n"
							+ "the player's camera, decreasing GPU performance. \n"
							+ "\n"
							+ "Disable this if you see LODs disappearing at the corners of your vision.")
						.build();
					
					public static ConfigEntry<Boolean> disableShadowPassFrustumCulling = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment(""
							+ "Identical to the other frustum culling option\n"
							+ "only used when a shader mod is present using the DH API\n"
							+ "and the shadow pass is being rendered.\n"
							+ "\n"
							+ "Disable this if shadows render incorrectly.")
						.build();
					
					public static ConfigUISpacer ignoreCsvStartSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<String> ignoredRenderBlockCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:barrier,minecraft:structure_void,minecraft:light,minecraft:tripwire,minecraft:brown_mushroom")
						.setAppearance(EConfigEntryAppearance.ALL)
						.addListener(RenderBlockCacheCsvHandler.INSTANCE)
						.comment(""
							+ "A comma separated list of block resource locations that won't be rendered by DH. \n"
							+ "Air is always included in this list. \n"
							+ "\n"
							+ "Note:\n"
							+ "If you see gaps, or holes you may have to change\n"
							+ "worldCompression to [" + EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS + "] and re-generate the LODs.\n"
							+ "")
						.build();
					
					public static ConfigEntry<String> ignoredRenderCaveBlockCsv = new ConfigEntry.Builder<String>()
						.set("")
						.setAppearance(EConfigEntryAppearance.ALL)
						.addListener(RenderBlockCacheCsvHandler.INSTANCE)
						.comment(""
							+ "A comma separated list of block resource locations that shouldn't be rendered \n"
							+ "if they are in a 0 sky light underground area. \n"
							+ "Air is always included in this list. \n"
							+ "\n"
							+ "Defaults to an empty list since most cave blocks will be automatically ignored due to being: \n"
							+ "transparent, non-solid, or liquids, but new blocks can be added here if needed.\n"
							+ "")
						.build();
					
					public static ConfigEntry<String> waterSubSurfaceBlockReplacementCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:kelp,minecraft:tall_seagrass,minecraft:seagrass")
						.setAppearance(EConfigEntryAppearance.ALL)
						.addListener(RenderBlockCacheCsvHandler.INSTANCE)
						.comment(""
							+ "A comma separated list of block resource locations that will be replaced by water \n"
							+ "if they're visible on the water's surface. \n"
							+ "")
						.build();
					
					public static ConfigEntry<String> waterSurfaceBlockReplacementCsv = new ConfigEntry.Builder<String>()
						.set("minecraft:lily_pad")
						.setAppearance(EConfigEntryAppearance.ALL)
						.addListener(RenderBlockCacheCsvHandler.INSTANCE)
						.comment(""
							+ "A comma separated list of block resource locations that will be removed \n"
							+ "when on top of water. \n"
							+ "")
						.build();
					
					public static ConfigUISpacer blockSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<EDhApiBlocksToAvoid> blocksToIgnore = new ConfigEntry.Builder<EDhApiBlocksToAvoid>()
						.set(EDhApiBlocksToAvoid.NON_COLLIDING)
						.comment(""
							+ "Defines what blocks should be rendered as LODs. \n"
							+ "\n"
							+ EDhApiBlocksToAvoid.NONE + ": Include all blocks in the LODs \n"
							+ EDhApiBlocksToAvoid.NON_COLLIDING + ": Only render solid blocks in the LODs (tall grass, torches, etc. will be ignored) \n"
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					public static ConfigEntry<Boolean> tintWithAvoidedBlocks = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "Should the blocks underneath avoided blocks gain the color of the avoided block? \n"
							+ "\n"
							+ "True: a red flower will tint the grass below it red. \n"
							+ "False: skipped blocks will not change color of surface below them. "
							+ "")
						.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
						.build();
					
				}
				
				public static class Experimental
				{
					public static ConfigUIComment experimentalHeader = new ConfigUIComment.Builder().setParentConfigClass(Experimental.class).build();
					
					public static ConfigEntry<Integer> earthCurveRatio = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(-5000, 0, 5000)
						.comment(""
							+ "This is the earth size ratio when applying the curvature shader effect. \n"
							+ "Note: Enabling this feature may cause rendering bugs. \n"
							+ "\n"
							+ "0 = flat/disabled \n"
							+ "1 = 1 to 1 (6,371,000 blocks) \n"
							+ "100 = 1 to 100 (63,710 blocks) \n"
							+ "10000 = 1 to 10000 (637.1 blocks) \n"
							+ "\n"
							+ "Note: Due to current limitations, the min value is [" + WorldCurvatureConfigEventHandler.MIN_VALID_CURVE_VALUE + "] \n"
							+ "and the max value is 5000. Any values outside this range \n"
							+ "will be set to 0 (disabled).")
						.addListener(WorldCurvatureConfigEventHandler.INSTANCE)
						.build();
					
					public static ConfigEntry<String> ignoredDimensionCsv = new ConfigEntry.Builder<String>()
						.set("")
						.comment(""
							+ "A comma separated list of dimension resource locations where DH won't render. \n"
							+ "\n"
							+ "Example: \"minecraft:the_nether,minecraft:the_end\"\n"
							+ "For Minecraft 1.12.2 and older: \n"
							+ "Open the F3 screen and copy the dimension_name:ID shown in yellow. Example: \"overworld:0\"\n"
							+ "\n"
							+ "Note:\n"
							+ "Some DH settings will be disabled and/or changed to improve \n"
							+ "visuals when DH rendering is disabled. \n"
							+ "")
						.addListener(IgnoredDimensionCsvHandler.INSTANCE)
						.build();
					
					public static ConfigEntry<EDhApiRenderingEngine> renderingEngine = new ConfigEntry.Builder<EDhApiRenderingEngine>()
						.set(EDhApiRenderingEngine.AUTO)
						.comment(""
							+ "Requires a restart to change. \n"
							+ " \n"
							+ "Options: \n"
							+ EDhApiRenderingEngine.AUTO + " - changes based on the most likely API for that MC version \n"
							+ EDhApiRenderingEngine.OPEN_GL + " - The Default for MC 1.21.11 and older (supports Iris shaders) \n"
							+ EDhApiRenderingEngine.BLAZE_3D + " - The Default for MC 26.1.2 and newer (supports Vulkan) \n"
							+ "")
						.build();
					
					
				}
				
			}
			
			public static class AutoUpdater
			{
				public static ConfigUIComment autoUpdaterHeader = new ConfigUIComment.Builder().setParentConfigClass(AutoUpdater.class).build();
				
				public static ConfigEntry<Boolean> enableAutoUpdater = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "Automatically check for updates on game launch? \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> enableSilentUpdates = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "Should Distant Horizons silently, automatically download and install new versions? \n"
						+ "This setting is force disabled on dedicated servers for stability reasons. \n"
						+ "")
					.build();
				
				public static ConfigEntry<EDhApiUpdateBranch> updateBranch = new ConfigEntry.Builder<EDhApiUpdateBranch>()
					.set(EDhApiUpdateBranch.AUTO)
					.comment(""
						+ "If DH should use the nightly (provided by Gitlab), or stable (provided by Modrinth) build. \n"
						+ "If [" + EDhApiUpdateBranch.AUTO + "] is selected DH will update to new stable releases if the current jar is a stable jar \n"
						+ "and will update to new nightly builds if the current jar is a nightly jar (IE the version number ends in '-dev')."
						+ "")
					.build();
				
			}
			
			public static class Multiplayer
			{
				public static ConfigUIComment multiplayerHeader = new ConfigUIComment.Builder().setParentConfigClass(Multiplayer.class).build();
				
				public static ConfigEntry<EDhApiServerFolderNameMode> serverFolderNameMode = new ConfigEntry.Builder<EDhApiServerFolderNameMode>()
					.set(EDhApiServerFolderNameMode.NAME_ONLY)
					.comment(""
						+ "How should multiplayer save folders should be named? \n"
						+ "\n"
						+ EDhApiServerFolderNameMode.NAME_ONLY + ": Example: \"Minecraft Server\" \n"
						+ EDhApiServerFolderNameMode.IP_ONLY + ": Example: \"192.168.1.40\" \n"
						+ EDhApiServerFolderNameMode.NAME_IP + ": Example: \"Minecraft Server IP 192.168.1.40\" \n"
						+ EDhApiServerFolderNameMode.NAME_IP_PORT + ": Example: \"Minecraft Server IP 192.168.1.40:25565\""
						+ EDhApiServerFolderNameMode.NAME_IP_PORT_MC_VERSION + ": Example: \"Minecraft Server IP 192.168.1.40:25565 GameVersion 1.16.5\"")
					.build();
				
			}
			
			public static class Debugging
			{
				public static ConfigUIComment debuggingHeader = new ConfigUIComment.Builder().setParentConfigClass(Debugging.class).build();
				
				public static ConfigEntry<EDhApiRendererMode> rendererMode = new ConfigEntry.Builder<EDhApiRendererMode>()
					.set(EDhApiRendererMode.DEFAULT)
					.comment(""
						+ "What renderer is active? \n"
						+ "\n"
						+ EDhApiRendererMode.DEFAULT + ": Default lod renderer \n"
						+ EDhApiRendererMode.DEBUG_TRIANGLE + ": Debug testing renderer \n"
						+ EDhApiRendererMode.DISABLED + ": Disable rendering")
					.build();
				
				public static ConfigEntry<EDhApiDebugRendering> debugRenderingColors = new ConfigEntry.Builder<EDhApiDebugRendering>()
					.set(EDhApiDebugRendering.OFF)
					.comment(""
						+ "Should specialized colors/rendering modes be used? \n"
						+ "\n"
						+ EDhApiDebugRendering.OFF + ": LODs will be drawn with their normal colors. \n"
						+ EDhApiDebugRendering.SHOW_DETAIL + ": LODs' color will be based on their detail level. \n"
						+ EDhApiDebugRendering.SHOW_BLOCK_MATERIAL + ": LODs' color will be based on their material. \n"
						+ EDhApiDebugRendering.SHOW_OVERLAPPING_QUADS + ": LODs will be drawn with total white, but overlapping quads will be drawn with red. \n"
						+ "")
					.addListener(ReloadLodsConfigEventHandler.DELAYED_INSTANCE)
					.build();
				
				public static ConfigUISpacer debugRenderingSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigEntry<Boolean> enableWhiteWorld = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "Stops vertex colors from being passed. \n"
						+ "Useful for debugging shaders")
					.build();
				
				public static ConfigEntry<Boolean> lodOnlyMode = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If enabled this will disable (most) vanilla Minecraft rendering. \n"
						+ "\n"
						+ "NOTE: Do not report any issues when this mode is on! \n"
						+ "   This setting is only for fun and debugging. \n"
						+ "   Mod compatibility is not guaranteed.")
					.build();
				
				public static ConfigEntry<Boolean> renderWireframe = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If enabled the LODs will render as wireframe."
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showOverlappingQuadErrors = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If true overlapping quads will be rendered as bright red for easy identification. \n"
						+ "If false the quads will be rendered normally. \n"
						+ "")
					.build();
				
				public static ConfigUISpacer miscSpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigEntry<Boolean> enableDebugKeybindings = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If true several keys can be used to toggle debug states. \n"
						+ "F6 - enable/disable LOD rendering \n"
						+ "F7 - enable/disable LOD only rendering \n"
						+ "F8 - cycle through the different debug rendering modes \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> logBufferGarbageCollection = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If true OpenGL Buffer garbage collection will be logged \n"
						+ "this also includes the number of live buffers. \n"
						+ "")
					.build();
				
				public static ConfigUISpacer unsafeSpacer = new ConfigUISpacer.Builder().build();
				
				// Note: This will reset on game restart, and should have a warning on the tooltip
				public static ConfigEntry<Boolean> allowUnsafeValues = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
					.addListener(UnsafeValuesConfigListener.INSTANCE)
					.build();
				
				public static ConfigUISpacer categorySpacer = new ConfigUISpacer.Builder().build();
				
				public static ConfigCategory debugWireframe = new ConfigCategory.Builder().set(DebugWireframe.class).build();
				public static ConfigCategory openGl = new ConfigCategory.Builder().set(OpenGl.class).build();
				public static ConfigCategory columnBuilderDebugging = new ConfigCategory.Builder().set(ColumnBuilderDebugging.class).build();
				public static ConfigCategory positionFinderDebugging = new ConfigCategory.Builder().set(PositionFinder.class).build();
				public static ConfigCategory f3Screen = new ConfigCategory.Builder().set(F3Screen.class).build();
				public static ConfigCategory exampleConfigScreen = new ConfigCategory.Builder().set(ExampleConfigScreen.class).build();
				
				
				
				public static class DebugWireframe
				{
					public static ConfigUIComment debugWireframeHeader = new ConfigUIComment.Builder().setParentConfigClass(DebugWireframe.class).build();
					
					public static ConfigEntry<Boolean> enableRendering = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment(""
							+ "If enabled, various wireframes for debugging internal functions will be drawn. \n"
							+ "\n"
							+ "NOTE: There WILL be performance hit! \n"
							+ "   Additionally, only stuff that's loaded after you enable this \n"
							+ "   will render their debug wireframes. \n"
							+ "")
						.build();
					
					public static ConfigUISpacer wireframeOptionSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Boolean> showWorldGenQueue = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Render queued world gen tasks?")
						.build();
					
					public static ConfigEntry<Boolean> showNetworkSyncOnLoadQueue = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Render queued network sync on load tasks?")
						.build();
					
					public static ConfigEntry<Boolean> showRenderSectionStatus = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Render LOD section status?")
						.build();
					
					public static ConfigEntry<Boolean> showQuadTreeRenderStatus = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Render Quad Tree Rendering status?")
						.build();
					
					public static ConfigEntry<Boolean> showFullDataUpdateStatus = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Render full data update/lock status?")
						.build();
					
				}
				
				public static class OpenGl
				{
					public static ConfigUIComment openGlHeader = new ConfigUIComment.Builder().setParentConfigClass(OpenGl.class).build();
					
					public static ConfigEntry<Boolean> overrideVanillaGLLogger = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "Defines how OpenGL errors are handled. \n "
							+ "Requires rebooting Minecraft to change. \n"
							+ "Will catch OpenGL errors thrown by other mods. \n"
							+ "")
						.build();
					
					public static ConfigEntry<Boolean> onlyLogGlErrorsOnce = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment(""
							+ "If true each Open GL error will only be logged once. \n"
							+ "Enabling this may cause some error logs to be missed. \n"
							+ "Does nothing if overrideVanillaGLLogger is set to false. \n"
							+ " \n"
							+ "Generally this can be kept as 'true' to prevent log spam. \n"
							+ "However, Please set this to 'false' if a developer needs your log to debug a GL issue. \n"
							+ "")
						.build();
					
					public static ConfigEntry<EDhApiGLErrorHandlingMode> glErrorHandlingMode = new ConfigEntry.Builder<EDhApiGLErrorHandlingMode>()
						.set(ModInfo.IS_DEV_BUILD ? EDhApiGLErrorHandlingMode.LOG : EDhApiGLErrorHandlingMode.IGNORE)
						.comment(""
							+ "Defines how OpenGL errors are handled. \n"
							+ "May incorrectly catch OpenGL errors thrown by other mods. \n"
							+ "\n"
							+ EDhApiGLErrorHandlingMode.IGNORE + ": Do nothing. \n"
							+ EDhApiGLErrorHandlingMode.LOG + ": write an error to the log. \n"
							+ EDhApiGLErrorHandlingMode.LOG_THROW + ": write to the log and throw an exception. \n"
							+ "           Warning: this should only be enabled when debugging the LOD renderer \n"
							+ "           as it may break Minecraft's renderer when an exception is thrown. \n"
							+ "")
						.build();
					
				}
				
				public static class ColumnBuilderDebugging
				{
					public static ConfigUIComment columnBuilderDebuggingHeader = new ConfigUIComment.Builder().setParentConfigClass(ColumnBuilderDebugging.class).build();
					
					public static ConfigEntry<Boolean> columnBuilderDebugEnable = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					public static ConfigEntry<Integer> columnBuilderDebugDetailLevel = new ConfigEntry.Builder<Integer>()
						.set((int) DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					public static ConfigEntry<Integer> columnBuilderDebugXPos = new ConfigEntry.Builder<Integer>()
						.set(0)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					public static ConfigEntry<Integer> columnBuilderDebugZPos = new ConfigEntry.Builder<Integer>()
						.set(0)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					
					public static ConfigUISpacer subLodSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Integer> columnBuilderDebugXRow = new ConfigEntry.Builder<Integer>()
						.set(-1)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					public static ConfigEntry<Integer> columnBuilderDebugZRow = new ConfigEntry.Builder<Integer>()
						.set(-1)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					public static ConfigEntry<Integer> columnBuilderDebugColumnIndex = new ConfigEntry.Builder<Integer>()
						.set(-1)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_GUI)
						.addListener(ReloadLodsConfigEventHandler.INSTANT_INSTANCE)
						.build();
					
				}
				
				public static class PositionFinder
				{
					//public static ConfigUIComment positionFinderHeader = new ConfigUIComment.Builder().setParentConfigClass(ColumnBuilderDebugging.class).build();
					
					public static ConfigEntry<Boolean> positionFinderEnable = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.build();
					
					public static ConfigEntry<Integer> positionFinderDetailLevel = new ConfigEntry.Builder<Integer>()
						.set((int) DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
						.build();
					public static ConfigEntry<Integer> positionFinderXPos = new ConfigEntry.Builder<Integer>()
						.set(0)
						.build();
					public static ConfigEntry<Integer> positionFinderZPos = new ConfigEntry.Builder<Integer>()
						.set(0)
						.build();
					
					public static ConfigUISpacer positionFinderBlockPosSpacer = new ConfigUISpacer.Builder().build();
					
					public static ConfigEntry<Integer> positionFinderMinBlockY = new ConfigEntry.Builder<Integer>()
						.set(-64)
						.build();
					public static ConfigEntry<Integer> positionFinderMaxBlockY = new ConfigEntry.Builder<Integer>()
						.set(125)
						.build();
					public static ConfigEntry<Float> positionFinderMarginPercent = new ConfigEntry.Builder<Float>()
						.set(0.0f)
						.build();
					
				}
				
				public static class F3Screen
				{
					public static ConfigUIComment f3ScreenHeader = new ConfigUIComment.Builder().setParentConfigClass(F3Screen.class).build();
					
					public static ConfigEntry<Boolean> showPlayerPos = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment("Shows the player's LOD position.")
						.build();
					public static ConfigEntry<Integer> playerPosSectionDetailLevel = new ConfigEntry.Builder<Integer>()
						.setMinDefaultMax(6, 6, 16)
						.comment("" +
							"Defines what internal detail level the player position will be shown as. \n" +
							"Internal detail level means: 6 = 1x1 block, 7 = 2x2 blocks, etc. \n" +
							"")
						.build();
					
					public static ConfigEntry<Boolean> showThreadPools = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment("Shows info about each thread pool.")
						.build();
					
					public static ConfigEntry<Boolean> showRenderThreadTasks = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Shows info about the render thread tasks.")
						.build();
					
					public static ConfigEntry<Boolean> showCombinedObjectPools = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Shows the combined memory use and array counts for all DH pooled objects.")
						.build();
					public static ConfigEntry<Boolean> showSeparatedObjectPools = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.comment("Shows the memory use and array counts for each DH object pool.")
						.build();
					
					public static ConfigEntry<Boolean> showQueuedChunkUpdateCount = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment("Shows how many chunks are queued for processing and the max count that can be queued.")
						.build();
					
					public static ConfigEntry<Boolean> showLevelStatus = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment("Shows what levels are loaded and world gen/rendering info about those levels.")
						.build();
					
					public static ConfigEntry<Boolean> onlyShowRenderingLevels = new ConfigEntry.Builder<Boolean>()
						.set(true)
						.comment("Only show levels that DH is actively rendering.")
						.build();
					
				}
				
				/** This class is used to debug the different features of the config GUI */
				// FIXME: WARNING: Some of the options in this class dont get show n in the default UI
				// This will throw a warning when opened in the default ui to tell you about it not showing
				public static class ExampleConfigScreen
				{
					public static ConfigUIComment exampleConfigHeader = new ConfigUIComment.Builder().setParentConfigClass(ExampleConfigScreen.class).build();
					
					// Defined in the lang, just a note about this screen
					public static ConfigUIComment debugConfigScreenNote = new ConfigUIComment.Builder().setTextPosition(EConfigCommentTextPosition.CENTER_OF_SCREEN).build();
					
					public static ConfigEntry<Boolean> boolTest = new ConfigEntry.Builder<Boolean>()
						.set(false)
						.build();
					
					public static ConfigEntry<Byte> byteTest = new ConfigEntry.Builder<Byte>()
						.set((byte) 8)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigEntry<Integer> intTest = new ConfigEntry.Builder<Integer>()
						.set(69420)
						.build();
					
					public static ConfigEntry<Double> doubleTest = new ConfigEntry.Builder<Double>()
						.set(420.69d)
						.build();
					
					public static ConfigEntry<Short> shortTest = new ConfigEntry.Builder<Short>()
						.set((short) 69)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigEntry<Long> longTest = new ConfigEntry.Builder<Long>()
						.set(42069L)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigEntry<Float> floatTest = new ConfigEntry.Builder<Float>()
						.set(0.42069f)
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigEntry<String> stringTest = new ConfigEntry.Builder<String>()
						.set("Test input box")
						.build();
					
					public static ConfigEntry<List<String>> listTest = new ConfigEntry.Builder<List<String>>()
						.set(new ArrayList<>(Arrays.asList("option 1", "option 2", "option 3")))
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigEntry<Map<String, String>> mapTest = new ConfigEntry.Builder<Map<String, String>>()
						.set(new HashMap<>())
						.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // no GUI renderer set up currently
						.build();
					
					public static ConfigUIButton uiButtonTest = new ConfigUIButton(() ->
					{
						// running on a separate thread is necessary to prevent locking
						new Thread(ExampleConfigScreen::onButtonPressed).start();
					});
					public static void onButtonPressed()
					{
						IMinecraftClientWrapper mcClient = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
						
						LOGGER.info("Attempting to show tinyfd message box...");
						mcClient.showDialog("Button pressed!", "UITester dialog", "ok", "info");
						LOGGER.info("dialog closed");
					}
					
					public static ConfigCategory categoryTest = new ConfigCategory.Builder().set(CategoryTest.class).build();
					
					public static ConfigEntry<Integer> linkableTest = new ConfigEntry.Builder<Integer>()
						.set(420)
						.build();
					
					
					public static class CategoryTest
					{
						// The name of this can be anything as it will be overwritten by the name of the linked object
						public static ConfigUiLinkedEntry linkableTest = new ConfigUiLinkedEntry(ExampleConfigScreen.linkableTest);
						
					}
					
				}
				
			}
			
		}
		
	}
	
	public static class Common
	{
		public static class WorldGenerator
		{
			public static ConfigUIComment worldGeneratorHeader = new ConfigUIComment.Builder().setParentConfigClass(WorldGenerator.class).build();
			
			public static ConfigEntry<EDhApiGeneratorPlan> generatorPlan = new ConfigEntry.Builder<EDhApiGeneratorPlan>()
				.setChatCommandName("generation.plan")
				.set(EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS)
				.setShowEnumOptionFunc(WorldGenPlanConfigEventHandler::setShowEnumOptionFunc)
				.addListener(WorldGenPlanConfigEventHandler.INSTANCE)
				.comment(""
					+ "Defines how LODs will be generated \n"
					+ "outside the vanilla render distance. \n"
					+ "In multiplayer, this may be overridden by the server. \n"
					+ "\n"
					+ EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS + " \n"
					+ "The rough surface will be generated first \n"
					+ "then chunks will be generated to fill in \n"
					+ "missing features (ie trees and villages). \n"
					+ "\n"
					+ "Recommended for modded or vanilla worlds. \n"
					+ "\n"
					+ EDhApiGeneratorPlan.SURFACE_ONLY + " \n"
					+ "Only the rough surface will be generated. \n"
					+ "Chunk features like trees and villages \n"
					+ "won't be generated. \n"
					+ "\n"
					+ "Recommended for computers that don't have \n"
					+ "enough CPU power or RAM space to handle \n"
					+ "the chunk generator. \n"
					+ "\n"
					+ EDhApiGeneratorPlan.CHUNKS_ONLY + " \n"
					+ "Slower than either surface options, \n"
					+ "but provides more accurate terrain. \n"
					+ "\n"
					+ "Recommended for custom worlds \n"
					+ "where the surface generator would \n"
					+ "generate incorrect terrain. \n"
					+ "\n"
					+ EDhApiGeneratorPlan.DISABLED + " \n"
					+ "No distant terrain will be generated. \n"
					+ "\n"
					+ "")
				.build();
			
			public static ConfigEntry<EDhApiDistantGeneratorMode> chunkGeneratorMode = new ConfigEntry.Builder<EDhApiDistantGeneratorMode>()
				.setChatCommandName("generation.chunkMode")
				.set(EDhApiDistantGeneratorMode.FEATURES)
				.comment(""
					+ "How detailed should LODs be generated outside the vanilla render distance? \n"
					+ "\n"
					+ EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY + " \n"
					+ "Only create LOD data for already generated chunks. \n"
					+ "\n"
					+ EDhApiDistantGeneratorMode.FEATURES + " \n"
					+ "Generate everything except structures. \n"
					+ "Note: This may cause world generator bugs or instability when paired with certain world generator mods. \n"
					+ "\n"
					+ EDhApiDistantGeneratorMode.INTERNAL_SERVER + " \n"
					+ "Ask the local server to generate/load each chunk. \n"
					+ "This is the most compatible and will generate structures correctly, \n"
					+ "but may cause server/simulation lag. \n"
					+ "Note: unlike other modes this option DOES save generated chunks to \n"
					+ "Minecraft's region files. \n"
					+ "")
				.build();
			
			public static ConfigUISpacer genLimitingSpacer = new ConfigUISpacer.Builder().build();
			
			public static final ConfigEntry<Boolean> limitIfServerUnhealthy = new ConfigEntry.Builder<Boolean>()
				.set(true)
				.comment(""
					+ "If the Minecraft server thread is taking too long \n"
					+ "Distant Horizons will pause world gen to reduce server stuttering/lag. \n"
					+ "")
				.build();
			
			public static final ConfigEntry<Boolean> pauseIfMovingQuickly = new ConfigEntry.Builder<Boolean>()
				.set(true)
				.comment(""
					+ "If true and the player/camera is moving quickly \n"
					+ "Distant Horizons will pause world gen to improve loading speed \n"
					+ "and reduce CPU load. \n"
					+ "")
				.build();
			
			public static final ConfigEntry<Double> surfaceRegenMaxDistancePercent = new ConfigEntry.Builder<Double>()
				.setMinDefaultMax(-1.0, -1.0, 1_000.0)
				.comment(""
					+ "If the "+EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS+" generator plan is \n"
					+ "in use, this percentage can be defined to reduce how far the \n"
					+ "second high-quality generation pass will go \n"
					+ "as a percentage of your total render distance: \n"
					+ "-1.0 = Infinity\n"
					+ "0.0 = 0%\n"
					+ "0.5 = 50%\n"
					+ "1.0 = 100%\n"
					+ "2.0 = 200%\n"
					+ "\n"
					+ "Does nothing on a dedicated server. \n"
					+ "")
				.build();
			
			public static ConfigUISpacer genProgressSpacer = new ConfigUISpacer.Builder().build();
			
			public static ConfigEntry<EDhApiDistantGeneratorProgressDisplayLocation> showGenerationProgress = new ConfigEntry.Builder<EDhApiDistantGeneratorProgressDisplayLocation>()
				.set(EDhApiDistantGeneratorProgressDisplayLocation.DISABLED)
				.comment(""
					+ "How should distant generator progress be displayed? \n"
					+ "\n"
					+ EDhApiDistantGeneratorProgressDisplayLocation.OVERLAY + ": may be the same as " + EDhApiDistantGeneratorProgressDisplayLocation.CHAT + " for some Minecraft versions \n"
					+ EDhApiDistantGeneratorProgressDisplayLocation.CHAT + " \n"
					+ EDhApiDistantGeneratorProgressDisplayLocation.LOG + " \n"
					+ EDhApiDistantGeneratorProgressDisplayLocation.DISABLED + " \n"
					+ "")
				.build();
			
			public static ConfigEntry<Integer> generationProgressDisplayIntervalInSeconds = new ConfigEntry.Builder<Integer>()
				.setChatCommandName("generation.logInterval")
				.setMinDefaultMax(1, 2, 60 * 60 * 4) // max = 4 hours
				.comment(""
					+ "How often should the distant generator progress be displayed? \n"
					+ "")
				.build();
			
			public static ConfigEntry<Integer> generationProgressDisableMessageDisplayTimeInSeconds = new ConfigEntry.Builder<Integer>()
				.setMinDefaultMax(0, 20, 60 * 60) // max = 1 hour
				.comment(""
					+ "For how many seconds should instructions for disabling the distant generator progress be displayed? \n"
					+ "Setting this to 0 hides the instructional message so the world gen progress is shown immediately when it starts. \n"
					+ "")
				.build();
			
			public static ConfigEntry<Boolean> generationProgressIncludeChunksPerSecond = new ConfigEntry.Builder<Boolean>()
				.set(true)
				.comment(""
					+ "When logging generation progress also include the rate at which chunks \n"
					+ "are being generated. \n"
					+ "This can be useful for troubleshooting performance. \n"
					+ "")
				.build();
			
			
			
			// file configs //
			
			public static ConfigEntry<Integer> generationCenterChunkX = new ConfigEntry.Builder<Integer>()
				.setChatCommandName("generation.bounds.centerChunk.x")
				.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
				.setMinDefaultMax(Integer.MIN_VALUE, 0, Integer.MAX_VALUE)
				.comment("" +
					"The center X chunk position that the world gen max radius is centered around. \n" +
					"")
				.build();
			public static ConfigEntry<Integer> generationCenterChunkZ = new ConfigEntry.Builder<Integer>()
				.setChatCommandName("generation.bounds.centerChunk.z")
				.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
				.setMinDefaultMax(Integer.MIN_VALUE, 0, Integer.MAX_VALUE)
				.comment("" +
					"The center Z chunk position that the world gen max radius is centered around. \n" +
					"")
				.build();
			public static ConfigEntry<Integer> generationMaxChunkRadius = new ConfigEntry.Builder<Integer>()
				.setChatCommandName("generation.bounds.radiusInChunks")
				.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
				.setMinDefaultMax(0, 0, Integer.MAX_VALUE)
				.comment("" +
					"The max radius in chunks around the central point where world generation is allowed. \n" +
					"If this value is set to 0, generation bounds are disabled and the render distance will be used. \n" +
					"\n" +
					"This should only be set if you have a pre-generated world that has a very limited size. \n" +
					"Setting this on a normal MC world will prevent the world generator from filling \n" +
					"out your render distance. \n" +
					"")
				.build();
			
			
		}
		
		public static class LodBuilding
		{
			public static ConfigUIComment lodBuildingHeader = new ConfigUIComment.Builder().setParentConfigClass(LodBuilding.class).build();
			
			public static ConfigEntry<Boolean> disableUnchangedChunkCheck = new ConfigEntry.Builder<Boolean>()
				.set(false)
				.comment(""
					+ "Enabling this will drastically speed up chunk processing time\n"
					+ "if you are CPU limited.\n"
					+ "Disabling it will drastically speed up chunk processing time\n"
					+ "if you are disk speed/IO limited.\n"
					+ "\n"
					+ "Normally DH will attempt to skip creating LODs for chunks it's already seen\n"
					+ "and that haven't changed.\n"
					+ "\n"
					+ "Disabling this option can also fix a rare bug where\n"
					+ "modified chunks are skipped due to being too similar to\n"
					+ "their saved hash.\n"
					+ "")
				.build();
			
			public static ConfigEntry<EDhApiDataCompressionMode> dataCompression = new ConfigEntry.Builder<EDhApiDataCompressionMode>()
				.set(EDhApiDataCompressionMode.Z_STD_BLOCK)
				// only visible via the API since there is no reason to use any compressor except ZStandard as of 2025-11-24
				// Note: this may need to be re-visited since some people (android) have issues with ZStd
				.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
				.build();
			
			public static ConfigEntry<EDhApiWorldCompressionMode> worldCompression = new ConfigEntry.Builder<EDhApiWorldCompressionMode>()
				.set(EDhApiWorldCompressionMode.VISUALLY_EQUAL)
				.comment(""
					+ "How should block data be compressed when creating LOD data? \n"
					+ "This setting will only affect new or updated LOD data, \n"
					+ "any data already generated when this setting is changed will be\n"
					+ "unaffected until it is modified or re-loaded.\n"
					+ "\n"
					+ EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS + " \n"
					+ "Every block/biome change is recorded in the database. \n"
					+ "This is what DH 2.0 and 2.0.1 all used by default and will store a lot of data. \n"
					+ "Expected Compression Ratio: 1.0\n"
					+ "\n"
					+ EDhApiWorldCompressionMode.VISUALLY_EQUAL + " \n"
					+ "Only visible block/biome changes are recorded in the database. \n"
					+ "Hidden blocks (IE ores) are ignored.  \n"
					+ "Expected Compression Ratio: 0.7\n"
					+ "")
				.build();
			
			public static ConfigEntry<EDhApiDatabaseSyncMode> databaseSyncMode = new ConfigEntry.Builder<EDhApiDatabaseSyncMode>()
				.set(EDhApiDatabaseSyncMode.NORMAL)
				.comment(""
					+ "Requires a level restart to apply. \n"
					+ "\n"
					+ "Setting this to a less strict mode increases DB read/write\n"
					+ "speed at the cost of potential data loss/corruption if\n"
					+ "the computer looses power \n"
					+ "(unexpected program shutdown should be safe for all options).\n"
					+ "\n"
					+ EDhApiDatabaseSyncMode.FULL + " \n"
					+ "ACID - data will never be lost or corrupted \n"
					+ "Slow \n"
					+ "At max throughput: ~2k saves per sec, ~70% disk usage \n"
					+ "\n"
					+ EDhApiDatabaseSyncMode.NORMAL + " \n"
					+ "May not be durable - some data may be lost on power loss, but DB should be safe from corruption \n"
					+ "Fast\n"
					+ "At max throughput: ~14k saves per sec, ~40% disk usage \n"
					+ "\n"
					+ EDhApiDatabaseSyncMode.OFF + " \n"
					+ "Not consistent - database may be corrupted during power loss \n"
					+ "Very Fast\n"
					+ "At max throughput: ~16k saves per sec, ~6% disk usage \n"
					+ "\n"
					+ "")
				.build();
			
			//public static ConfigCategory experimental = new ConfigCategory.Builder().set(Experimental.class).build();
			
			
			
			//public static class Experimental
			//{
			//	public static ConfigUIComment experimentalHeader = new ConfigUIComment.Builder().setParentConfigClass(Experimental.class).build();
			//	
			//}
			
		}
		
		public static class MultiThreading
		{
			public static ConfigUIComment multiThreadingHeader = new ConfigUIComment.Builder().setParentConfigClass(MultiThreading.class).build();
			
			public static final ConfigEntry<Integer> numberOfThreads = new ConfigEntry.Builder<Integer>()
				.setChatCommandName("threading.numberOfThreads")
				.setMinDefaultMax(1,
					ThreadPresetConfigEventHandler.getDefaultThreadCount(),
					Runtime.getRuntime().availableProcessors())
				.comment(""
					+ "How many threads should be used by Distant Horizons? \n"
					+ "")
				.build();
			
			public static final ConfigEntry<Double> threadRunTimeRatio = new ConfigEntry.Builder<Double>()
				.setChatCommandName("threading.threadRunTimeRatio")
				.setMinDefaultMax(0.01, ThreadPresetConfigEventHandler.getDefaultRunTimeRatio(), 1.0)
				.comment(""
					+ "A value between 1.0 and 0.0 that represents the percentage \n"
					+ "of time each thread can run before going idle. \n"
					+ "\n"
					+ "This can be used to reduce CPU usage if the thread count \n"
					+ "is already set to 1 for the given option, or more finely \n"
					+ "tune CPU performance. \n" +
					"")
				.build();
			
			public static final ConfigEntry<Integer> threadPriority = new ConfigEntry.Builder<Integer>()
				.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE) // only in file since this requires a MC reboot to change
				.setMinDefaultMax(Thread.MIN_PRIORITY, // 1
					Thread.NORM_PRIORITY, // 5 (1 higher than C2ME's default priority of 4 which can help reduce issues with Chunky)
					Thread.MAX_PRIORITY) // 10
				.comment(""
					+ "What Java thread priority should DH's primary thread pools run with? \n"
					+ "\n"
					+ "You probably don't need to change this unless you are also \n"
					+ "running C2ME and are seeing thread starvation in either C2ME or DH. \n"
					+ "")
				.build();
			
			//public static final ConfigEntry<Boolean> useC2meThreadCount = new ConfigEntry.Builder<Boolean>()
			//	.set(true)
			//	.comment(""
			//		+ "If C2ME is present, set DH's thread count to match C2ME. \n"
			//		+ "\n"
			//		+ "This is necessary to prevent thread starvation issues when C2ME is present. \n"
			//		+ "")
			//	.build();
			
			
			
		}
		
		public static class Logging
		{
			public static ConfigUIComment loggingHeader = new ConfigUIComment.Builder().setParentConfigClass(Logging.class).build();
			
			
			public static ConfigEntry<EDhApiLoggerLevel> globalFileMaxLevel = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.globalFileMaxLevel")
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ ""
					+ "")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> globalChatMaxLevel = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.globalChatMaxLevel")
				.set(EDhApiLoggerLevel.ERROR)
				.comment(""
					+ ""
					+ "")
				.build();
			
			public static ConfigUISpacer globalLoggingSpacer = new ConfigUISpacer.Builder().build();
			
			
			public static ConfigEntry<EDhApiLoggerLevel> logWorldGenEventToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.logWorldGenEvent")
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ "If enabled, the mod will log information about the world generation process. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logWorldGenChunkLoadEventToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.logWorldGenLoadEvent")
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ "If enabled, the mod will log information about the world generation process. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logRendererEventToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ "If enabled, the mod will log information about the renderer setup, cleanup, and any issues it may encounter. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logRendererGLEventToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ "If enabled, the mod will log information about the renderer OpenGL process. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logRendererGLEventToChat = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.set(EDhApiLoggerLevel.ERROR)
				.comment(""
					+ "If enabled, the mod will log information about the renderer OpenGL process. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logNetworkEventToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.logNetworkEvent")
				.set(EDhApiLoggerLevel.INFO)
				.comment(""
					+ "If enabled, the mod will log information about network operations. \n"
					+ "This can be useful for debugging.")
				.build();
			
			public static ConfigEntry<EDhApiLoggerLevel> logConnectionConfigChangesToFile = new ConfigEntry.Builder<EDhApiLoggerLevel>()
				.setChatCommandName("logging.logConnectionConfigChanges")
				.set(EDhApiLoggerLevel.WARN)
				.comment(""
					+ "If enabled, config changes sent by the server will be logged. \n"
					+ "")
				.build();
			
			public static ConfigUISpacer warningSpacer = new ConfigUISpacer.Builder().build();
			
			public static ConfigCategory warning = new ConfigCategory.Builder().set(Warning.class).build();
			
			
			
			public static class Warning
			{
				public static ConfigUIComment warningHeader = new ConfigUIComment.Builder().setParentConfigClass(Warning.class).build();
				
				public static ConfigEntry<Boolean> showLowMemoryWarningOnStartup = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed if Java doesn't have enough \n"
						+ "memory allocated to run DH well. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showPoolInsufficientMemoryWarning = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed if DH detects \n"
						+ "that any pooled objects have been garbage collected. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showHighVanillaRenderDistanceWarning = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed if vanilla MC's \n"
						+ "render distance is higher than the recommended amount. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showReplayWarningOnStartup = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed when a replay is started \n"
						+ "giving some basic information about how DH will function. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showUpdateQueueOverloadedChatWarning = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.comment(""
						+ "If enabled, a chat message will be displayed when DH has too many chunks \n"
						+ "queued for updating. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showSlowWorldGenSettingWarnings = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed when DH has too many chunks \n"
						+ "queued for updating. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showModCompatibilityWarningsOnStartup = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed when a potentially problematic \n"
						+ "mod is installed alongside DH. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> logGarbageCollectorWarning = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a message will be logged if the garbage \n"
						+ "collector Java is currently using is known \n"
						+ "to cause frame stuttering and/or other issues. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showGarbageCollectorWarning = new ConfigEntry.Builder<Boolean>()
					.set(true)
					.comment(""
						+ "If enabled, a chat message will be displayed in chat if the garbage \n"
						+ "collector Java is currently using is known \n"
						+ "to cause frame stuttering and/or other issues. \n"
						+ "")
					.build();
				
				// as of 3.2.0 explicit GC shouldn't be needed since we pool our byte buffers
				// but we'll keep this around (in a disabled form) for now in case we
				// see problems that explicit GC solves
				public static ConfigEntry<Boolean> logExplicitGcDisabledWarning = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
					.comment(""
						+ "If enabled, a message will be logged if explicit garbage collection \n"
						+ "is disabled. \n"
						+ "This is known to cause out-of-memory issues \n"
						+ "and is better solved with a concurrent garbage collector. \n"
						+ "")
					.build();
				
				public static ConfigEntry<Boolean> showExplicitGcDisabledWarning = new ConfigEntry.Builder<Boolean>()
					.set(false)
					.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
					.comment(""
						+ "If enabled, a message will be displayed in chat if explicit garbage collection \n"
						+ "is disabled. \n"
						+ "This is known to cause out-of-memory issues \n"
						+ "and is better solved with a concurrent garbage collector. \n"
						+ "")
					.build();
				
			}
			
		}
		
	}
	
	public static class Server
	{
		// Level keys
		public static ConfigEntry<Boolean> sendLevelKeys = new ConfigEntry.Builder<Boolean>()
			.setChatCommandName("levelKeys.send")
			.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
			.set(true)
			.comment(""
				+ "Makes the server send level keys for each world.\n"
				+ "Disable this if you use alternative ways to send level keys.\n"
				+ "")
			.build();
		
		public static ConfigEntry<Integer> serverId = new ConfigEntry.Builder<Integer>()
			.set(new Random().nextInt())
			.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
			.comment(""
				+ "DO NOT CHANGE UNLESS YOU KNOW WHAT YOU'RE DOING.\n"
				+ "Autogenerated ID used to prevent multiple independent servers from accidentally\n"
				+ "writing over each other's LODs when the same serverKey is set on both.\n"
				+ "")
			.build();
		
		public static ConfigEntry<String> serverKey = new ConfigEntry.Builder<String>()
			.setChatCommandName("levelKeys.serverKey")
			.setAppearance(EConfigEntryAppearance.ONLY_IN_FILE)
			.set("")
			.comment(""
				+ "Custom server key used which can be used to always reuse the same LOD data folder,\n"
				+ "for cases when the server doesn't have a static IP for some reason.\n"
				+ "If this value is empty, the client itself decides which folder name to use.\n"
				+ "Requires rejoining the server to apply after changing.\n"
				+ "")
			.build();
		
		public static ConfigEntry<String> levelKeyPrefix = new ConfigEntry.Builder<String>()
			.setChatCommandName("levelKeys.prefix")
			.set("")
			.comment(""
				+ "Prefix of the level keys sent to the clients.\n"
				+ "If the mod is running behind a proxy, each backend should use a unique value.\n"
				+ "If this value is empty, level key will be based on the server's seed hash.\n"
				+ "")
			.build();
		
		public static ConfigEntry<Integer> generationRequestRateLimit = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("generation.requestRateLimit")
			.setMinDefaultMax(1, 20, 100)
			.comment(""
				+ "How many LOD generation requests per second should a client send? \n"
				+ "Also limits the number of client requests allowed to stay in the server's queue."
				+ "")
			.build();
		
		public static ConfigEntry<Integer> maxGenerationRequestDistance = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("generation.maxRequestDistance")
			.setMinDefaultMax(256, 4096, 4096)
			.comment("" +
				"Defines the distance allowed to generate around the player." +
				"")
			.build();
		
		
		// Real-time updates
		public static ConfigEntry<Boolean> enableRealTimeUpdates = new ConfigEntry.Builder<Boolean>()
			.setChatCommandName("realTimeUpdates.enable")
			.set(true)
			.comment(""
				+ "If true, clients will receive real-time LOD updates for chunks outside the client's render distance."
				+ "")
			.build();
		
		public static ConfigEntry<Integer> realTimeUpdateDistanceRadiusInChunks = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("realTimeUpdates.playerDistance")
			.setMinDefaultMax(32, 256, 4096)
			.comment("" +
				"Defines the distance the player will receive updates around." +
				"")
			.build();
		
		
		// Sync on load
		public static ConfigEntry<Boolean> synchronizeOnLoad = new ConfigEntry.Builder<Boolean>()
			.setChatCommandName("syncOnLoad.enable")
			.set(true)
			.comment(""
				+ "If true, clients will receive updated LODs when joining or loading new LODs. \n"
				+ "")
			.build();
		
		public static ConfigEntry<Integer> syncOnLoadRateLimit = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("syncOnLoad.rateLimit")
			.setMinDefaultMax(1, 50, 100)
			.comment(""
				+ "How many LOD sync requests per second should a client send? \n"
				+ "Also limits the amount of player's requests allowed to stay in the server's queue."
				+ "")
			.build();
		
		public static ConfigEntry<Integer> maxSyncOnLoadRequestDistance = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("syncOnLoad.maxRequestDistance")
			.setMinDefaultMax(256, 4096, 4096)
			.comment("" +
				"Defines the distance allowed to be synchronized around the player. \n" +
				"Should be the same or larger than maxGenerationRequestDistance in most cases." +
				"")
			.build();
		
		
		// Common
		public static ConfigEntry<Integer> playerBandwidthLimit = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("common.playerBandwidthLimit")
			.setMinDefaultMax(0, 500, 1000000 /* 1 GB/s */)
			.comment(""
				+ "Maximum per-player speed for uploading LODs to the clients, in KB/s.\n"
				+ "Value of 0 disables the limit."
				+ "")
			.build();
		public static ConfigEntry<Integer> globalBandwidthLimit = new ConfigEntry.Builder<Integer>()
			.setChatCommandName("common.globalBandwidthLimit")
			.setMinDefaultMax(0, 0, 10000000 /* 10 GB/s */)
			.comment(""
				+ "Maximum global speed for uploading LODs to the clients, in KB/s.\n"
				+ "Value of 0 disables the limit."
				+ "")
			.build();
		public static ConfigEntry<Boolean> enableAdaptiveTransferSpeed = new ConfigEntry.Builder<Boolean>()
			.set(false)
			.comment(""
				+ "Enables adaptive transfer speed based on client performance.\n"
				+ "If true, DH will automatically adjust transfer rate to minimize connection lag.\n"
				+ "If false, transfer speed will remain fixed.\n"
				+ "")
			.build();
		
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** the setup should only be called once */
	private static boolean complicatedListenerSetupComplete = false;
	/**
	 * Runs any config setup that needs all (or most) config entries be initialized (not null),
	 * but doesn't necessarily require they have the right values yet. <br><br>
	 *
	 * Specially:
	 * Updates any config values that are UI only
	 * and adds any listeners that depend on multiple config values.
	 */
	public static void completeDelayedSetup()
	{
		if (!complicatedListenerSetupComplete)
		{
			complicatedListenerSetupComplete = true;
			
			try
			{
				ThreadPresetConfigEventHandler.INSTANCE.setUiOnlyConfigValues();
				RenderQualityPresetConfigEventHandler.INSTANCE.setUiOnlyConfigValues();
				QuickRenderToggleConfigEventHandler.INSTANCE.setUiOnlyConfigValues();
				
				IgnoredDimensionCsvHandler.INSTANCE.onConfigValueSet();
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected exception when running config delayed UI setup. Error: [" + e.getMessage() + "].", e);
			}
		}
	}
	
	/** Guesses whether a dev environment is used based on the current folder path */
	private static boolean isRunningInDevEnvironment()
	{
		IMinecraftSharedWrapper mcShared = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
		File installFolder = mcShared.getInstallationDirectory();
		File installParentFolder = installFolder.getParentFile();
		
		// new merged DH format "run/client" or "run/server"
		if (installParentFolder != null && installParentFolder.getName().equals("run"))
		{
			if (installFolder.getName().equals("client")
				|| installFolder.getName().equals("server"))
			{
				return true;
			}
		}
		
		// old DH format "run/"
		if (installFolder.getName().equals("run"))
		{
			return true;
		}
		
		return false;
	}
	
}
