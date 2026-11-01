package com.seibel.distanthorizons.fabric.testing;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;

import java.util.Random;

/**
 * @see TestCustomColorEvent
 * @see TestCustomTextureEvent
 */
public class TestBlockWrapperCreatedEvent extends DhApiBlockStateWrapperCreatedEvent
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	
	@Override 
	public void blockStateWrapperCreated(DhApiEventParam<EventParam> event)
	{
		EventParam eventParam = event.value;
		
		// can be enabled to flip the opacity of transparent/opaque blocks
		if (false)
		{
			if (eventParam.getBlockStateWrapper().getOpacity() == LodUtil.BLOCK_FULLY_OPAQUE)
			{
				eventParam.setOpacity(LodUtil.BLOCK_FULLY_TRANSPARENT);
			}
			else
			{
				eventParam.setOpacity(LodUtil.BLOCK_FULLY_OPAQUE);
			}
		}
		
		// needed for TestCustomColorEvent
		eventParam.setAllowApiColorOverride(true);
		// needed for TestCustomTextureEvent
		eventParam.setAllowApiTextureOverride(true);
	}
	
}
