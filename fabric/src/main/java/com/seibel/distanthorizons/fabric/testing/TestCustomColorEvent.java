package com.seibel.distanthorizons.fabric.testing;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;

import java.awt.*;
import java.io.IOException;

/**
 * @see TestBlockWrapperCreatedEvent
 */
public class TestCustomColorEvent extends DhApiBlockColorOverrideEvent
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	
	@Override 
	public void onBlockColorOverridden(DhApiEventParam<EventParam> event)
	{
		EventParam eventParam = event.value;
		
		//randomDatapointColors(eventParam);
		//randomPerBlockColors(eventParam);
		//blackWhitePositionStripe(eventParam);
		useWaterTint(eventParam);
		//positionRainbow(eventParam);
	}
	
	/** each datapoint has a random color */
	private static void randomDatapointColors(EventParam eventParam)
	{
		// random colors for each datapoint
		int a = eventParam.getAlpha();
		int r = eventParam.getRed();
		int g = eventParam.getGreen();
		int b = eventParam.getBlue();

		if (eventParam.getBlockStateWrapper().getOpacity() == LodUtil.BLOCK_FULLY_OPAQUE)
		{
			eventParam.setColor(255,r,g,b);
		}
		else
		{
			eventParam.setColor(60,r,g,b);
		}
	}
	
	/** each block has a different color */
	private static void randomPerBlockColors(EventParam eventParam)
	{
		// random colors per block
		int r = Math.abs(eventParam.getBlockStateWrapper().hashCode() % 255);
		int g = Math.abs((eventParam.getBlockStateWrapper().hashCode() << 4) % 255);
		int b = Math.abs((eventParam.getBlockStateWrapper().hashCode() << 8) % 255);
		eventParam.setColor(r,g,b);
	}
	
	/** every block will be rendered using water's color */
	private static void useWaterTint(EventParam eventParam)
	{
		IDhApiBlockStateWrapper blockWrapper;
		try
		{
			String blockNamespace = "minecraft:water"; // everything is a shade of blue (except swamps)
			//String blockNamespace = "minecraft:oak_leaves"; // alternative example using oak leaves (everything is a shade of green)
			blockWrapper = DhApi.Delayed.wrapperFactory.getDefaultBlockStateWrapper(blockNamespace, eventParam.getLevelWrapper());
		}
		catch (IOException e)
		{
			blockWrapper = eventParam.getBlockStateWrapper();
		}
		
		DhApiResult<Color> result = eventParam.getLevelWrapper().getBlockColorPreApi(
			blockWrapper,
			eventParam.getBiomeWrapper(),
			eventParam.getBlockPosX(), eventParam.getBlockPosY(), eventParam.getBlockPosZ(),
			eventParam.getDataSource()
		);
		if (!result.success)
		{
			// shouldn't happen, but just in case
			return;
		}
		
		eventParam.setColor(result.payload.getRed(), result.payload.getGreen(), result.payload.getBlue());
	}
	
	private static void blackWhitePositionStripe(EventParam eventParam)
	{
		// black-white stripes
		int r = Math.abs(eventParam.getBlockPosX() % 255);
		int g = r;
		int b = r;
		eventParam.setColor(r,g,b);
	}
	
	/** rainbow along the X axis repeating every 255 blocks */
	private static void positionRainbow(EventParam eventParam)
	{
		float[] ahsv = ColorUtil.argbToAhsv(ColorUtil.RED);
		float a = ahsv[0];
		
		int xModPos = Math.abs(eventParam.getBlockPosX() % 510);
		float h = xModPos < 255 ? xModPos : 510 - xModPos;
		float s = ahsv[2];
		float v = ahsv[3];
		int colorInt = ColorUtil.ahsvToArgb(a,h,s,v);
		eventParam.setColor(ColorUtil.getRed(colorInt),ColorUtil.getGreen(colorInt),ColorUtil.getBlue(colorInt));
	}
	
	
	
}
