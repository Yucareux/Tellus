package com.yucareux.tellus.world.data.geology;

import java.util.Objects;

/**
 * Bedrock of one geologic map unit: its main rock and, for interbedded units such as "shale and limestone", a
 * second rock with its share of the beds.
 */
public record MapUnit(Rock main, Rock second, int secondPercent) {
   public MapUnit {
      Objects.requireNonNull(main, "main");
      if (second == null ? secondPercent != 0 : secondPercent <= 0 || secondPercent >= 100) {
         throw new IllegalArgumentException("A second rock needs a share between 1 and 99 percent");
      }
   }

   public static MapUnit of(Rock rock) {
      return new MapUnit(rock, null, 0);
   }
}
