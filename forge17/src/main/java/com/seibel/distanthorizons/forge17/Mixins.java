package com.seibel.distanthorizons.forge17;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.gtnewhorizon.gtnhmixins.builders.MixinBuilder;

/**
 * Handles setting up mixins
 * similar to `... mixins.json` in newer MC versions.
 */
public enum Mixins implements IMixins 
{

    CORE(
		new MixinBuilder().setPhase(Phase.EARLY)
        .addCommonMixins("MixinBiomeGenBase")),
	
    THREADED_FILE_IO_NO_SLEEP(
		new MixinBuilder().setPhase(Phase.EARLY)
        .addExcludedMod(TargetedMod.HODGEPODGE)
        .addCommonMixins("MixinThreadedFileIOBase")),
	
    CLIENT_CORE(
		new MixinBuilder().setPhase(Phase.EARLY)
        .addClientMixins(
            "MixinActiveRenderInfo",
            "MixinChunk",
            "MixinEntityRenderer",
            "MixinNetHandlerPlayClient",
            "MixinOptionsScreen",
            "MixinRenderGlobal",
            "MixinTesselator",
            "MixinTextureAtlasSprite",
            "MixinTextureMap")),
	
    FIX_SIDE_FACING_UNLOADED_CHUNKS_BEING_RENDERED(
		new MixinBuilder().addExcludedMod(TargetedMod.ANGELICA)
        .addClientMixins("MixinBlock_SideFacingUnloadedChunk", "IMixinChunkCache_SideFacingUnloaded")
        .setPhase(Phase.EARLY)),
	
    CLIENT_FADE(
		new MixinBuilder().setPhase(Phase.EARLY)
        .addExcludedMod(TargetedMod.ANGELICA)
        .addClientMixins("MixinFramebuffer"));
	
	
	
    private final MixinBuilder builder;
	
    Mixins(MixinBuilder builder) { this.builder = builder; }
	
    @Override
    @NotNull
    public MixinBuilder getBuilder() { return this.builder; }
	
	
	
}
