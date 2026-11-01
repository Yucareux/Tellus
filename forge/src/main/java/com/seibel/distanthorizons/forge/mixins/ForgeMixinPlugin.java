package com.seibel.distanthorizons.forge.mixins;

import com.seibel.distanthorizons.common.commonMixins.AbstractDhMixinPlugin;
import com.seibel.distanthorizons.forge.wrappers.modAccessor.ModChecker;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.minecraftforge.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * @author coolGi
 * @author cortex
 */
public class ForgeMixinPlugin extends AbstractDhMixinPlugin implements IMixinConfigPlugin
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName)
	{
		if (this.isNeoforge())
		{
			LOGGER.debug("Skipping DH mixin ["+mixinClassName+"] -> ["+targetClassName+"] due to forge not being loaded.");
			return false;
		}
		
		return this.shouldApplyDhMixin(targetClassName, mixinClassName);
	}
	
	
	@Override
	public void onLoad(String mixinPackage) { }
	
	@Override
	public String getRefMapperConfig() { return null; }
	
	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	
	@Override
	public List<String> getMixins() { return null; }
	
	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	
	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	
}