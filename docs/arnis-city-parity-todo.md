# Arnis City Parity TODO

Goal: make Tellus generate city detail classes comparable to Arnis for the same OSM area, while keeping Tellus in charge of projection, terrain, chunk lifecycle, cache reuse, and WLB proxy routing.

Done means feature-class parity, not byte-identical `.mca` output. For example, New York should have terrain, roads, buildings, bridges/tunnels, parks/water, barriers, city props, and building details generated from OSM in-game without requiring a prewritten Arnis world.

## Ground Rules

- Keep the existing `tellus/cache/map/arnis-overpass/` raw OSM cache reusable across updates.
- Missing OSM data should try direct routes first through the WLB rule proxy; do not force paid VPN traffic.
- Add detail through neutral feature contracts first, then adapt into Tellus worldgen.
- Prefer capped, cache-first behavior for every new data family.
- Keep original Tellus Overture/PMTiles sources as fallback when OSM cache or network is missing.

## Arnis Element Inventory

Status meanings: `Done` is implemented in Tellus; `Partial` is visible but less detailed than Arnis; `Todo` is not implemented yet; `Watch` needs validation because data volume or geometry can be risky.

| Arnis module | OSM tags / behavior | Tellus status | Next task |
| --- | --- | --- | --- |
| `buildings.rs` | `building`, `building:part`, multipolygon shells, materials, roof shapes, storefront facades | Partial | Improve part stacking and Arnis-like interiors. |
| `buildings_interior.rs` | Room templates, stairs, beds, crafting/furnace/bookshelf/anvil/abandoned variants | Todo | Add opt-in simple interior pass for safe large buildings. |
| `doors.rs` | `door=*`, `entrance=*`, ground-level doors | Done | Validate snapping in dense downtown blocks. |
| `highways.rs` | `highway=*`, lanes, surface, sidewalks, zebra crossings, traffic signals, street lamps, bus stops, bridges/tunnels/layers | Partial | Tunnel portals remain; tunnel side shell/lighting exists. |
| `bridges.rs` | `bridge=*`, raised deck, ramps, edge rails/supports | Done | Validate long river bridges visually. |
| `railways.rs` | `railway=rail/light_rail/subway/tram`, slope rails, subway shells, crossing/tram-stop nodes | Partial | Surface rails and crossing/tram-stop markers exist; subway/tunnel shells remain. |
| `barriers.rs` | `barrier=*`, `fence_type`, `material`, `height`, bollard/gate nodes | Done | Validate gate replacement against dense barrier lines. |
| `amenities.rs` | parking, bicycle parking, bench, shelter, fountain, recycling, waste, vending/ATM, drinking water, fuel | Partial | Fountain/parking area shape and recycling metadata remain optional tuning. |
| `advertising.rs` | column, flag, poster box | Done | Validate density and collision. |
| `emergency.rs` | fire hydrant | Done | Validate density and underground filtering. |
| `historic.rs` | memorial, monument, wayside cross | Done | Add more subtype palettes if needed. |
| `tourisms.rs` | information boards | Done | Add map/guidepost subtype palettes if needed. |
| `man_made.rs` | pier, antenna/mast, chimney, water well, water tower | Done | Validate large towers against build height. |
| `power.rs` | power poles/towers/lines/minor lines | Done | Validate spacing and visual scale. |
| `tree.rs` | Arnis tree shapes, species/genus/leaf type | Done | Continue visual tuning for wild forests. |
| `landuse.rs` | grass, meadow, forest, orchard, farmland, cemetery, construction, traffic island, education, religious, industrial, military, railway, vineyard, brownfield, landfill, quarry | Partial | Selectors and first-pass props exist; visual density tuning remains. |
| `leisure.rs` | park, garden, nature reserve, golf/disc golf, schoolyard, playground, recreation ground, pitch, beach resort, dog park, pool, seating, water park, slipway, ice rink | Partial | Selectors and first-pass props exist; visual density tuning remains. |
| `natural.rs` | tree, wood, tree row, scrub, heath, grassland, beach/sand/dune/shoal, wetland, bare rock/scree/blockfield, mud, glacier, ridge/cliff/saddle/tundra/shrubbery | Partial | Selectors and first-pass surface/detail palettes exist; validation remains. |
| `surfaces.rs` | `surface=*` palettes for asphalt, gravel, wood, sand, tartan, grass, dirt, bricks, paving stones | Partial | Extend area/road surface palette where Tellus still falls back. |
| `waterways.rs` | river/canal/stream/ditch/drain with width and layer filters | Partial | Current light water traces exist; add width-aware channels when safe. |
| `water_areas.rs` | water multipolygons with inner islands | Partial | Current water areas exist; validate complex relation clipping. |

