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

import com.seibel.distanthorizons.api.enums.rendering.EDhApiDirection;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

import java.util.function.Consumer;

/**
 * This event is fired the first time DH needs to render a {@link IDhApiBlockStateWrapper}.
 * Once the texture has been resolved, that same texture will be reused for all subsequent instances
 * of that {@link IDhApiBlockStateWrapper}. <br>
 * (I.E. each block can only have one texture per face, you can't define
 * different textures based on the world position or dimension.)
 * <Br><Br>
 * 
 * This event will only trigger for {@link IDhApiBlockStateWrapper}s that have been registered
 * via {@link DhApiBlockStateWrapperCreatedEvent.EventParam#setAllowApiTextureOverride(boolean)} (boolean)}.
 * 
 * @author James Seibel
 * @version 2026-09-04
 * @since API 7.1.0
 * @see IDhApiBlockStateWrapper
 */
public abstract class DhApiBlockTextureOverrideEvent implements IDhApiEvent<DhApiBlockTextureOverrideEvent.EventParam>
{
	public abstract void onBlockTextureOverridden(DhApiEventParam<EventParam> event);
	
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiEventParam<EventParam> event) { this.onBlockTextureOverridden(event); }
	
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam implements IDhApiEventParam
	{
		private IDhApiBlockStateWrapper blockStateWrapper = null;
		private EDhApiDirection faceDirection = null;
		private ITextureRegenFunc textureRegenFunc = null;
		
		/**
		 * Pixel colors in ARGB order. <br>
		 * Indexed via {@link TextureUtil#getPixelIndex(int, int)} where (0,0) is the face's top left pixel.
		 */
		private final int[] argbPixels = new int[TextureUtil.TEXTURE_WIDTH_AND_HEIGHT * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT];
		
		
		
		//=============//
		// constructor //
		//=============//
		//region
		
		public EventParam() {}
		
		/** should only be called by DH internal code */
		public void update(
			IDhApiBlockStateWrapper blockStateWrapper,
			EDhApiDirection faceDirection,
			ITextureRegenFunc textureRegenFunc)
		{
			this.blockStateWrapper = blockStateWrapper;
			this.faceDirection = faceDirection;
			this.textureRegenFunc = textureRegenFunc;
		}
		
		//endregion
		
		
		
		//=================//
		// getters/setters //
		//=================//
		//region
		
		public IDhApiBlockStateWrapper getBlockStateWrapper() { return this.blockStateWrapper; }
		public EDhApiDirection getFaceDirection() { return this.faceDirection; }
		
		/** 
		 * Replaces the existing texture with the texture Distant Horizons would find
		 * from the given {@link IDhApiBlockStateWrapper}. <br><br>
		 * 
		 * This allows for easily replacing one block's texture with another.
		 */
		public void generateNewTextureFromBlock(IDhApiBlockStateWrapper blockStateWrapper, EDhApiDirection faceDirection) 
		{ this.textureRegenFunc.regenTextureFromBlock(this, blockStateWrapper, faceDirection); }
		
		/** in pixels */
		public int getWidth() { return TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; }
		/** in pixels */
		public int getHeight() { return TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; }
		
		/** (0,0) is the face's top left pixel */
		public int getAlpha(int u, int v) throws ArrayIndexOutOfBoundsException      { return ColorUtil.getAlpha(this.getColorAsInt(u,v)); }
		/** (0,0) is the face's top left pixel */
		public int getRed(int u, int v) throws ArrayIndexOutOfBoundsException        { return ColorUtil.getRed(this.getColorAsInt(u,v)); }
		/** (0,0) is the face's top left pixel */
		public int getGreen(int u, int v) throws ArrayIndexOutOfBoundsException      { return ColorUtil.getGreen(this.getColorAsInt(u,v)); }
		/** (0,0) is the face's top left pixel */
		public int getBlue(int u, int v) throws ArrayIndexOutOfBoundsException       { return ColorUtil.getBlue(this.getColorAsInt(u,v)); }
		/** (0,0) is the face's top left pixel */
		public int getColorAsInt(int u, int v) throws ArrayIndexOutOfBoundsException { return this.argbPixels[TextureUtil.getPixelIndex(u,v)]; }
		
		/** 
		 * (0,0) is the face's top left pixel. <br>
		 * color values should be between 0 and 255 (inclusive) 
		 */
		public void setColor(int u, int v, int red, int green, int blue) throws IllegalArgumentException, ArrayIndexOutOfBoundsException 
		{ this.setColor(u, v, this.getAlpha(u, v), red, green, blue); }
		/** 
		 * (0,0) is the face's top left pixel. <br>
		 * color values should be between 0 and 255 (inclusive) 
		 */
		public void setColor(int u, int v, int alpha, int red, int green, int blue) throws IllegalArgumentException, ArrayIndexOutOfBoundsException
		{
			ColorUtil.throwIfColorValueOutOfIntRange("alpha", alpha);
			ColorUtil.throwIfColorValueOutOfIntRange("red", red);
			ColorUtil.throwIfColorValueOutOfIntRange("green", green);
			ColorUtil.throwIfColorValueOutOfIntRange("blue", blue);
			
			this.argbPixels[TextureUtil.getPixelIndex(u,v)] = ColorUtil.argbToInt(alpha, red, green, blue);
		}
		
		//endregion
		
		
		
		//================//
		// helper methods //
		//================//
		//region
		
		/** should only be used by internal DH functions */
		@FunctionalInterface
		public interface ITextureRegenFunc
		{
			void regenTextureFromBlock(EventParam eventParam, IDhApiBlockStateWrapper newApiBlockState, EDhApiDirection newApiDirection);
		}
		
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