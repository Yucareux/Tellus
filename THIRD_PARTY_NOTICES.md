# Third-Party Notices

## Overture Maps base theme

This product downloads and caches Overture Maps base-theme vector tiles for land-cover and water classification.

Copyright Overture Maps Foundation and OpenStreetMap contributors. The base theme is licensed under ODbL.

Land-cover data is derived from ESA WorldCover 2020: © ESA WorldCover project / Contains modified Copernicus Sentinel data (2020) processed by ESA WorldCover consortium. Available under CC BY 4.0.

Source and attribution: https://docs.overturemaps.org/attribution/

## ETH Global Canopy Height 2020

This product downloads and caches small image tiles from the ETH Global Canopy Height 2020 product through its ArcGIS Living Atlas image service.

Dataset: Lang, N., Schindler, K., and Wegner, J. D. (2022), ETH_GlobalCanopyHeight_10m_2020_version1. ETH Zurich. https://doi.org/10.3929/ethz-b-000609802

Related publication: Lang, N., Jetz, W., Schindler, K., and Wegner, J. D. (2023), “A high-resolution canopy height model of the Earth,” Nature Ecology & Evolution.

Licensed under Creative Commons Attribution 4.0 International.

ArcGIS Living Atlas item: https://www.arcgis.com/home/item.html?id=2a3dfb00c2c6425f85bd70da420d58eb

## LERC and Chicory

All supported builds include Esri's LERC WebAssembly decoder and the Chicory pure-Java WebAssembly runtime to decode canopy-height image tiles without native libraries.

LERC copyright Esri. Licensed under the Apache License, Version 2.0. Source: https://github.com/Esri/lerc

Chicory copyright the Chicory project contributors. Licensed under the Apache License, Version 2.0. Source: https://github.com/dylibso/chicory

## Arnis

This product includes code derived from Arnis.

Copyright (c) 2022-2026 Louis Erbkamm (louis-e)

Licensed under the Apache License, Version 2.0.

Source: https://github.com/louis-e/arnis

## Mapterhorn Terrain

This product can download and cache Mapterhorn terrain DEM tiles for land terrain.

Source: https://mapterhorn.com/

## OpenWaters Seascape

This product can download and cache OpenWaters Seascape bathymetry raster DEM tiles for ocean terrain.

Published tile compilation license: CC BY 4.0.

Source: https://github.com/openwatersio/seascape

Tiles: https://tiles.openwaters.io/seascape/

## Eiffel Tower landmark model

The Eiffel Tower landmark (`tellus/landmarks/eiffel_tower.tlm`) is adapted from "Eiffel Tower" by Newcandle, published on Thingiverse (https://www.thingiverse.com/thing:912478) and on Wikimedia Commons (https://commons.wikimedia.org/wiki/File:EiffelTower_fixed.stl).

Licensed under Creative Commons Attribution-ShareAlike 3.0 Unported (https://creativecommons.org/licenses/by-sa/3.0/). Changes: converted from STL to the Tellus landmark mesh format and scaled to a 124.9 m base and 330 m height. The adapted model is shared under the same license; this license covers that file only, not the rest of Tellus.

## GLiM rock classes

The rock-class lookup (`tellus/geology/glim_lithology_60s.bin.xz`) is derived from the OpenLandMap global lithology class map at 250 m by Tomislav Hengl (2018), https://doi.org/10.5281/zenodo.1464846. That map is derived from the USGS global ecological land units and the Global Lithological Map (GLiM): Hartmann, J. and Moosdorf, N. (2012), "The new global lithological map database GLiM: A representation of rock properties at the Earth surface", Geochemistry, Geophysics, Geosystems, 13, Q12004.

Licensed under Creative Commons Attribution-ShareAlike 4.0 International (https://creativecommons.org/licenses/by-sa/4.0/). Changes: the majority class of each 60-arc-second cell, land cells that no neighbour shares merged into their neighbours' class, water cells within two cells of land given the nearest land class, and run-length encoded. The derived lookup is shared under the same license; this license covers that file only, not the rest of Tellus.

## Macrostrat geologic maps

This product downloads and caches geologic map tiles from Macrostrat (https://macrostrat.org/) to choose the rock of cliffs and mountain faces.

Peters, S.E., Husson, J.M., and Czaplewski, J. (2018), "Macrostrat: a platform for geological data integration and deep-time Earth crust research", Geochemistry, Geophysics, Geosystems, 19, 1393-1409. Map units are compiled from the published geologic maps listed at https://macrostrat.org/maps.

Licensed under Creative Commons Attribution 4.0 International.

## Golden Gate Bridge landmark model

The Golden Gate Bridge landmark (`tellus/landmarks/golden_gate_bridge.tlm`) is converted from the "Golden Gate Bridge" SketchUp model published on the 3D Warehouse (https://3dwarehouse.sketchup.com/; model contributors listed in the file as Google / Google 3D Warehouse), used under the 3D Warehouse General Model License Agreement. Changes: converted from SketchUp to the Tellus landmark mesh format, placed from the model's UTM georeference, see-through textures cut to their opaque parts, the ground plaza left out, the Marin side of the deck raised to the real road's height, four support towers added under the Marin approach viaduct and materials replaced by Minecraft blocks.
