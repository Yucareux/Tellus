# WLB Arnis Integration

This branch keeps the Tellus chunk generator as the owner of terrain, projection, caching, and block placement. Arnis should be integrated at the feature/rule layer, not by merging generated region files.

## Project Placement

The working copy lives under:

```text
/home/kaijie/桌面/WorkLifeBalance/你在这里搞个项目/minecraft-mods/tellus-arnis-integration
```

This matches the WorkLifeBalance workspace convention: source projects stay in `你在这里搞个项目/`, Minecraft mod projects stay in `minecraft-mods/`, and built jars are copied separately to `../../.minecraft/mods/`.

## Integration Boundary

The first stable boundary is:

```text
ExternalFeatureSource
  -> ExternalRoadFeature
  -> ExternalBuildingFeature
  -> ExternalAreaFeature
  -> ExternalLineFeature
  -> GeoBounds / GeoPoint
  -> ExternalFeatureAdapters
  -> JsonExternalFeatureSource
  -> OverpassExternalFeatureSource
```

This gives Tellus one neutral input shape for external real-world features. The first adapters can be:

- `OverpassExternalFeatureSource`: reads OSM roads/buildings/city details from Overpass with the same bbox-style data source family used by Arnis.
- `ArnisJsonFeatureSource`: reads an Arnis/exporter-produced JSON or GeoJSON feature dump for offline overrides.
- Later: `ArnisProcessFeatureSource`: runs an Arnis-side exporter as a local process for a requested bbox.

The current JSON source accepts this shape:

```json
{
  "roads": [
    {
      "source": "arnis",
      "sourceId": "road-1",
      "roadClass": "MAIN",
      "mode": "NORMAL",
      "bridgeLevel": 0,
      "highwayTag": "primary",
      "points": [{"lat": 35.0, "lon": 139.0}, {"lat": 35.001, "lon": 139.001}],
      "tags": {"surface": "asphalt", "lanes": "4", "sidewalk": "both"}
    }
  ],
  "buildings": [
    {
      "source": "arnis",
      "sourceId": "building-1",
      "kind": "FOOTPRINT",
      "heightMeters": 8.0,
      "minHeightMeters": 0.0,
      "floorCount": 2,
      "rings": [[
        {"lat": 35.0, "lon": 139.0},
        {"lat": 35.0, "lon": 139.001},
        {"lat": 35.001, "lon": 139.001},
        {"lat": 35.001, "lon": 139.0},
        {"lat": 35.0, "lon": 139.0}
      ]],
      "tags": {"building": "house", "building:material": "brick", "building:colour": "white", "roof:material": "tile", "roof:colour": "red"}
    }
  ]
}
```

The same file can also include optional `areas`, `lines`, and `points` arrays for offline city-detail overrides. `areas` use `kind` values such as `PARKING`, `LANDUSE`, `LEISURE`, and `NATURAL`; `lines` use `kind` values such as `BARRIER` and `RAILWAY`; `points` use `kind` values such as `TRAFFIC_SIGNAL`, `CROSSING`, `AMENITY`, and `NATURAL`.

## Runtime File

The game-side loader now has two inputs:

1. Automatic Overpass/OSM source, enabled by default.
2. Optional local JSON override/extension file.

For normal use, no JSON file is required. When a chunk needs road/building/city-detail data, Tellus calculates the chunk's geographic bbox, fetches matching OSM `highway`, `building`, `building:part`, `amenity=parking`, selected `landuse`, `leisure`, `natural`, `barrier`, and `railway` ways/relations plus selected OSM city nodes from Overpass, caches the response under:

```text
<gameDir>/tellus/cache/map/arnis-overpass/
```

If the Overpass source returns usable features for a chunk, Tellus prefers that Arnis-style OSM source over the original Overture road/building PMTiles for that chunk. If the Overpass source returns nothing or fails, Tellus falls back to the original Overture source. Building and area relations/multipolygons are supported by merging outer/inner member way geometry into rings before handing the feature to Tellus.

Older road/building cache files remain usable. City-detail data uses a `city-v4` sidecar profile next to the existing raw tile cache. If a tile was cached before city details existed, the UI warm-up or first in-game city-detail query upgrades that tile in place instead of invalidating the whole cache. The `city-v4` profile adds Arnis-style vegetation inputs such as OSM forest/orchard landuse and extra natural area tags.

The optional local file is still supported at:

```text
<gameDir>/tellus/external-features.json
```

For the current WLB instance that means:

```text
/home/kaijie/桌面/WorkLifeBalance/.minecraft/tellus/external-features.json
```

It can also be overridden with `-Dtellus.external.features.path=/path/to/external-features.json`.

Useful runtime switches:

```text
-Dtellus.arnis.overpass.enabled=false
-Dtellus.arnis.overpass.network=cache-first
-Dtellus.arnis.overpass.network=cache-only
-Dtellus.arnis.overpass.maxNetworkTilesPerSession=96
-Dtellus.arnis.overpass.prefetchMaxTiles=32
-Dtellus.arnis.overpass.endpoints=https://overpass-api.de/api/interpreter,https://overpass.osm.ch/api/interpreter,https://overpass.kumi.systems/api/interpreter
-Dtellus.map.tile.endpoints=https://tile.openstreetmap.org/%d/%d/%d.png,https://tile.openstreetmap.de/%d/%d/%d.png
-Dtellus.arnis.pbf.enabled=true
-Dtellus.arnis.pbf.directory=/path/to/osm-pbf-folder
-Dtellus.arnis.pbf.paths=/path/to/new-york.osm.pbf,/path/to/new-jersey.osm.pbf
-Dtellus.external.features.prefer=false
```

Network behavior is intentionally conservative to avoid burning VPN traffic:

