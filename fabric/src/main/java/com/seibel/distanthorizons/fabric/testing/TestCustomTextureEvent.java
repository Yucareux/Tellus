package com.seibel.distanthorizons.fabric.testing;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockTextureOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;

import java.io.IOException;
import java.util.Random;

/**
 * @see TestBlockWrapperCreatedEvent
 */
public class TestCustomTextureEvent extends DhApiBlockTextureOverrideEvent
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	@Override 
	public void onBlockTextureOverridden(DhApiEventParam<EventParam> event)
	{
		EventParam eventParam = event.value;
		//solidRed(eventParam);
		//randomNoiseColor(eventParam);
		//positionRainbow(eventParam);
		everyBlockIsOakLog(eventParam);
	}
	
	private static void solidRed(EventParam eventParam)
	{
		for (int u = 0; u < eventParam.getWidth(); u++)
		{
			for (int v = 0; v < eventParam.getHeight(); v++)
			{
				eventParam.setColor(
					u, v,
					255, 255, 0, 0);
			}
		}
	}
	
	private static void randomNoiseColor(EventParam eventParam)
	{
		Random random = new Random();
		
		for (int u = 0; u < eventParam.getWidth(); u++)
		{
			for (int v = 0; v < eventParam.getHeight(); v++)
			{
				int r = random.nextInt() % 256;
				int g = random.nextInt() % 256;
				int b = random.nextInt() % 256;
				
				eventParam.setColor(
					u, v,
					255, r, g, b);
			}
		}
	}
	
	/** rainbow along the X axis repeating once per block */
	private static void positionRainbow(EventParam eventParam)
	{
		float[] ahsv = ColorUtil.argbToAhsv(ColorUtil.RED);
		
		for (int u = 0; u < eventParam.getWidth(); u++)
		{
			for (int v = 0; v < eventParam.getHeight(); v++)
			{
				
				int xModPos = Math.abs(u % 16);
				float hue = xModPos < 8 ? xModPos : 16 - xModPos;
				float sat = ahsv[2];
				float value = ahsv[3];
				int colorInt = ColorUtil.ahsvToArgb(255, hue, sat, value);
				eventParam.setColor(u, v, 
					255, ColorUtil.getRed(colorInt), ColorUtil.getGreen(colorInt), ColorUtil.getBlue(colorInt));
			}
		}
	}
	
	private static void everyBlockIsOakLog(EventParam eventParam)
	{
		try
		{
			String blockNamespace = "minecraft:oak_log";
			IDhApiBlockStateWrapper blockWrapper = DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(blockNamespace, DhApi.Delayed.worldProxy.getSinglePlayerLevel());
			eventParam.generateNewTextureFromBlock(blockWrapper, eventParam.getFaceDirection());
		}
		catch (IOException e)
		{
			LOGGER.error("Failed to deserialize Block, error: ["+e.getMessage()+"].", e);
		}
	}
	
	
	
	
}
