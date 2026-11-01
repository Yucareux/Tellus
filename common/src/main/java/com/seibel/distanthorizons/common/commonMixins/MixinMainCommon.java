package com.seibel.distanthorizons.common.commonMixins;


import com.seibel.distanthorizons.core.jar.EPlatform;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.ModInfo;

public class MixinMainCommon
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static void onMainStart()
	{
		if (!ModInfo.IS_DEV_BUILD)
		{
			// we only want renderdoc when running debug builds
			return;
		}
		
		if (EPlatform.get() != EPlatform.WINDOWS)
		{
			LOGGER.debug("Distant Horizons is unable to inject Render Doc on OS ["+EPlatform.get().name()+"]");
			return;
		}
		
		
		
		String renderDocDllPath = "C:/Program Files/RenderDoc/renderdoc.dll"; 
		try
		{
			System.load(renderDocDllPath);
			LOGGER.info("Distant Horizons has successfully injected Render Doc for debugging.");
		}
		catch (Throwable e) // UnsatisfiedLinkError is likely what will be thrown if the DLL is missing
		{
			LOGGER.error("Unable to inject RenderDoc from ["+renderDocDllPath+"]", e);
		}
	}
	
	
	
}
