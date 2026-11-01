package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;

public class WorldGenPlanConfigEventHandler implements IConfigListener
{
	public static WorldGenPlanConfigEventHandler INSTANCE = new WorldGenPlanConfigEventHandler();
	public static IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	
	/** private since we only ever need one handler at a time */
	private WorldGenPlanConfigEventHandler() { }
	
	
	
	@Override
	public void onConfigValueSet()
	{
		boolean supportsSurfaceGen = MC_SHARED.supportsSurfaceGeneration();
		if (supportsSurfaceGen)
		{
			// all options are available, we don't need to do anything;
			return;
		}
		
		EDhApiGeneratorPlan genPlan = Config.Common.WorldGenerator.generatorPlan.get();
		if (genPlan.surfaceGenEnabled)
		{
			// this generator isn't supported on this MC version,
			Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.CHUNKS_ONLY);
		}
	}
	
	@Override
	public void onUiModify() { /* do nothing, we only care about modified config values */ }
	
	public static Boolean setShowEnumOptionFunc(Enum<?> enumValue)
	{
		boolean supportsSurfaceGen = MC_SHARED.supportsSurfaceGeneration();
		if (supportsSurfaceGen)
		{
			// all options are available, we don't need to check anything;
			return true;
		}
		
		
		EDhApiGeneratorPlan genPlan = (EDhApiGeneratorPlan)enumValue;
		return !genPlan.surfaceGenEnabled;
	}
	
}
