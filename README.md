# Tellus

Tellus is a Fabric mod that recreates real-world terrain in Minecraft by generating Earth-scale landscapes from geographic data. It focuses on realistic elevation, biome placement, and climate-driven time and weather, aiming to make the world feel like a playable map of our planet.

![Tellus header image](images/Header%20image.jpg)

Inspired by Gegy's Terrarium: https://modrinth.com/mod/terrarium

Survival note: Some survival features are still missing (including certain structures and biomes). While a survival world is possible, upcoming updates may break those worlds; for now Tellus is better suited for testing and exploration than long-term survival.

Internet & data note: Tellus requires an active internet connection and will not work offline. It downloads terrain, land cover, climate, and weather data on demand; expect ongoing data usage that varies with how much of the world you explore.

Server support note: Tellus must be installed on the server, but is not required on clients. Official server support is not available yet; for now you should create the world in singleplayer first, then move that world to the server (with Tellus installed) so new chunks generate with Tellus.

*Note: generative AI was used during the creation of this mod.*

## Features

- Earth-scale terrain generated from geographic elevation data
- Highly customizable terrain generation (scale, height limits, and more)
- Built-in terrain preview screen for visualizing settings before world creation
- Biomes placed to match real-world climate regions
- OSM roads, buildings, and map features generated with Arnis-derived logic
- Real-time inspired weather and time systems (optional)
- Distant Horizons integration for long-distance terrain rendering
- In-game map teleport UI for choosing real-world locations

## Third-Party Code

Tellus includes code derived from Arnis.

Copyright (c) 2022-2026 Louis Erbkamm (louis-e)

Licensed under the Apache License, Version 2.0.

Source: https://github.com/louis-e/arnis

## Distant Horizons Integration

Tellus integrates with the Distant Horizons (DH) mod to render planet-scale terrain far beyond vanilla view distance. When DH is installed, Tellus registers a DH world-generation override for Tellus worlds (DH API v4+), so distant terrain is built using Tellus data and settings instead of generic vanilla sampling.

- **Fast mode**: Tellus provides a custom LOD generator that samples its elevation, land-cover, climate, and water data directly to build distant terrain quickly and consistently with your world settings.
- **Detailed mode**: Tellus delegates to DH's chunk-based generator for far terrain, which is more accurate but significantly heavier on performance.

Because Tellus worlds are Earth-scale, DH is strongly recommended and is almost essential for comfortable exploration and long-distance views.

### Offline Fast LOD profiling

The Minecraft 26.2 target includes a headless source-loading simulation for Fast LOD development. It uses experimental 1:1 true-height settings, prefetches the real elevation, land-cover, land-mask, Overture water/road/building inputs, and reports cold/warm sampling timings without starting Minecraft or creating a world:

```bash
./gradlew :mc262:simulateFastLodDataLoading
```

The default 64×64 detail-11 pass spans 8192 chunks, matching a 4096-chunk render radius. Use `-PsimDetails=0,6,11`, `-PsimGrid=64`, `-PsimLatitude=...`, and `-PsimLongitude=...` to select a smaller profile. The task stores its isolated cache under `mc262/build/lod-simulation-game`; `-PsimGameDir=...` selects another cache for cold-run comparisons.

## Commands

- `/tellus map`: Opens the GeoTP map UI (requires gamemaster permissions).
- `/tellus weather`: Shows local Tellus weather and time information at your current position.
- `/tellus config weather enable_realtime_time <true|false>`: Overrides the real-time time setting on the server (requires gamemaster permissions).
- `/tellus config weather enable_realtime_weather <true|false>`: Overrides the real-time weather setting on the server (requires gamemaster permissions).

More commands will be added over time.

<details>
  <summary>Settings</summary>

These options are available in the "Customize World Generation" screen when creating a Tellus world.

![Tellus config screen](images/Config%20screen.png)