- `cache-first` is the default. Existing cached OSM tiles are used without network; missing tiles may be fetched.
- `cache-only` never fetches missing tiles. It only uses `<gameDir>/tellus/cache/map/arnis-overpass/`, then falls back to Overture.
- `off` disables the Overpass source.
- `maxNetworkTilesPerSession` caps missing-tile downloads per game process. The default is `96`; after that, Tellus skips more Overpass requests and falls back.
- `prefetchMaxTiles` caps each UI cache warm-up batch. The default is `32`, so the button never starts an unbounded city download.
- Overpass endpoints are tried with per-endpoint cooldown. If one public source times out or rate-limits, it is skipped for a short period instead of delaying every following tile.
- Empty Overpass responses are still treated as a completed city-detail cache entry. This prevents empty ocean or low-detail tiles from being downloaded repeatedly.
- The spawn/world map tile loader also supports multiple raster tile endpoints through `tellus.map.tile.endpoints`; tile failures are written to the Tellus traffic log.
- Local `.osm.pbf` extracts are loaded before Overpass. By default Tellus scans `<gameDir>/tellus/cache/osm-pbf/`; configured files provide roads, buildings, landuse/natural/leisure/water areas, barriers/rail/waterway/power lines, and point details. When a generated tile is inside the loaded PBF coverage, Tellus skips live Overpass requests for that tile to avoid VPN traffic.

The world customization UI has a Data Sources entry named `Test OSM connectivity`. It sends a tiny Overpass query to each configured endpoint from the current computer and reports how many endpoints are reachable plus per-endpoint timing in the tooltip. This is intended for checking whether the current network can direct-connect before spending cache/download budget.

The same Data Sources section also has `Estimate OSM cache` and `Warm missing OSM cache`. The estimate uses the current spawnpoint and the Voxy pregen radius as the target area; when Voxy pregen is disabled it estimates a conservative 96-chunk spawn radius. It reports cached/missing raw Overpass tiles and compressed cache size. The warm-up button downloads only a capped batch of missing raw OSM tiles and reuses the existing WLB `127.0.0.1:18127` rule proxy, so direct-classified sources still avoid paid VPN traffic.

## Current Arnis-Style Rules

- OSM road tags are preserved on `RoadFeature`.
- `lanes=*` and `lanes:forward/backward` widen paved roads instead of using only the Tellus road class width.
- `sidewalk=*`, `sidewalk:left`, `sidewalk:right`, and `sidewalk:both` widen paved roads and paint smooth-stone sidewalk edge strips.
- unpaved surfaces such as `gravel`, `ground`, `dirt`, `sand`, and `mud` use the dirt-path road material.
- paved `footway`, `pedestrian`, and `cycleway` features render as smooth-stone paths when they are not explicitly unpaved.
- paved roads with at least two lanes get dashed white lane markings.
- `building:material`, `facade:material`, and `material` feed the building wall palette.
- `building:colour`, `facade:colour`, and `colour` feed the building wall color palette.
- `roof:material` feeds the roof palette, `roof:colour` feeds the roof color palette, and `roof:shape` still controls flat/gabled/hipped profiles.
- `height`, `building:height`, `building:levels`, `roof:height`, and `roof:levels` feed building and roof massing. Parsed Overture building cache version was bumped so older parsed tiles without roof height metadata are rebuilt.
- `amenity=parking` areas render as paved lots with painted parking stripes.
- selected `landuse`, `leisure`, and `natural` areas render as grass, dirt/gravel construction ground, cemetery moss, pitches, tracks, playgrounds, beaches, wetlands, rock, scree, wood, scrub, and heath surfaces.
- OSM forest, orchard, wood, tree-row, scrub, heath, grassland, wetland, parks, and gardens receive deterministic Arnis-style vegetation such as trees, shrubs, ferns, dead bushes, and rocks while avoiding roads/buildings.
- selected OSM `water=*`, `natural=water`, and `waterway=*` features render lightweight water surfaces/traces for city-scale ponds, canals, streams, ditches, and drains.
- OSM `barrier=*` lines render as fences, walls, hedges, guard rails, or iron-bar barriers when they do not collide with roads/buildings.
- OSM `railway=rail|light_rail|subway|tram` lines render as rail traces where the current surface can accept them.
- OSM `highway=traffic_signals` and `highway=crossing` nodes render traffic signal props and crosswalk stripes.
- OSM `entrance=*` / `door=*` nodes try to place actual building doors by snapping to a nearby safe facade column.
- selected OSM `amenity=*` nodes render benches, bicycle parking/shelter bars, fountains, and fuel markers.
- OSM `natural=tree` nodes render Arnis-style oak/spruce/birch/dark-oak/jungle/acacia trees, selecting species from `species`, `genus`, `genus:wikidata`, and `leaf_type` tags where available.

## Why Not Merge Arnis Region Files

Arnis is an offline world writer. Tellus is a Fabric chunk generator. Their final `.mca` output cannot be safely overlaid without solving projection alignment, terrain base height, feature ordering, chunk lifecycle, and collision handling. Keeping Tellus as the block-placement owner avoids those issues.

## Development Phases

1. Add neutral feature contracts and keep existing behavior unchanged.
2. Wrap current Overture road/building features behind the neutral contracts.
3. Add an Arnis JSON/GeoJSON adapter for roads and buildings.
4. Port Arnis styling rules into Tellus profiles:
   - road `surface`, `lanes`, lane markings, pedestrian paths, bridge/tunnel hints;
   - building category, material palette, roof shape, doors, simple interiors.
5. Wire source selection behind settings after the adapters are validated.

## License Notes

Tellus is LGPL-3.0. Arnis is Apache-2.0. Porting algorithms and interoperating through a data format is the lowest-risk path. If code is copied directly, keep copyright notices and review compatibility before upstream submission.
