package com.yucareux.tellus.worldgen.tree;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Places tree foliage only after its final wood and leaf support are known. */
final class TreePlacement {
   private static final int PLACEMENT_FLAGS = 260;
   private static final int DECAY_DISTANCE = 7;
   private final WorldGenLevel level;
   // Keep full positions: Tellus worlds can exceed vanilla's packed Y range.
   private final Map<BlockPos, BlockState> leaves = new LinkedHashMap<>();
   private final Set<BlockPos> logs = new LinkedHashSet<>();

   TreePlacement(WorldGenLevel level) {
      this.level = level;
   }

   boolean setLog(BlockPos position, BlockState log) {
      if (!this.canWrite(position)) {
         return false;
      }
      BlockState current = this.level.getBlockState(position);
      if (!(current.isAir() || current.is(BlockTags.LEAVES) || current.is(BlockTags.REPLACEABLE_BY_TREES))
         || !this.level.setBlock(position, log, PLACEMENT_FLAGS)) {
         return false;
      }
      this.logs.add(position.immutable());
      this.leaves.remove(position);
      return true;
   }

   void setLeaves(BlockPos position, BlockState state) {
      if (!this.canWrite(position)) {
         return;
      }
      BlockState current = this.level.getBlockState(position);
      // Preserve existing foliage, including player-placed persistent leaves.
      if (!current.is(BlockTags.LEAVES) && (current.isAir() || current.is(BlockTags.REPLACEABLE_BY_TREES))) {
         this.leaves.put(
            position.immutable(), state.setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, DECAY_DISTANCE)
         );
      }
   }

   void finish() {
      List<Set<BlockPos>> frontier = new ArrayList<>(DECAY_DISTANCE);
      for (int distance = 0; distance < DECAY_DISTANCE; distance++) {
         frontier.add(new LinkedHashSet<>());
      }

      // Existing trees can support new foliage too. Read their real distances
      // using Minecraft's version-specific support rules (including block tags).
      for (BlockPos position : this.leaves.keySet()) {
         int distance = DECAY_DISTANCE;
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = position.relative(direction);
            if (this.canWrite(neighbor) && !this.leaves.containsKey(neighbor)) {
               distance = Math.min(distance, LeavesBlock.getOptionalDistanceAt(this.level.getBlockState(neighbor))
                  .orElse(DECAY_DISTANCE) + 1);
            }
         }
         this.updateDistance(position, distance, frontier);
      }

      // As in vanilla TreeFeature, newly placed wood also updates nearby
      // existing leaves. Never change those leaves' persistence or material.
      for (BlockPos position : this.logs) {
         for (Direction direction : Direction.values()) {
            this.updateDistance(position.relative(direction), 1, frontier);
         }
      }
      for (int distance = 1; distance < DECAY_DISTANCE - 1; distance++) {
         for (BlockPos position : frontier.get(distance)) {
            for (Direction direction : Direction.values()) {
               this.updateDistance(position.relative(direction), distance + 1, frontier);
            }
         }
      }

      for (Map.Entry<BlockPos, BlockState> entry : this.leaves.entrySet()) {
         // Noisy crowns can contain isolated leaves or tips beyond the vanilla
         // six-leaf support range. Omit them instead of generating foliage that
         // immediately decays when the chunk starts ticking.
         if (entry.getValue().getValue(LeavesBlock.DISTANCE) < DECAY_DISTANCE) {
            this.level.setBlock(entry.getKey(), entry.getValue(), PLACEMENT_FLAGS);
         }
      }
   }

   private void updateDistance(BlockPos position, int distance, List<Set<BlockPos>> frontier) {
      if (distance >= DECAY_DISTANCE || !this.canWrite(position)) {
         return;
      }
      BlockState pending = this.leaves.get(position);
      BlockState state = pending != null ? pending : this.level.getBlockState(position);
      if (!state.hasProperty(LeavesBlock.DISTANCE) || state.getValue(LeavesBlock.DISTANCE) <= distance) {
         return;
      }
      BlockState updated = state.setValue(LeavesBlock.DISTANCE, distance);
      if (pending != null) {
         this.leaves.put(position, updated);
      } else if (!this.level.setBlock(position, updated, PLACEMENT_FLAGS)) {
         return;
      }
      frontier.get(distance).add(position.immutable());
   }

   private boolean canWrite(BlockPos position) {
      return MinecraftVersionCompat.isInsideBuildHeight(this.level, position) && this.level.ensureCanWrite(position);
   }
}