### World Settings
- **World Scale**: Controls how many real-world meters are represented by one block. Lower values create more detailed, larger worlds; higher values compress distances and features. Current limits are 1:1m to 1:1km per block.
- **Increase Height**: Enables the experimental expanded-height terrain profile. Elevation remains proportional to the selected World Scale. Hover over the option in-game for the current compatibility warning.
- **Terrestrial Height Scale**: Multiplier that converts elevation above sea level from meters to blocks. Higher values produce taller mountains and landforms.
- **Oceanic Height Scale**: Multiplier that converts elevation below sea level from meters to blocks. Higher values deepen oceans and trenches.
- **Height Offset**: Shifts all terrain up or down by a fixed number of blocks. Use this to raise or lower the entire world.
- **Max Altitude**: Upper world limit in blocks. Set to Automatic to let Tellus compute a safe cap based on your scale settings.
- **Min Altitude**: Lower world limit in blocks. Set to Automatic to let Tellus compute a safe floor based on your scale settings.
- **Water**: Uses Overture Maps `ocean`/`sea` polygons as the sole ocean and coastline authority. Rivers and lakes retain their own Overture feature kinds, and ocean floors use OpenWaters bathymetry with a corrective coastal safety ramp.

### Ecological Settings (work in progress)
These options are currently locked and not adjustable yet. They describe what will be configurable in a future update.
- **Tree Density**: Will control how many trees spawn in eligible biomes.
- **Aquatic Vegetation**: Will enable kelp and seagrass in water.

### Geological Settings
The cave and underground generation system is still work in progress, so expect changes here.
- **Cave Generation**: Projects modern Overworld cave density throughout the configured terrain shell, with supplemental tunnels and ravines.
- **Ore Distribution**: Generates mineable ores and raw ore blocks independently from decorative host-rock patches.
- **Geological Stone Patches**: Optionally generates buried granite, diorite, andesite, and tuff patches. This is disabled by default.
- **Lava Pools**: Enables underground lava pools.
- **Underground Depth**: Controls how far the terrain shell, caves, ores, cave biomes, and underground structures extend below the local surface.

Tellus reads the live `minecraft:overworld` noise settings, so compatible data packs and mods that replace the Overworld density router can influence its modern cave field. Mods that add biome-registered custom carvers are not currently supported by the Tellus surface-relative carver runner.

Worlds created with earlier 0.8.1 builds keep their already-generated chunks unchanged. Newly generated chunks use the corrected full-depth underground rules, so underground seams may be visible at old chunk borders.

### Structure Settings
This section lets you toggle vanilla structures and world features on or off, such as villages, temples, monuments, ruins, and underground features like Deep Dark and amethyst geodes. Some structures (notably Deep Dark and certain ocean structures) may not generate properly yet and are still work in progress.

### Real-Time Settings
- **Real-Time Time**: Syncs the in-game day/night cycle to real-world time based on your in-game location, so sunrise and sunset match that location's local clock.
- **Real-Time Weather**: Pulls live weather conditions for your location and mirrors them in-game (rain, thunder, or snow) instead of Minecraft's default weather rolls.
- **Historical Snow Coverage** (work in progress): Tracks recent temperature and snowfall data to decide if snow should appear and persist on the ground, creating more realistic seasonal snow coverage.

### Compatibility Settings
- **Distant Horizons Render Mode**: Fast uses Tellus's LOD generator to build simplified distant terrain quickly with lower cost. Detailed asks Distant Horizons to use full chunk generation for far terrain, which is more accurate but significantly slower and heavier. For most setups, keeping Fast LOD generation is recommended.
- **LOD Water Resolver**: Adds water depth and smoother water surfaces to Distant Horizons fast LODs using cached Overture vector water without a coarse land-cover fallback.
- **Coming Soon**: Additional compatibility options are work in progress and currently unavailable.

### Cache
- **OSM data**: Cached map, road, and water tiles used by Tellus map and OSM features. Deleting will force re-downloads as needed.
- **ESA WorldCover land cover**: Official 10 m categorical COG pixels and internal overview levels, fetched and cached by compressed byte range for biome and vegetation lookups.
- **Koppen climate**: Cached climate raster used for biome climate classification.
- **Mapterhorn terrain**: Cached elevation tiles used for terrain height sampling.
- **OpenWaters bathymetry**: Cached bathymetry tiles used for ocean and underwater terrain.
- **Total**: Combined size of all Tellus caches (read-only).
- **Delete cache / Delete all cache**: Removes cached data to free disk space; data will be re-downloaded or rebuilt as needed.
</details>

<details>
  <summary>Runtime Configuration</summary>

Tellus reads JVM system properties (`-Dtellus.<name>=<value>` on the client or server launch command) for tuning. Values are validated and clamped to their supported ranges; invalid values fall back to the default. Only properties with user-visible effect are listed; other `tellus.*` properties exist in the code for internal state and diagnostics.

### Data sources

