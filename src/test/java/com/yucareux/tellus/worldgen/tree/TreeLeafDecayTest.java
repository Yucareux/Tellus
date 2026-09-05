package com.yucareux.tellus.worldgen.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.yucareux.tellus.world.data.canopy.TellusCanopyHeightSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TreeLeafDecayTest {
   private static final Map<Holder.Reference<Block>, Object> ORIGINAL_TAGS = new HashMap<>();
   private static Field tagsField;
   private static Method updateDistance;
   private static Method decaying;

   @BeforeAll
   static void bootstrapMinecraft() throws Exception {
      assumeFalse(isMinecraftForge(), "Forge's raw JUnit bootstrap cannot initialize vanilla block registries");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      updateDistance = LeavesBlock.class.getDeclaredMethod("updateDistance", BlockState.class, LevelAccessor.class, BlockPos.class);
      updateDistance.setAccessible(true);
      decaying = LeavesBlock.class.getDeclaredMethod("decaying", BlockState.class);
      decaying.setAccessible(true);

      // Plain JUnit does not load data-pack tags. Bind just the vanilla tags
      // needed by this fixture, and restore the registry when the tests finish.
      tagsField = Holder.Reference.class.getDeclaredField("tags");
      tagsField.setAccessible(true);
      Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
      bindTags.setAccessible(true);
      for (Block block : BuiltInRegistries.BLOCK) {
         Holder.Reference<Block> holder = block.builtInRegistryHolder();
         ORIGINAL_TAGS.put(holder, tagsField.get(holder));
         List<Object> tags = new ArrayList<>();
         if (block instanceof LeavesBlock) {
            tags.add(BlockTags.LEAVES);
            tags.add(BlockTags.REPLACEABLE_BY_TREES);
         }
         String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
         if (name.endsWith("_log") || name.endsWith("_wood")) {
            tags.add(BlockTags.LOGS);
            try {
               tags.add(BlockTags.class.getField("PREVENTS_NEARBY_LEAF_DECAY").get(null));
            } catch (NoSuchFieldException ignored) {
               // Older versions use LOGS directly for leaf support.
            }
         }
         if (block == Blocks.FERN) {
            tags.add(BlockTags.REPLACEABLE_BY_TREES);
         }
         bindTags.invoke(holder, tags);
      }
   }

   @AfterAll
   static void restoreTags() throws Exception {
      for (Map.Entry<Holder.Reference<Block>, Object> entry : ORIGINAL_TAGS.entrySet()) {
         tagsField.set(entry.getKey(), entry.getValue());
      }
   }

   @Test
   void supportedLeavesSurviveAndBecomeEligibleForVanillaDecayAfterChopping() throws Exception {
      TestWorld world = new TestWorld();
      TreePlacement tree = new TreePlacement(world.level);
      BlockPos root = new BlockPos(0, 64, 0);
      // Deliberately submit foliage before wood and reuse a mutable position.
      BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
      for (int x = 6; x >= 1; x--) {
         tree.setLeaves(cursor.set(x, 64, 0), Blocks.OAK_LEAVES.defaultBlockState());
      }
      assertTrue(tree.setLog(root, Blocks.OAK_LOG.defaultBlockState()));
      tree.finish();

      for (int x = 1; x <= 6; x++) {
         assertEquals(x, world.state(root.offset(x, 0, 0)).getValue(LeavesBlock.DISTANCE));
      }
      assertStableCanopy(world);
      assertDecaysAfterChopping(world);
   }

   @Test
   void disconnectedAndOutOfRangeLeavesAreOmittedWithoutReplacingPlants() {
      TestWorld world = new TestWorld();
      TreePlacement tree = new TreePlacement(world.level);
      BlockPos root = new BlockPos(0, 64, 0);
      tree.setLog(root, Blocks.OAK_LOG.defaultBlockState());
      world.blocks.put(root.offset(7, 0, 0), Blocks.FERN.defaultBlockState());
      for (int x = 1; x <= 8; x++) {
         tree.setLeaves(root.offset(x, 0, 0), Blocks.OAK_LEAVES.defaultBlockState());
      }
      tree.setLeaves(root.offset(1, 1, 1), Blocks.OAK_LEAVES.defaultBlockState());
      tree.finish();

      assertEquals(6, world.leafCount());
      assertTrue(world.state(root.offset(7, 0, 0)).is(Blocks.FERN));
      assertTrue(world.state(root.offset(8, 0, 0)).isAir());
      assertTrue(world.state(root.offset(1, 1, 1)).isAir());
   }

   @Test
   void existingTreeSupportAndPersistentFoliageKeepTheirVanillaBehavior() throws Exception {
      TestWorld world = new TestWorld();
      TreePlacement tree = new TreePlacement(world.level);
      BlockPos root = new BlockPos(0, 64, 0);
      world.blocks.put(root.offset(10, 0, 0), Blocks.BIRCH_LOG.defaultBlockState());
      world.blocks.put(root.offset(9, 0, 0), Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
      BlockPos decoration = root.above();
      world.blocks.put(decoration, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
      tree.setLog(root, Blocks.OAK_LOG.defaultBlockState());
      tree.setLeaves(decoration, Blocks.OAK_LEAVES.defaultBlockState());
      for (int x = 1; x <= 8; x++) {
         tree.setLeaves(root.offset(x, 0, 0), Blocks.OAK_LEAVES.defaultBlockState());
      }
      tree.finish();

      for (int x = 1; x <= 9; x++) {
         BlockState state = world.state(root.offset(x, 0, 0));
         assertEquals(Math.min(x, 10 - x), state.getValue(LeavesBlock.DISTANCE));
         assertFalse(state.getValue(LeavesBlock.PERSISTENT));
      }
      assertTrue(world.state(decoration).is(Blocks.BIRCH_LEAVES));
      assertTrue(world.state(decoration).getValue(LeavesBlock.PERSISTENT));
      assertEquals(1, world.state(decoration).getValue(LeavesBlock.DISTANCE));
      world.blocks.remove(root);
      settleVanillaDistances(world);
      assertFalse((boolean)decaying.invoke(world.state(root.offset(9, 0, 0)).getBlock(), world.state(root.offset(9, 0, 0))));
      assertTrue(world.state(decoration).getValue(LeavesBlock.PERSISTENT));
   }

   @Test
   void logThroughBufferedFoliageWinsAndFailedWoodCannotSupportLeaves() {
      TestWorld world = new TestWorld();
      TreePlacement tree = new TreePlacement(world.level);
      BlockPos root = new BlockPos(0, 64, 0);
      tree.setLeaves(root, Blocks.OAK_LEAVES.defaultBlockState());
      tree.setLog(root, Blocks.OAK_LOG.defaultBlockState());
      BlockPos obstruction = root.offset(20, 0, 0);
      world.blocks.put(obstruction, Blocks.STONE.defaultBlockState());
      assertFalse(tree.setLog(obstruction, Blocks.OAK_LOG.defaultBlockState()));
      tree.setLeaves(obstruction.above(), Blocks.OAK_LEAVES.defaultBlockState());
      tree.finish();

      assertTrue(world.state(root).is(Blocks.OAK_LOG));
      assertTrue(world.state(obstruction).is(Blocks.STONE));
      assertTrue(world.state(obstruction.above()).isAir());
   }

   @Test
   void placementRespectsGenerationBoundariesAndKeepsFullHeightCoordinates() throws Exception {
      TestWorld world = new TestWorld();
      world.maxX = 2;
      world.denied = Set.of(new BlockPos(1, 12288, 0));
      TreePlacement tree = new TreePlacement(world.level);
      BlockPos lower = new BlockPos(0, 8192, 0);
      BlockPos upper = lower.above(4096);
      tree.setLog(lower, Blocks.OAK_LOG.defaultBlockState());
      tree.setLog(upper, Blocks.BIRCH_LOG.defaultBlockState());
      for (int x = 1; x <= 4; x++) {
         tree.setLeaves(lower.offset(x, 0, 0), Blocks.OAK_LEAVES.defaultBlockState());
         tree.setLeaves(upper.offset(x, 0, 0), Blocks.BIRCH_LEAVES.defaultBlockState());
      }
      tree.setLeaves(new BlockPos(0, world.maxY, 0), Blocks.OAK_LEAVES.defaultBlockState());
      tree.finish();

      assertEquals(2, world.leafCount());
      assertEquals(2, world.state(lower.offset(2, 0, 0)).getValue(LeavesBlock.DISTANCE));
      assertTrue(world.state(upper.offset(2, 0, 0)).isAir());
   }

   @Test
   void everyTreeProfileSmallTreeAndBushProducesStableDecayableLeaves() throws Exception {
      for (TellusProceduralTreeGenerator.Profile profile : TellusProceduralTreeGenerator.Profile.values()) {
         for (long seed : new long[]{0L, 17L, 912341L}) {
            for (double height : new double[]{0.0, 5.0, 96.0}) {
               TellusCanopyHeightSource.CanopySample canopy = new TellusCanopyHeightSource.CanopySample(
                  true, height, height, height, height, height, height, 13, 9
               );
               TellusProceduralTreeGenerator.TreePlan plan = TellusProceduralTreeGenerator.plan(profile, canopy, seed);
               TestWorld world = new TestWorld();
               assertTrue(TellusProceduralTreeGenerator.place(world.level, new BlockPos(0, 64, 0), plan, seed));
               assertTrue(world.leafCount() >= 8, "missing canopy: " + plan);
               assertStableCanopy(world);
               assertDecaysAfterChopping(world);
            }
         }
         assertFalse(TellusProceduralTreeGenerator.leavesState(profile, 17L).getValue(LeavesBlock.PERSISTENT));
      }
   }

   private static void assertStableCanopy(TestWorld world) throws Exception {
      for (Map.Entry<BlockPos, BlockState> entry : world.blocks.entrySet()) {
         BlockState state = entry.getValue();
         if (state.getBlock() instanceof LeavesBlock) {
            assertFalse(state.getValue(LeavesBlock.PERSISTENT));
            assertTrue(state.getValue(LeavesBlock.DISTANCE) < 7);
            assertEquals(state, updateDistance.invoke(null, state, world.level, entry.getKey()));
            assertFalse((boolean)decaying.invoke(state.getBlock(), state));
            assertFalse(state.isRandomlyTicking());
         }
      }
   }

   private static void assertDecaysAfterChopping(TestWorld world) throws Exception {
      world.blocks.entrySet().removeIf(entry -> LeavesBlock.getOptionalDistanceAt(entry.getValue()).orElse(7) == 0);
      settleVanillaDistances(world);
      for (BlockState state : world.blocks.values()) {
         if (state.getBlock() instanceof LeavesBlock) {
            assertEquals(7, state.getValue(LeavesBlock.DISTANCE));
            assertTrue((boolean)decaying.invoke(state.getBlock(), state));
            assertTrue(state.isRandomlyTicking());
         }
      }
   }

   private static void settleVanillaDistances(TestWorld world) throws Exception {
      for (int tick = 0; tick < 7; tick++) {
         Map<BlockPos, BlockState> updated = new HashMap<>(world.blocks);
         for (Map.Entry<BlockPos, BlockState> entry : world.blocks.entrySet()) {
            if (entry.getValue().getBlock() instanceof LeavesBlock) {
               updated.put(entry.getKey(), (BlockState)updateDistance.invoke(null, entry.getValue(), world.level, entry.getKey()));
            }
         }
         world.blocks.clear();
         world.blocks.putAll(updated);
      }
   }

   private static boolean isMinecraftForge() {
      try {
         Class.forName("net.minecraftforge.fml.ModList", false, TreeLeafDecayTest.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }

   private static final class TestWorld {
      final Map<BlockPos, BlockState> blocks = new HashMap<>();
      int maxX = 128;
      final int maxY = 16384;
      Set<BlockPos> denied = Set.of();
      final WorldGenLevel level = (WorldGenLevel)Proxy.newProxyInstance(
         WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
               case "getBlockState" -> {
                  BlockPos position = (BlockPos)args[0];
                  assertTrue(this.canWrite(position), "out-of-region read: " + position);
                  yield this.state(position);
               }
               case "setBlock" -> {
                  BlockPos position = (BlockPos)args[0];
                  assertTrue(this.canWrite(position), "out-of-region write: " + position);
                  assertEquals(260, args[2]);
                  this.blocks.put(position.immutable(), (BlockState)args[1]);
                  yield true;
               }
               case "ensureCanWrite" -> this.canWrite((BlockPos)args[0]);
               case "getMaxBuildHeight", "getMaxY" -> this.maxY;
               case "getMinBuildHeight", "getMinY" -> 0;
               case "getHeight" -> this.maxY;
               case "isInsideBuildHeight" -> this.insideHeight((BlockPos)args[0]);
               case "isOutsideBuildHeight" -> !this.insideHeight((BlockPos)args[0]);
               case "toString" -> "TreeLeafDecayTest.TestWorld";
               default -> throw new UnsupportedOperationException(method.toString());
            };
         }
      );

      private boolean insideHeight(BlockPos position) {
         return position.getY() >= 0 && position.getY() < this.maxY;
      }

      private boolean canWrite(BlockPos position) {
         return position.getX() >= -128 && position.getX() <= this.maxX
            && Math.abs(position.getZ()) <= 128 && this.insideHeight(position) && !this.denied.contains(position);
      }

      BlockState state(BlockPos position) {
         return this.blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
      }

      long leafCount() {
         return this.blocks.values().stream().filter(state -> state.getBlock() instanceof LeavesBlock).count();
      }
   }
}
