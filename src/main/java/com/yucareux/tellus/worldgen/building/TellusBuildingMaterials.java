package com.yucareux.tellus.worldgen.building;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class TellusBuildingMaterials {
   private static final BlockState BUILDING_STATE = Blocks.GRAY_CONCRETE.defaultBlockState();
   private static final BlockState BUILDING_WINDOW_STATE = Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
   private static final BlockState BUILDING_TOWER_WINDOW_STATE = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
   private static final BlockState BUILDING_ROOF_STATE = Blocks.GRAY_CONCRETE.defaultBlockState();
   private static final BlockState BUILDING_SLATE_ROOF_STATE = Blocks.DEEPSLATE_TILES.defaultBlockState();
   private static final BlockState BUILDING_CLAY_TILE_ROOF_STATE = Blocks.BRICKS.defaultBlockState();
   private static final BlockState BUILDING_STONE_ROOF_STATE = Blocks.STONE_BRICKS.defaultBlockState();
   private static final BlockState BUILDING_METAL_ROOF_STATE = Blocks.IRON_BLOCK.defaultBlockState();
   private static final BlockState BUILDING_DARK_ROOF_STATE = Blocks.BLACK_CONCRETE.defaultBlockState();
   private static final BlockState BUILDING_GREEN_ROOF_STATE = Blocks.MOSS_BLOCK.defaultBlockState();
   private static final BlockState BUILDING_RED_ROOF_STATE = Blocks.RED_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_ORANGE_ROOF_STATE = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_BROWN_ROOF_STATE = Blocks.BROWN_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_WALL_STATE = Blocks.WHITE_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_ARID_WALL_STATE = Blocks.SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_SANDSTONE_WALL_STATE = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_COLD_WALL_STATE = Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_TROPICAL_WALL_STATE = Blocks.BIRCH_PLANKS.defaultBlockState();
   private static final BlockState BUILDING_BRICK_WALL_STATE = Blocks.BRICKS.defaultBlockState();
   private static final BlockState BUILDING_PALE_STONE_WALL_STATE = Blocks.CALCITE.defaultBlockState();
   private static final BlockState BUILDING_COMMERCIAL_WALL_STATE = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
   private static final BlockState BUILDING_INDUSTRIAL_WALL_STATE = Blocks.ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_TOWER_WALL_STATE = Blocks.CYAN_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_GLASS_WALL_STATE = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
   private static final BlockState BUILDING_METAL_WALL_STATE = Blocks.IRON_BLOCK.defaultBlockState();
   private static final BlockState BUILDING_RED_WALL_STATE = Blocks.RED_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_YELLOW_WALL_STATE = Blocks.YELLOW_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_BROWN_WALL_STATE = Blocks.BROWN_TERRACOTTA.defaultBlockState();
   private static final BlockState BUILDING_DARK_WALL_STATE = Blocks.GRAY_CONCRETE.defaultBlockState();
   private static final BlockState BUILDING_TRIM_STATE = Blocks.POLISHED_ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_WHITE_TRIM_STATE = Blocks.SMOOTH_QUARTZ.defaultBlockState();
   private static final BlockState BUILDING_SANDSTONE_TRIM_STATE = Blocks.CUT_SANDSTONE.defaultBlockState();
   private static final BlockState BUILDING_BRICK_TRIM_STATE = Blocks.STONE_BRICKS.defaultBlockState();
   private static final BlockState BUILDING_FLOOR_STATE = Blocks.POLISHED_ANDESITE.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_FLOOR_STATE = Blocks.OAK_PLANKS.defaultBlockState();
   private static final BlockState BUILDING_PARTITION_STATE = Blocks.SMOOTH_STONE.defaultBlockState();
   private static final BlockState BUILDING_STAIR_STATE = Blocks.OAK_STAIRS.defaultBlockState();
   private static final BlockState BUILDING_COMMERCIAL_STAIR_STATE = Blocks.STONE_BRICK_STAIRS.defaultBlockState();
   private static final BlockState BUILDING_SLAB_STATE = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
   private static final BlockState BUILDING_RESIDENTIAL_SLAB_STATE = Blocks.OAK_SLAB.defaultBlockState();
   private static final BlockState BUILDING_LIGHT_STATE = Blocks.SEA_LANTERN.defaultBlockState();

   private TellusBuildingMaterials() {
   }

   public static TellusBuildingMaterials.BuildingMaterialPalette resolvePalette(BuildingBlueprint blueprint) {
      return resolvePalette(blueprint.profile(), blueprint.blueprintSeed());
   }

   public static TellusBuildingMaterials.BuildingMaterialPalette resolvePalette(BuildingProfile profile, long blueprintSeed) {
      BlockState wall = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> switch (profile.climateFamily()) {
            case COLD -> BUILDING_COLD_WALL_STATE;
            case ARID -> BUILDING_ARID_WALL_STATE;
            case TROPICAL -> BUILDING_TROPICAL_WALL_STATE;
            case TEMPERATE -> BUILDING_RESIDENTIAL_WALL_STATE;
         };
         case COMMERCIAL -> BUILDING_COMMERCIAL_WALL_STATE;
         case INDUSTRIAL -> BUILDING_INDUSTRIAL_WALL_STATE;
         case TOWER -> BUILDING_TOWER_WALL_STATE;
         case GENERIC -> BUILDING_STATE;
      };
      BlockState trim = BUILDING_TRIM_STATE;
      BlockState roof = BUILDING_ROOF_STATE;
      if (profile.archetype() == BuildingProfile.Archetype.HOUSE) {
         TellusBuildingStyles.HouseStyle houseStyle = TellusBuildingStyles.resolveHouseStyle(profile, blueprintSeed);
         wall = switch (houseStyle) {
            case WHITE_SLATE -> BUILDING_RESIDENTIAL_WALL_STATE;
            case WARM_CLAY -> BUILDING_SANDSTONE_WALL_STATE;
            case GRAY_CHARCOAL -> BUILDING_COLD_WALL_STATE;
            case BRICK_SLATE -> BUILDING_BRICK_WALL_STATE;
            case PALE_STONE -> BUILDING_PALE_STONE_WALL_STATE;
         };
         trim = switch (houseStyle) {
            case WHITE_SLATE -> BUILDING_WHITE_TRIM_STATE;
            case WARM_CLAY -> BUILDING_SANDSTONE_TRIM_STATE;
            case GRAY_CHARCOAL -> BUILDING_TRIM_STATE;
            case BRICK_SLATE, PALE_STONE -> BUILDING_BRICK_TRIM_STATE;
         };
         roof = switch (houseStyle) {
            case WHITE_SLATE, BRICK_SLATE -> BUILDING_SLATE_ROOF_STATE;
            case WARM_CLAY -> BUILDING_CLAY_TILE_ROOF_STATE;
            case GRAY_CHARCOAL -> BUILDING_ROOF_STATE;
            case PALE_STONE -> BUILDING_STONE_ROOF_STATE;
         };
      }

      BlockState materialWall = wallBlockForMaterial(profile.wallMaterial());
      if (materialWall != null) {
         wall = materialWall;
         trim = trimBlockForWallMaterial(profile.wallMaterial(), trim);
      }

      BlockState materialRoof = roofBlockForMaterial(profile.roofMaterial());
      if (materialRoof != null) {
         roof = materialRoof;
      }

      BlockState colorWall = wallBlockForColor(profile.wallColor());
      if (colorWall != null) {
         wall = colorWall;
      }

      BlockState colorRoof = roofBlockForColor(profile.roofColor());
      if (colorRoof != null) {
         roof = colorRoof;
      }

      BlockState floor = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_RESIDENTIAL_FLOOR_STATE;
         default -> BUILDING_FLOOR_STATE;
      };
      BlockState stair = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_STAIR_STATE;
         default -> BUILDING_COMMERCIAL_STAIR_STATE;
      };
      BlockState slab = switch (profile.archetype()) {
         case HOUSE, APARTMENT -> BUILDING_RESIDENTIAL_SLAB_STATE.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
         default -> BUILDING_SLAB_STATE.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
      };
      BlockState window = profile.archetype() == BuildingProfile.Archetype.TOWER || profile.archetype() == BuildingProfile.Archetype.COMMERCIAL
         ? BUILDING_TOWER_WINDOW_STATE
         : BUILDING_WINDOW_STATE;
      return new TellusBuildingMaterials.BuildingMaterialPalette(wall, trim, roof, window, floor, BUILDING_PARTITION_STATE, stair, slab, BUILDING_LIGHT_STATE);
   }

   private static BlockState wallBlockForMaterial(String material) {
      if (material == null || material.isBlank()) {
         return null;
      }

      if (containsAny(material, "brick", "masonry")) {
         return BUILDING_BRICK_WALL_STATE;
      }
      if (containsAny(material, "concrete", "cement", "plaster", "stucco")) {
         return BUILDING_COMMERCIAL_WALL_STATE;
      }
      if (containsAny(material, "stone", "limestone", "marble")) {
         return BUILDING_PALE_STONE_WALL_STATE;
      }
      if (containsAny(material, "granite", "slate", "andesite")) {
         return BUILDING_INDUSTRIAL_WALL_STATE;
      }
      if (containsAny(material, "wood", "timber", "log")) {
         return BUILDING_TROPICAL_WALL_STATE;
      }
      if (containsAny(material, "glass")) {
         return BUILDING_GLASS_WALL_STATE;
      }
      if (containsAny(material, "metal", "steel", "aluminium", "aluminum")) {
         return BUILDING_METAL_WALL_STATE;
      }
      return null;
   }

   private static BlockState trimBlockForWallMaterial(String material, BlockState fallback) {
      if (material == null || material.isBlank()) {
         return fallback;
      }

      if (containsAny(material, "brick", "stone", "limestone", "marble", "granite", "slate")) {
         return BUILDING_BRICK_TRIM_STATE;
      }
      if (containsAny(material, "concrete", "cement", "plaster", "stucco", "glass", "metal", "steel", "aluminium", "aluminum")) {
         return BUILDING_TRIM_STATE;
      }
      if (containsAny(material, "wood", "timber", "log")) {
         return BUILDING_WHITE_TRIM_STATE;
      }
      return fallback;
   }

   private static BlockState wallBlockForColor(String color) {
      if (color == null || color.isBlank()) {
         return null;
      }

      if (containsAny(color, "white", "cream", "ivory", "#fff", "#ffffff")) {
         return BUILDING_RESIDENTIAL_WALL_STATE;
      }
      if (containsAny(color, "light gray", "light grey", "silver", "#ccc", "#cccccc")) {
         return BUILDING_COLD_WALL_STATE;
      }
      if (containsAny(color, "gray", "grey")) {
         return BUILDING_COMMERCIAL_WALL_STATE;
      }
      if (containsAny(color, "black", "dark")) {
         return BUILDING_DARK_WALL_STATE;
      }
      if (containsAny(color, "red", "maroon")) {
         return BUILDING_RED_WALL_STATE;
      }
      if (containsAny(color, "yellow", "gold", "beige")) {
         return BUILDING_YELLOW_WALL_STATE;
      }
      if (containsAny(color, "brown", "tan")) {
         return BUILDING_BROWN_WALL_STATE;
      }
      if (containsAny(color, "blue", "cyan")) {
         return BUILDING_TOWER_WALL_STATE;
      }
      return null;
   }

   private static BlockState roofBlockForColor(String color) {
      if (color == null || color.isBlank()) {
         return null;
      }

      if (containsAny(color, "red", "maroon")) {
         return BUILDING_RED_ROOF_STATE;
      }
      if (containsAny(color, "orange")) {
         return BUILDING_ORANGE_ROOF_STATE;
      }
      if (containsAny(color, "brown", "tan")) {
         return BUILDING_BROWN_ROOF_STATE;
      }
      if (containsAny(color, "black", "dark")) {
         return BUILDING_DARK_ROOF_STATE;
      }
      if (containsAny(color, "gray", "grey", "silver")) {
         return BUILDING_ROOF_STATE;
      }
      if (containsAny(color, "green")) {
         return BUILDING_GREEN_ROOF_STATE;
      }
      return null;
   }

   private static BlockState roofBlockForMaterial(String material) {
      if (material == null || material.isBlank()) {
         return null;
      }

      if (containsAny(material, "tile", "clay", "terracotta", "brick")) {
         return BUILDING_CLAY_TILE_ROOF_STATE;
      }
      if (containsAny(material, "slate", "shingle")) {
         return BUILDING_SLATE_ROOF_STATE;
      }
      if (containsAny(material, "stone", "concrete", "cement")) {
         return BUILDING_STONE_ROOF_STATE;
      }
      if (containsAny(material, "metal", "steel", "tin", "copper", "zinc", "aluminium", "aluminum")) {
         return BUILDING_METAL_ROOF_STATE;
      }
      if (containsAny(material, "asphalt", "bitumen", "tar", "rubber")) {
         return BUILDING_DARK_ROOF_STATE;
      }
      if (containsAny(material, "grass", "green", "vegetation", "moss")) {
         return BUILDING_GREEN_ROOF_STATE;
      }
      return null;
   }

   private static boolean containsAny(String value, String... parts) {
      for (String part : parts) {
         if (value.contains(part)) {
            return true;
         }
      }
      return false;
   }

   public static BlockState resolveLodFacadeBlock(
      BuildingBlueprint blueprint, TellusBuildingMaterials.BuildingMaterialPalette palette, int boundaryDistance, int floorIndex
   ) {
      if (!blueprint.isFacadeCell(boundaryDistance, floorIndex)) {
         return palette.wall();
      }

      return switch (blueprint.profile().archetype()) {
         case TOWER, COMMERCIAL -> palette.window();
         case APARTMENT -> floorIndex > 0 ? palette.window() : palette.wall();
         case HOUSE, INDUSTRIAL, GENERIC -> palette.wall();
      };
   }

   public static BlockState resolveLodRoofBlock(TellusBuildingMaterials.BuildingMaterialPalette palette, boolean roofEdge) {
      return roofEdge ? palette.trim() : palette.roof();
   }

   public record BuildingMaterialPalette(
      BlockState wall,
      BlockState trim,
      BlockState roof,
      BlockState window,
      BlockState floor,
      BlockState partition,
      BlockState stair,
      BlockState slab,
      BlockState light
   ) {
   }
}
