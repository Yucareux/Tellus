/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;

/**
 * Performance note: this event will be fired millions of times on concurrent threads, 
 * make it thread safe and as fast as possible. <br> 
 * (If every LOD block goes through this event, On a 512 render distance world, 
 * at the medium quality preset, it will be triggered around 40,000,000 times.)
 * <Br><Br>
 * 
 * This event is fired when DH needs to convert a {@link IDhApiBlockStateWrapper}
 * into a color for rendering. This event is fired after DH attempts to determine
 * the color itself (using the base color, tinting, etc.). <Br>
 * Using this event will override the tinting config for this block
 * unless you re-implement that logic yourself.
 * <Br><Br>
 * 
 * This event will only trigger for {@link IDhApiBlockStateWrapper}s that have been registered
 * via {@link DhApiBlockStateWrapperCreatedEvent.EventParam#setAllowApiColorOverride(boolean)}.
 * 
 * @author James Seibel
 * @version 2026-05-18
 * @since API 6.0.0
 * @see IDhApiBlockStateWrapper
 */
public abstract class DhApiBlockColorOverrideEvent implements IDhApiEvent<DhApiBlockColorOverrideEvent.EventParam>
{
	public abstract void onBlockColorOverridden(DhApiEventParam<EventParam> event);
	
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiEventParam<EventParam> event) { this.onBlockColorOverridden(event); }
	
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam implements IDhApiEventParam
	{
		private IDhApiLevelWrapper levelWrapper;
		private IDhApiFullDataSource dataSource;
		private IDhApiBlockStateWrapper blockStateWrapper = null;
		private IDhApiBiomeWrapper biomeWrapper = null;
		private int colorAsInt = -1;
		private int tintColorAsInt = -1;
		private int baseColorAsInt = -1;
		private int blockPosX = 0, blockPosY = 0, blockPosZ = 0;
		
		
		
		//=============//
		// constructor //
		//=============//
		//region
		
		public EventParam() {}
		
		public void update(
			IDhApiLevelWrapper levelWrapper,
			IDhApiFullDataSource dataSource,
			IDhApiBlockStateWrapper blockStateWrapper,
			IDhApiBiomeWrapper biomeWrapper,
			int colorAsInt,
			int tintColorAsInt,
			int baseColorAsInt,
			int blockPosX, int blockPosY, int blockPosZ)
		{
			this.levelWrapper = levelWrapper;
			this.dataSource = dataSource;
			this.blockStateWrapper = blockStateWrapper;
			this.biomeWrapper = biomeWrapper;
			
			this.colorAsInt = colorAsInt;
			this.tintColorAsInt = tintColorAsInt;
			this.baseColorAsInt = baseColorAsInt;
			
			this.blockPosX = blockPosX;
			this.blockPosY = blockPosY;
			this.blockPosZ = blockPosZ;
		}
		
		//endregion
		
		
		
		//=================//
		// getters/setters //
		//=================//
		//region
		
		public IDhApiBlockStateWrapper getBlockStateWrapper() { return this.blockStateWrapper; }
		/** @since API 7.0.0 */
		public IDhApiBiomeWrapper getBiomeWrapper() { return this.biomeWrapper; }
		
		/** the level DH is resolving this block's color in. */
		public IDhApiLevelWrapper getLevelWrapper() { return this.levelWrapper; }
		/** 
		 * The DH datasource that contains this block's position. Can be used to access adjacent
		 * {@link IDhApiBlockStateWrapper}'s and {@link IDhApiBiomeWrapper}'s for adjacent aware tinting.
		 * @since API 7.0.0
		 */
		public IDhApiFullDataSource getDataSource() { return this.dataSource; }
		
		public int getColorAsInt() { return this.colorAsInt; }
		public int getAlpha() { return ColorUtil.getAlpha(this.colorAsInt); }
		public int getRed() { return ColorUtil.getRed(this.colorAsInt); }
		public int getGreen() { return ColorUtil.getGreen(this.colorAsInt); }
		public int getBlue() { return ColorUtil.getBlue(this.colorAsInt); }
		public void setColor(int red, int green, int blue) throws IllegalArgumentException { this.setColor(this.getAlpha(), red, green, blue); }
		/** 
		 * Note: when if you set a partially transparent alpha channel the underlying {@link IDhApiBlockStateWrapper#getOpacity()}
		 * method should also return a non-opaque value.
		 * Otherwise LODs may behave incorrectly.
		 */
		public void setColor(int alpha, int red, int green, int blue) throws IllegalArgumentException
		{
			ColorUtil.throwIfColorValueOutOfIntRange("alpha", alpha);
			ColorUtil.throwIfColorValueOutOfIntRange("red", red);
			ColorUtil.throwIfColorValueOutOfIntRange("green", green);
			ColorUtil.throwIfColorValueOutOfIntRange("blue", blue);
			
			this.colorAsInt = ColorUtil.argbToInt(alpha, red, green, blue);
		}
		
		/** @since API 7.1.0 */
		public int getTintColorAsInt() { return this.tintColorAsInt; }
		
		/** @since API 7.1.0 */
		public int getBaseColorAsInt() { return this.baseColorAsInt; }
		
		/** @return the block's X value in the world */
		public int getBlockPosX() { return blockPosX; }
		/** @return the block's Y value in the world */
		public int getBlockPosY() { return blockPosY; }
		/** @return the block's Z value in the world */
		public int getBlockPosZ() { return blockPosZ; }
		
		//endregion
		
		
		
		//==========================//
		// base api event overrides //
		//==========================//
		//region
		
		/** 
		 * Returns the same instance of this event.
		 * Copying this event isn't supported
		 * since the internal parameters must be mutated
		 * by API users in order to be tracked by DH's internal
		 * logic.
		 */
		@Override
		public EventParam copy() { return this; }
		
		@Override 
		public boolean getCopyBeforeFire() { return false; }
		
		//endregion
		
		
		
	}
	
}