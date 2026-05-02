package com.yucareux.tellus.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;

import java.util.Optional;

@Mixin(SerializableChunkData.class)
public abstract class ReduceStorageSizeMixin {

    @Inject(
        method = "write",
        at = @At("RETURN")
    )
    private void reduceStorageSize(CallbackInfoReturnable<CompoundTag> cir) {
        
        if (!EarthChunkGenerator.optimizeStorage) return;

        CompoundTag tag = cir.getReturnValue();
        if (tag == null) return;

        Optional<ListTag> sectionsOpt = tag.getList("sections");
        if (sectionsOpt.isEmpty()) return;
        ListTag sections = sectionsOpt.get();

        for (int i = 0; i < sections.size(); i++) {
            Optional<CompoundTag> sectionOpt = sections.getCompound(i);
            if (sectionOpt.isEmpty()) continue;
            CompoundTag section = sectionOpt.get();

            Optional<CompoundTag> blockStatesOpt = section.getCompound("block_states");
            if (blockStatesOpt.isEmpty()) continue;

            Optional<ListTag> paletteOpt = blockStatesOpt.get().getList("palette");
            if (paletteOpt.isEmpty() || paletteOpt.get().size() != 1) continue;

            section.remove("BlockLight");
            section.remove("SkyLight");
            section.remove("biomes");
        }

        Optional<ListTag> blockTicksOpt = tag.getList("block_ticks");
        if (blockTicksOpt.isPresent()) {
            ListTag blockTicks = blockTicksOpt.get();
            for (int i = blockTicks.size() - 1; i >= 0; i--) {
                Optional<CompoundTag> tickOpt = blockTicks.getCompound(i);
                if (tickOpt.isEmpty()) continue;
                Optional<String> id = tickOpt.get().getString("i");
                if (id.isPresent() && id.get().endsWith("_leaves")) {
                    blockTicks.remove(i);
                }
            }
        }

        Optional<ListTag> ppOpt = tag.getList("PostProcessing");
        if (ppOpt.isPresent()) {
            ListTag pp = ppOpt.get();
            for (int i = pp.size() - 1; i >= 0; i--) {
                Optional<ListTag> subList = pp.getList(i);
                if (subList.isPresent() && subList.get().isEmpty()) {
                    pp.remove(i);
                }
            }
        }
    }
}