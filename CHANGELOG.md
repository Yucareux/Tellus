# Changelog

All notable changes to Tellus are documented here. This file is maintained per release; entries are derived from the commit history.

## [Unreleased]

### Developer experience
- Introduced a Gradle version catalog (`gradle/libs.versions.toml`) as the single source of truth for shared dependency and plugin versions across all five Minecraft targets.
- Root `build.gradle` now derives aggregate tasks (`clean`, `assemble`, `check`, `build`) and the per-target convenience tasks from the subproject list instead of hardcoding module names.
- Hoisted four files that were byte-identical across all five targets into the shared source tree: `TellusChunkLodGenerator`, `DimensionTypeHighYMixin`, `LevelHighYPackedCoordinateMixin`, `HighYPackedCoordinateProfile`.
- CI now builds the five targets in a parallel matrix and uploads the resulting jars as workflow artifacts for playtesting, instead of one serial job.
- Added `.editorconfig` and this changelog.

## [0.8.2] - 2026-07-23

- Reworked underground generation: corrected full-depth underground rules in `EarthChunkGenerator`, expanded `TellusEmptyBeardifier` and cave sampling, updated underground structure exclusion and protection, and adjusted the vanilla carver runner.
- Overhauled the terrain preview (`TerrainPreview`, `TerrainPreviewWidget`) and reworked the slippy map tile cache (`SlippyMapTileCache`).
- Updated the world customization and terrain preload screens, the level loading screen mixin, and `EarthGeneratorSettings`.
- Documented the new underground behavior (chunk border seams between old and new chunks) in the README.

## [0.8.1]

- Reworked the terrain preview and world customization screens.
- Improved place search (`PlaceSearchWidget`) and slippy map tiles.
- Added client-side setup in `TellusClient` and fixed `build.gradle` wiring.

## [0.8.0]

- Added terrain preload: `TerrainPreloadScreen`, preload job management, and the managed terrain download overlay.
- Major rework of the world customization screen and terrain preview.
- Updated data source documentation (ESA WorldCover, Overture, OpenWaters, Mapterhorn, Open-Meteo).

## [0.7.0]

- Added the spawn point screen and expanded the terrain preview and teleport screens.
- Fixed crashes on Minecraft 1.20.1 and 1.21.1 and continued the NeoForge port.
