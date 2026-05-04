package com.yucareux.tellus.worldgen.vegetation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class ArnisTreeGenerator {
   private static final int[][] ROUND1_PATTERN = {
      {-2, 0, 0},
      {2, 0, 0},
      {0, 0, -2},
      {0, 0, 2},
      {-1, 0, -1},
      {1, 0, 1},
      {1, 0, -1},
      {-1, 0, 1}
   };
   private static final int[][] ROUND2_PATTERN = {
      {3, 0, 0},
      {2, 0, -1},
      {2, 0, 1},
      {1, 0, -2},
      {1, 0, 2},
      {-3, 0, 0},
      {-2, 0, -1},
      {-2, 0, 1},
      {-1, 0, 2},
      {-1, 0, -2},
      {0, 0, -3},
      {0, 0, 3}
   };
   private static final int[][] ROUND3_PATTERN = {
      {3, 0, -1},
      {3, 0, 1},
      {2, 0, -2},
      {2, 0, 2},
      {1, 0, -3},
      {1, 0, 3},
      {-3, 0, -1},
      {-3, 0, 1},
      {-2, 0, -2},
      {-2, 0, 2},
      {-1, 0, 3},
      {-1, 0, -3}
   };
   private static final int[][][] ROUND_PATTERNS = {ROUND1_PATTERN, ROUND2_PATTERN, ROUND3_PATTERN};

   private static final int[][][] OAK_LEAVES_FILL = {
      {{-1, 3, 0}, {-1, 9, 0}},
      {{1, 3, 0}, {1, 9, 0}},
      {{0, 3, -1}, {0, 9, -1}},
      {{0, 3, 1}, {0, 9, 1}},
      {{0, 9, 0}, {0, 10, 0}}
   };
   private static final int[][][] SPRUCE_LEAVES_FILL = {
      {{-1, 3, 0}, {-1, 10, 0}},
      {{0, 3, -1}, {0, 10, -1}},
      {{1, 3, 0}, {1, 10, 0}},
      {{0, 3, 1}, {0, 10, 1}},
      {{0, 11, 0}, {0, 11, 0}}
   };
   private static final int[][][] BIRCH_LEAVES_FILL = {
      {{-1, 2, 0}, {-1, 7, 0}},
      {{1, 2, 0}, {1, 7, 0}},
      {{0, 2, -1}, {0, 7, -1}},
      {{0, 2, 1}, {0, 7, 1}},
      {{0, 7, 0}, {0, 8, 0}}
   };
   private static final int[][][] DARK_OAK_LEAVES_FILL = {
      {{-1, 3, 0}, {-1, 6, 0}},
      {{1, 3, 0}, {1, 6, 0}},
      {{0, 3, -1}, {0, 6, -1}},
      {{0, 3, 1}, {0, 6, 1}},
      {{0, 6, 0}, {0, 7, 0}}
   };
   private static final int[][][] JUNGLE_LEAVES_FILL = {
      {{-1, 7, 0}, {-1, 11, 0}},
      {{1, 7, 0}, {1, 11, 0}},
      {{0, 7, -1}, {0, 11, -1}},
      {{0, 7, 1}, {0, 11, 1}},
      {{0, 11, 0}, {0, 12, 0}}
   };
   private static final int[][][] ACACIA_LEAVES_FILL = {
      {{-1, 5, 0}, {-1, 8, 0}},
      {{1, 5, 0}, {1, 8, 0}},
      {{0, 5, -1}, {0, 8, -1}},
      {{0, 5, 1}, {0, 8, 1}},
      {{0, 8, 0}, {0, 9, 0}}
   };

   private static final BlockState OAK_LEAVES = persistent(Blocks.OAK_LEAVES.defaultBlockState());
   private static final BlockState SPRUCE_LEAVES = persistent(Blocks.SPRUCE_LEAVES.defaultBlockState());
   private static final BlockState BIRCH_LEAVES = persistent(Blocks.BIRCH_LEAVES.defaultBlockState());
   private static final BlockState DARK_OAK_LEAVES = persistent(Blocks.DARK_OAK_LEAVES.defaultBlockState());
   private static final BlockState JUNGLE_LEAVES = persistent(Blocks.JUNGLE_LEAVES.defaultBlockState());
   private static final BlockState ACACIA_LEAVES = persistent(Blocks.ACACIA_LEAVES.defaultBlockState());

   private ArnisTreeGenerator() {
   }

   public static boolean place(WorldGenLevel level, BlockPos base, ArnisTreeType type, int minY, int maxY, int flags) {
      TreeShape shape = shape(type);
      int baseY = base.getY();
      if (baseY < minY || baseY + shape.maxYOffset() > maxY) {
         return false;
      }

      MutableBlockPos cursor = new MutableBlockPos();
      for (int y = 0; y <= shape.logHeight(); y++) {
         cursor.set(base.getX(), baseY + y, base.getZ());
         if (!canPlaceTrunk(level.getBlockState(cursor))) {
            return false;
         }
      }

      for (int y = 0; y <= shape.logHeight(); y++) {
         cursor.set(base.getX(), baseY + y, base.getZ());
         level.setBlock(cursor, shape.logBlock(), flags);
      }
      for (int[][] fill : shape.leavesFill()) {
         fillLeaves(level, cursor, base, shape.leavesBlock(), fill[0], fill[1], flags);
      }
      for (int patternIndex = 0; patternIndex < ROUND_PATTERNS.length; patternIndex++) {
         for (int yOffset : shape.roundRanges()[patternIndex]) {
            placeRound(level, cursor, base, shape.leavesBlock(), yOffset, ROUND_PATTERNS[patternIndex], flags);
         }
      }
      return true;
   }

   public static int maxHeight(ArnisTreeType type) {
      return shape(type).maxYOffset();
   }

   private static void fillLeaves(
      WorldGenLevel level, MutableBlockPos cursor, BlockPos base, BlockState state, int[] from, int[] to, int flags
   ) {
      for (int x = from[0]; x <= to[0]; x++) {
         for (int y = from[1]; y <= to[1]; y++) {
            for (int z = from[2]; z <= to[2]; z++) {
               placeLeaf(level, cursor, base, state, x, y, z, flags);
            }
         }
      }
   }

   private static void placeRound(
      WorldGenLevel level, MutableBlockPos cursor, BlockPos base, BlockState state, int yOffset, int[][] pattern, int flags
   ) {
      for (int[] offset : pattern) {
         placeLeaf(level, cursor, base, state, offset[0], yOffset + offset[1], offset[2], flags);
      }
   }

   private static void placeLeaf(
      WorldGenLevel level, MutableBlockPos cursor, BlockPos base, BlockState state, int xOffset, int yOffset, int zOffset, int flags
   ) {
      cursor.set(base.getX() + xOffset, base.getY() + yOffset, base.getZ() + zOffset);
      if (canPlaceLeaf(level.getBlockState(cursor))) {
         level.setBlock(cursor, state, flags);
      }
   }

   private static boolean canPlaceTrunk(BlockState state) {
      return state.isAir() || state.is(BlockTags.LEAVES) || isSoftPlant(state);
   }

   private static boolean canPlaceLeaf(BlockState state) {
      return state.isAir() || state.is(BlockTags.LEAVES) || isSoftPlant(state);
   }

   private static boolean isSoftPlant(BlockState state) {
      return state.is(Blocks.FERN) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.VINE);
   }

   private static TreeShape shape(ArnisTreeType type) {
      return switch (type) {
         case SPRUCE -> new TreeShape(
            Blocks.SPRUCE_LOG.defaultBlockState(),
            9,
            SPRUCE_LEAVES,
            SPRUCE_LEAVES_FILL,
            new int[][]{{9, 7, 6, 4, 3}, {6, 3}, {}},
            11
         );
         case BIRCH -> new TreeShape(
            Blocks.BIRCH_LOG.defaultBlockState(),
            6,
            BIRCH_LEAVES,
            BIRCH_LEAVES_FILL,
            new int[][]{{6, 5, 4, 3, 2}, {2, 3, 4}, {}},
            8
         );
         case DARK_OAK -> new TreeShape(
            Blocks.DARK_OAK_LOG.defaultBlockState(),
            5,
            DARK_OAK_LEAVES,
            DARK_OAK_LEAVES_FILL,
            new int[][]{{6, 5, 4, 3}, {5, 4, 3}, {5, 4}},
            7
         );
         case JUNGLE -> new TreeShape(
            Blocks.JUNGLE_LOG.defaultBlockState(),
            10,
            JUNGLE_LEAVES,
            JUNGLE_LEAVES_FILL,
            new int[][]{{11, 10, 9, 8, 7}, {10, 9, 8}, {}},
            12
         );
         case ACACIA -> new TreeShape(
            Blocks.ACACIA_LOG.defaultBlockState(),
            6,
            ACACIA_LEAVES,
            ACACIA_LEAVES_FILL,
            new int[][]{{8, 7, 6, 5}, {7, 6, 5}, {7, 6}},
            9
         );
         default -> new TreeShape(
            Blocks.OAK_LOG.defaultBlockState(),
            8,
            OAK_LEAVES,
            OAK_LEAVES_FILL,
            new int[][]{{8, 7, 6, 5, 4, 3}, {7, 6, 5, 4}, {6, 5}},
            10
         );
      };
   }

   private static BlockState persistent(BlockState state) {
      return state.hasProperty(BlockStateProperties.PERSISTENT) ? state.setValue(BlockStateProperties.PERSISTENT, Boolean.TRUE) : state;
   }

   private record TreeShape(BlockState logBlock, int logHeight, BlockState leavesBlock, int[][][] leavesFill, int[][] roundRanges, int maxYOffset) {
   }
}
