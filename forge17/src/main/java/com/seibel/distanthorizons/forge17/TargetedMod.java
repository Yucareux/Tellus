package com.seibel.distanthorizons.forge17;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.gtnhmixins.builders.ITargetMod;
import com.gtnewhorizon.gtnhmixins.builders.TargetModBuilder;

public enum TargetedMod implements ITargetMod 
{
    ANGELICA("loading.AngelicaTweaker", "angelica"),
    HODGEPODGE("com.mitchej123.hodgepodge.core.HodgepodgeCore", "hodgepodge");

	
	
    private final TargetModBuilder builder;

    TargetedMod(String coreModClass, String modId) 
    {
        this.builder = new TargetModBuilder().setCoreModClass(coreModClass)
            .setModId(modId);
    }

    @Override
    @NotNull
    public TargetModBuilder getBuilder() { return this.builder; }
	
	
	
}
