# Tellus Distant Horizons fork: 3.3.4-tellus.1

Based on upstream Distant Horizons 3.3.4 (`eb1007cb6cc3e0419effe2b40ab485cf24e55b43`)
and core/API `64410621767ee697e8bc175c2a3ebe4c5e2ce520`. The fork implements official
API 7.2.0 plus the optional generation-availability extension. `ConfigEntry` keeps the
value-only `setApiValue` that 3.3.2 replaced, so older Tellus builds still apply their
runtime DH overrides.

The loader uses upstream's build tooling (Shadow 9.6.1, Unimined 1.4.43). Tellus
patches preserve the 14-bit vertical format, old-cache conversion, child-upsample
metadata, Iris atlas allocation/upload state, and the neutral atlas with textures
disabled. The new mipmap filter and upstream renderer remain in place. Automatic
updates to stock DH are disabled even when an old config enabled the updater.

The generation queue calls `getGenerationAvailability` before starting work.
READY begins generation, SPLIT subdivides useful partially cached regions, and
WAIT leaves unavailable regions queued without consuming workers. Generators that
do not implement the extension remain ready by default. Queue selection avoids
per-candidate allocations; generation failure and cancellation release capacity
and pooled results. Completed columns at the required generation stage avoid
redundant child database scans.

The quad tree never replaces a drawn LOD with sections lacking data for their
whole area (they are still queued for generation), render loading no longer holds
pool threads while uploads wait for the render thread, and world-gen queuing
restarts from the current sections and player position instead of an old snapshot.
Zoom-boosted detail only follows a zoom held for half a second (MC 26.3's first
frames after launch look like an 8-16x zoom), and generation positions finer than
the tree still wants are dropped once a zoom ends or the player moves away.

Use the matching updated Tellus jar compiled against API 7.1. Tellus marks complete
LOD columns as FEATURES and checks managed coverage without scanning enormous
empty regions. Terrain sampling and detail settings are unchanged.

## Building

Use JDK 25 and the included Gradle wrapper. Build versions sequentially because
Manifold definitions and common build outputs are shared. Clean the loader/common
outputs when switching targets, then stage the jars before building the next one.

Supported `mcVer` values: `26.3.0`, `26.2.0`, `1.21.11`, `1.21.1`, and `1.20.1`.
Use `:fabric:remapJar` for every version; use `:neoforge:remapJar` except for 1.20.1,
which uses `:forge:remapJar`. Run `:api:test :core:test` once for core regressions.
The Tellus repository's `compat/distant-horizons/build.py` automates this matrix.

Ship only the loader-specific `DistantHorizons-LOADER-3.3.4-tellus.1-MC.jar`, replacing
the old DH jar for that instance. Do not install two loader/version variants.

## Verification

All 80 API/core tests pass, including height/cache conversion, child upsampling,
readiness selection, cancellation, and synchronous/asynchronous failure cleanup.
All ten jars preserve the official API and resolve all 107 DH class/member
references in Iris 1.11.4 and 1.11.6. Language resources and core/API bytecode are
identical across loaders. The Apple M4 OpenGL smoke check validates initial atlas
allocation, neutral pixels, API texture IDs, pixel/sampler state, and atlas growth.
The Tellus repository contains the complete runtime/test notes in
`docs/distant-horizons-3.3.1.md` and reproducible source patches in
`compat/distant-horizons/patches/`.

A full interactive shader-pack playtest on Windows/NVIDIA remains separate from
these local checks.
