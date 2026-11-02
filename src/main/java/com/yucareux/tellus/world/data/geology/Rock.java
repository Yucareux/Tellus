package com.yucareux.tellus.world.data.geology;

/**
 * Rock kinds a geologic map unit is read as. Colour variants are separate kinds because they need different
 * blocks: a red sandstone and a buff one look nothing alike. {@code StrataPolicy} gives each kind its palette.
 */
public enum Rock {
   SANDSTONE,
   RED_SANDSTONE,
   GREY_SANDSTONE,
   CONGLOMERATE,
   SHALE,
   RED_MUDSTONE,
   BLACK_SHALE,
   LIMESTONE,
   DARK_LIMESTONE,
   CHALK,
   DOLOMITE,
   MARL,
   EVAPORITE,
   GRANITE,
   PINK_GRANITE,
   GREY_GRANITE,
   DIORITE,
   GABBRO,
   ULTRAMAFIC,
   RHYOLITE,
   ANDESITE,
   BASALT,
   TUFF,
   SLATE,
   SCHIST,
   GNEISS,
   QUARTZITE,
   MARBLE,
   AMPHIBOLITE
}
