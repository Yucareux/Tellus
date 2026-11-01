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

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;

/**
 * Can be used to modify {@link IDhApiBlockStateWrapper}'s as they're created.
 * This can be helpful for modded blocks that are mis-categorized by DH's base logic. <br><br>
 *
 * Note: this is only fired once per {@link IDhApiBlockStateWrapper} that is created
 * and those {@link IDhApiBlockStateWrapper} will only be created once per JVM session.
 * 
 * @author James Seibel
 * @version 2026-04-14
 * @since API 6.0.0
 * @see IDhApiBlockStateWrapper
 */
public abstract class   DhApiBlockStateWrapperCreatedEvent implements IDhApiEvent<DhApiBlockStateWrapperCreatedEvent.EventParam>
{
	public abstract void blockStateWrapperCreated(DhApiEventParam<EventParam> event);
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiEventParam<EventParam> event) { this.blockStateWrapperCreated(event); }
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam implements IDhApiEventParam
	{
		/** 
		 * A copy of the wrapper that will be created. <Br>
		 * Note: modifying this object won't change anything
		 * a new wrapper will be created after this event finishes.
		 */
		private final IDhApiBlockStateWrapper blockStateWrapper;
		
		private boolean overridesSet = false;
		private EDhApiBlockMaterial blockMaterial = null;
		private Integer opacity = null;
		private Boolean allowApiColorOverride = null;
		private Boolean allowApiTextureOverride = null;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		public EventParam(IDhApiBlockStateWrapper blockStateWrapper) { this.blockStateWrapper = blockStateWrapper; }
		
		
		
		//=================//
		// getters/setters //
		//=================//
		
		public IDhApiBlockStateWrapper getBlockStateWrapper() { return this.blockStateWrapper; }
		
		/** if set this will override the value currently set in the given {@link IDhApiBlockStateWrapper} */
		public void setBlockMaterial(EDhApiBlockMaterial blockMaterial) 
		{
			this.blockMaterial = blockMaterial; 
			this.overridesSet = true;
		}
		public EDhApiBlockMaterial getBlockMaterial() { return this.blockMaterial; }
		
		/** if set this will override the value currently set in the given {@link IDhApiBlockStateWrapper} */
		public void setOpacity(int opacity) 
		{
			this.opacity = opacity;
			this.overridesSet = true;
		}
		public Integer getOpacity() { return this.opacity; }
		
		/** if set to true this {@link IDhApiBlockStateWrapper} will trigger {@link DhApiBlockColorOverrideEvent} */
		public void setAllowApiColorOverride(boolean allowApiColorOverride) 
		{
			this.allowApiColorOverride = allowApiColorOverride;
			this.overridesSet = true;
		}
		public Boolean getAllowApiColorOverride() { return this.allowApiColorOverride; }
		
		/** if set to true, {@link DhApiBlockTextureOverrideEvent} events will be triggered whenever a block is first seen. */
		public void setAllowApiTextureOverride(boolean allowApiColorOverride) 
		{
			this.allowApiTextureOverride = allowApiColorOverride;
			this.overridesSet = true;
		}
		public Boolean getAllowApiTextureOverride() { return this.allowApiTextureOverride; }
		
		/** If true then one or more options for this block were set to be changed */
		public boolean getOverridesSet() { return this.overridesSet; }
		
		
		
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
		
	}
	
}