## P0 Data And Cache

- [x] Add neutral external feature source for roads/buildings.
- [x] Add automatic Overpass source with raw JSON cache.
- [x] Add cache estimate and capped warm-up actions to the world UI.
- [x] Preserve WLB proxy routing by using JVM networking.
- [x] Extend the neutral model to OSM line/area city details beyond roads/buildings.
- [x] Expand the Overpass query toward Arnis element families without making downloads unbounded.
- [x] Show expanded city-detail cache estimates in the UI.
- [x] Add OSM point city details for traffic signals, crossings, selected amenities, and trees.
- [x] Add OSM point city details for entrances and door nodes.
- [ ] Keep expanding cache profile in small versioned steps (`city-vN`) so raw cache remains reusable and new sidecars refresh in place.

## P1 Roads

- [x] Preserve road OSM tags on Tellus `RoadFeature`.
- [x] Use `surface=*` to distinguish paved paths and unpaved roads.
- [x] Use `lanes=*` to widen paved roads.
- [x] Render sidewalks from `sidewalk=*`.
- [x] Render dashed lane markings for multi-lane paved roads.
- [x] Add crossings and traffic signals.
- [x] Add street lamps and bus stop markers from Arnis `highways.rs`.
- [x] Add bridge edge rails beyond deck height/supports.
- [x] Add tunnel side shell and ceiling lights beyond current carving.
- [ ] Add explicit tunnel portal/headwall details at road tunnel mouths.
- [x] Add rail-related crossings.
- [x] Add rail line rendering from OSM `railway=*`.
- [x] Add barriers/guardrails/fences along OSM barrier lines.
- [x] Add barrier point nodes: bollards, blocks, gates, entrances, stiles.

## P1 Buildings

- [x] Preserve building tags through the external adapter.
- [x] Use building wall material and color tags.
- [x] Use roof material, color, shape, roof height, and roof levels.
- [x] Add entrances and doors from OSM `entrance=*` / `door=*` nodes.
- [ ] Improve building-part stacking and vertical alignment for dense downtown areas.
- [ ] Add simple Arnis-like interiors for accessible buildings.
- [x] Add OSM-aware windows/storefront variation for commercial buildings.

## P1 Areas And Surfaces

- [x] Add parking lots with surface material and painted parking markings.
- [x] Add landuse surfaces such as grass, residential, industrial, farmland, cemetery, and construction.
- [x] Add leisure surfaces such as parks, pitches, tracks, playgrounds, and gardens.
- [x] Add natural surfaces such as wood, scrub, heath, beach, wetland, rock, and scree.
- [x] Add water areas and waterways from OSM where they improve city-scale detail.
- [x] Add Arnis-style farmland crops/water points/hay bales.
- [x] Add Arnis-style cemetery graves/flowers/fence feel.
- [x] Add Arnis-style construction/quarry/brownfield/landfill clutter.
- [x] Add Arnis-style playground, pitch, pool, schoolyard, and parking-lot props.
- [x] Expand selectors for missing Arnis landuse/leisure/natural area types without making Overpass fetches unbounded.

## P2 City Props

- [x] Add amenity nodes/areas with high visual value: benches, bicycle parking, fuel, fountains, shelters.
- [x] Add tourism/historic/man_made/emergency/power/advertising feature families where Arnis renders them.
- [x] Add Arnis-style OSM natural tree nodes with species/genus/leaf-type selection.
- [x] Add tree distribution from OSM natural/landuse/leisure areas with building/road avoidance.
- [x] Replace coarse wild forest trees with the shared Arnis-style tree shapes.
- [x] Add street furniture placement that respects roads by shifting road-center OSM nodes to nearby non-road anchors.
- [ ] Add stronger building/sidewalk collision validation for street furniture after visual smoke tests.

## P3 Validation

- [ ] New York Manhattan smoke area: cache estimate, warm-up, generation, and visual pass.
- [ ] Dense European city smoke area with multipolygon buildings and narrow streets.
- [ ] Domestic-network pass: confirm direct routes are used when available and proxy/VPN only covers unreachable hosts.
- [ ] Cache-only pass: restart with `-Dtellus.arnis.overpass.network=cache-only` and confirm cached city details still render.
- [ ] Build and replace active WLB mod jar after each stable slice.
