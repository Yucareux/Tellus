package com.yucareux.tellus.world.data.geology;

/**
 * GLiM first-level rock classes (Hartmann and Moosdorf 2012). The ordinal is the class code used by the
 * OpenLandMap lithology class map and the bundled lookup; code 0 is water or missing data.
 */
public enum Lithology {
   UNKNOWN,
   ACID_PLUTONIC,
   ACID_VOLCANIC,
   BASIC_PLUTONIC,
   BASIC_VOLCANIC,
   CARBONATE_SEDIMENTARY,
   EVAPORITE,
   ICE_AND_GLACIERS,
   INTERMEDIATE_PLUTONIC,
   INTERMEDIATE_VOLCANIC,
   METAMORPHIC,
   MIXED_SEDIMENTARY,
   PYROCLASTIC,
   SILICICLASTIC_SEDIMENTARY,
   UNCONSOLIDATED_SEDIMENT,
   UNDEFINED;

   private static final Lithology[] BY_CODE = values();

   public static Lithology fromCode(int code) {
      return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : UNKNOWN;
   }
}