| Property | Default | Range | Effect |
|---|---|---|---|
| `tellus.worldcover.memoryCacheMb` | 64 | 8..2048 | Decoded ESA WorldCover COG memory cache size in MiB. |
| `tellus.worldcover.diskCacheMb` | 512 | 32..16384 | Compressed WorldCover block disk cache size in MiB. |
| `tellus.worldcover.baseUrl` | official ESA source | — | Mirror base URL for WorldCover COG requests. |
| `tellus.worldcover.connectTimeoutMs` | 7000 | 1..120000 | WorldCover HTTP connect timeout. |
| `tellus.worldcover.readTimeoutMs` | 30000 | 1..120000 | WorldCover HTTP read timeout. |
| `tellus.worldcover.fetchRetries` | 3 | 1..8 | WorldCover HTTP retry count. |
| `tellus.worldcover.metadataCacheEntries` | — | — | WorldCover COG metadata cache entry count. |
| `tellus.overture.landCover.pmtiles` | official release | — | Overture land-cover PMTiles URL override. |
| `tellus.overture.landCover.rasterSize` | 512 | 64..1024 | Overture land-cover fallback raster edge size in pixels. |
| `tellus.overture.landCover.dirCache` | 256 | 1..8192 | Overture land-cover tile cache entries. |
| `tellus.overture.release` | latest | — | Pinned Overture release tag, e.g. `2025-05-20`. |
| `tellus.elevation.cacheTiles` | 512 | — | Mapterhorn elevation tile cache entries. |
| `tellus.elevation.areaPrefetch.samples` | 25 | — | Prefetch samples per terrain area. |
| `tellus.elevation.areaPrefetch.terrainTileLimit` | 512 | — | Max elevation tiles per prefetch area. |
| `tellus.elevation.downloadAttempts` | 3 | — | Mapterhorn download attempts per tile. |
| `tellus.elevation.retryBackoffMs` | 250 | — | Mapterhorn retry backoff. |
| `tellus.elevation.connectTimeoutMs` / `readTimeoutMs` | 8000 | — | Mapterhorn HTTP timeouts. |
| `tellus.elevation.normalized.enabled` | false | — | Opt into the normalized elevation cache path. |
| `tellus.elevation.normalized.threads` / `memoryTiles` | — / 256 | — | Normalized cache workers and tile count. |
| `tellus.mapterhorn.coverage.cacheTiles` | 32 | 4..512 | Mapterhorn coverage tile cache entries. |
| `tellus.mapterhorn.coverage.endpoint` | official | — | Mapterhorn coverage endpoint override. |
| `tellus.landmask.baseUrl` | official | — | Land-mask PMTiles base URL override. |
| `tellus.landmask.cacheTiles` | 256 | — | Land-mask tile cache entries. |
| `tellus.landmask.dirCache` | 256 | — | Land-mask directory cache entries. |
| `tellus.landmask.areaPrefetch.samples` | 25 | — | Land-mask prefetch samples per area. |
| `tellus.landmask.areaPrefetch.tileLimit` | 512 | — | Max land-mask tiles per prefetch area. |
| `tellus.landcover.cacheTiles` | 96 | 1..2048 | Land-cover sample cache entries. |
| `tellus.landcover.nearestLandCacheEntries` | 131072 | 1024..1048576 | Nearest-land lookup cache entries. |
| `tellus.landcover.nearestLandRadiusPixels` | 64 | 1..512 | Nearest-land search radius. |
| `tellus.oisst.endpoint` | official | — | OISST sea-surface temperature endpoint override. |
| `tellus.oisst.sampleYear` | 2024 | — | OISST climatology year used for ocean climate. |
| `tellus.oisst.timeStrideDays` | 30 | — | OISST sampling stride in days. |
| `tellus.oisst.cacheCells` | 4096 | — | OISST cell cache entries. |

### OSM / Overpass

| Property | Default | Range | Effect |
|---|---|---|---|
| `tellus.osm.overpass.endpoint` / `endpoints` | official | — | Overpass API endpoint(s); `endpoints` accepts a comma-separated list. |
| `tellus.osm.overpass.queryTimeoutSec` | 25 | 5..300 | Overpass query timeout. |
| `tellus.osm.overpass.maxRetries` | 3 | 0..12 | Overpass retry count. |
| `tellus.osm.overpass.minSpacingMs` | 350 | 0..60000 | Minimum spacing between Overpass requests. |
| `tellus.osm.overpass.retryBackoffMs` / `retryBackoffJitterMs` / `retryableCooldownMs` | 750 / 250 / 4000 | 0..60000 / 0..10000 / 0..300000 | Overpass retry timing. |
| `tellus.osm.infrastructure.queryZoom` | 14 | 0..20 | OSM infrastructure query zoom level. |
| `tellus.osm.infrastructure.cacheTiles` | 256 | 1..8192 | OSM infrastructure tile cache entries. |
| `tellus.osm.infrastructure.prefetchAsyncMax` | 96 | 0..8192 | Max concurrent infrastructure prefetches. |
| `tellus.overture.infrastructure.pmtiles` | official | — | Overture infrastructure PMTiles URL override. |
| `tellus.overture.infrastructure.dirCache` | 256 | 1..8192 | Overture infrastructure cache entries. |

### Water

| Property | Default | Range | Effect |
|---|---|---|---|
| `tellus.water.oceanFloorTransitionBlocks` | 512 | 0..2048 | Coastal ramp from one-block depth to raw bathymetry. |
| `tellus.water.oceanFloorSupportBlocks` | 8 | 2..32 | Solid support blocks reserved above the world minimum for ocean floors. |
| `tellus.oceanCoastCacheTiles` | 32 | 4..256 | Overture coastline macro-tile cache entries. |
| `tellus.water.inlandOceanTransitionBlocks` | 48 | 0..512 | Blending distance between inland water and open ocean. |
| `tellus.water.featureSurfaceSamples` | 128 | 8..2048 | Water surface sampling count per feature. |
| `tellus.water.lakeMaxTerrainCut` | 12 | 1..64 | Max terrain cut for lake water surfaces. |
| `tellus.water.riverMaxTerrainCut` | 6 | 0..32 | Max terrain cut for river water surfaces. |
| `tellus.water.riverConnectGapBlocks` | 4 | 0..16 | Max gap bridged when connecting river segments. |
| `tellus.water.esaLakeHeightBucket` | 4 | 1..64 | ESA-based lake height quantization bucket. |
| `tellus.water.esaLakeKeyGridBlocks` | 512 | 64..8192 | ESA lake key grid resolution. |
| `tellus.waterLakeSurfaceCacheSize` / `tellus.waterNearChunkCacheSize` / `tellus.waterRegionCacheSize` | 8192 / 8192 / — | 256..65536 | Water lookup cache sizes. |

### Preload and managed downloads

| Property | Default | Range | Effect |
|---|---|---|---|
| `tellus.preload.downloadThreads` | 8 | 1..32 | Preload download worker threads. |
| `tellus.preload.downloadDedupEntries` / `downloadDedupSeconds` | 16384 / 120 | 0..262144 / 0..3600 | Preload download deduplication window. |
| `tellus.preload.maxChunksPerSide` | — | — | Preload area size cap in chunks. |
| `tellus.preload.package.gridResolutionMeters` | — | — | Preload package grid resolution. |
| `tellus.preload.package.maxSamples` / `maxLoadedSamples` / `rowsInFlight` / `threads` | — | — | Preload package sampling and threading limits. |
| `tellus.preload.source.dem` / `water` / `roads` / `sand` / `buildings` / `infrastructure` | — | — | Per-source preload toggles. |
| `tellus.managedDownloads.batchCellsPerSide` | 8 | 1..64 | Managed (DH) download batch size. |
| `tellus.managedDownloads.coordinators` | 4 | 1..16 | Managed download coordinator threads. |
| `tellus.managedDownloads.coreAttempts` | 1 | 1..3 | Core download attempts per tile. |
| `tellus.managedDownloads.packageLoadTimeoutMs` / `retryBaseDelayMs` / `retryMaxDelayMs` | — | — | Managed download timing knobs. |

### Debug and developer

| Property | Default | Effect |
|---|---|---|
| `tellus.gameDir` / `tellus.configDir` | platform default | Override the game/config directory (used by headless tooling). |
| `tellus.projection.mode` | `mercator` | Earth projection mode. |
| `tellus.experimentalHeight.coordinateProfile` | — | Coordinate profile for the experimental expanded-height terrain (see `simulateFastLodDataLoading`). |
| `tellus.dhLodTiming` / `tellus.lodTiming` | `false` | Log LOD generation timing. |
| `tellus.debug.osmPerf` | `false` | Log OSM source performance counters. |
| `tellus.debug.dem` / `tellus.debugWater` | `false` | Debug rendering/diagnostics for DEM and water. |
| `tellus.chunkdetail.deferDetailedWater` | — | Defer detailed water work during chunk generation. |

</details>

<details>
  <summary>Data Sources</summary>

### ESA WorldCover land cover
- ESA WorldCover 2021 v200 is sampled at its native approximately 10 m resolution for close terrain. Its built-in 20–640 m COG overviews are selected for larger world scales and LOD requests.
- Tellus requests only the compressed TIFF blocks needed for the explored area and stores them in a bounded local cache; it does not download complete 3° source files.
- The decoded memory cache defaults to 64 MiB and the compressed disk cache to 512 MiB. Override them with `tellus.worldcover.memoryCacheMb` and `tellus.worldcover.diskCacheMb`; mirrors can use `tellus.worldcover.baseUrl`.
- © ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium.
- ESA WorldCover license: CC BY 4.0.
- Overture Maps base-theme land cover remains an availability and out-of-coverage fallback. © Overture Maps Foundation; base-theme license: ODbL.
- https://esa-worldcover.org/en/data-access
- https://docs.overturemaps.org/attribution/
- In-game processing: the primary COG is fetched with HTTP byte ranges and sampled directly; Overture fallback vectors use PMTiles byte ranges and a compact raster cache.

### Overture Maps water
- Overture Maps base-theme water features provide inland-water geometry and definitive `ocean`/`sea` coastline polygons.
- Ocean classification does not use Mapterhorn elevation, land-mask state, or an elevation-at-or-below-zero heuristic.
- Complete empty vector tiles are valid dry coverage. Pending or failed coverage is kept non-cacheable so temporary source failures cannot become permanent dry seams.
- https://docs.overturemaps.org/attribution/

### Koppen-Geiger climate classification
- Source: Beck, H.E., Zimmermann, N.E., McVicar, T.R., et al. (2018).
- Present and future Koppen-Geiger climate classification maps at 1-km resolution (Scientific Data).
- License: CC BY 4.0
- https://creativecommons.org/licenses/by/4.0/
- Publication DOI:
- https://doi.org/10.1038/sdata.2018.214
- In-game processing: reprojected and resampled to match the world grid, cached for fast lookup.

### Mapterhorn terrain DEM
- Source: Mapterhorn global terrain tiles.
- Website: https://mapterhorn.com/
- In-game processing: sampled as Terrarium elevation tiles, with zoom selected from player scale and cached locally for reuse.

### OpenWaters Seascape bathymetry
- Source: OpenWaters Seascape bathymetry raster DEM tiles.
- Project: https://github.com/openwatersio/seascape
- Tiles: https://tiles.openwaters.io/seascape/
- License: CC BY 4.0 for the published tile compilation.
- In-game processing: Overture `ocean` and `sea` polygons define ocean membership independently of either elevation source. OpenWaters Terrarium pixels are bilinearly sampled at a zoom selected for the requested world/LOD resolution. Negative elevations are scaled by the oceanic height scale; zero or positive samples remain ocean and are clamped to a one-block minimum depth. Deterministic fallback bathymetry is used only when OpenWaters is unavailable.
- Coastal safety: naturally shallow OpenWaters profiles are preserved. Abrupt, invalid, or missing profiles receive a smooth one-block-to-raw-depth ramp over 512 blocks by default. Configure it with `tellus.water.oceanFloorTransitionBlocks` (`0..2048`); DH inherits this unless `tellus.dhWaterOceanFloorTransitionBlocks` is supplied. The 512-block Overture coastline macro-tile cache defaults to 32 entries and can be set with `tellus.oceanCoastCacheTiles` (`4..256`).
- DH renders the raw profiled ocean floor by default so deep-water variation remains continuous. Legacy logarithmic depth compression is opt-in through `tellus.dhWaterOceanDepthCompressionEnabled=true` and no longer uses a fixed maximum-depth plateau.
- When raw bathymetry is deeper than the dimension permits, Tellus now fits it monotonically into the available vertical range instead of clamping every sample to the same bottom Y. Ocean floors reserve eight solid support blocks above the world minimum, configurable with `tellus.water.oceanFloorSupportBlocks` (`2..32`).
- Compatibility: the serialized `ocean_shoreline_blend` setting is retained but is a no-op for oceans. River/lake shoreline blending is unchanged.

### Open-Meteo (weather)
- Weather data provided by Open-Meteo.com.
- https://open-meteo.com/
- License: CC BY 4.0
- https://creativecommons.org/licenses/by/4.0/
- Credit: "Weather data by Open-Meteo.com".
- https://doi.org/10.5281/ZENODO.7970649
</details>
