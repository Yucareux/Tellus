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

package com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.jetbrains.annotations.Nullable;

public interface IImmersivePortalsAccessor extends IModAccessor
{
	/** 
	 * immersive portals has a bunch of different potential mod IDs
	 * so we need to check for all of them.
	 */
	String[] MOD_ID_ARRAY =
		new String[]
		{
			"imm_ptl_core",
			"immersive_portals",
			"immersive_portals_core",	
		};
	
	String INJECTION_CLASS_1_16 = "com.qouteall.immersive_portals.render.context_management.RenderStates";
	String INJECTION_CLASS = "qouteall.imm_ptl.core.render.context_management.RenderStates";
	
	/** 
	 * Returns true if Immersive Portals is currently rendering a portal.
	 * This can be used to determine if the level currently being rendered
	 * is being seen through a portal if called on the render thread.
	 */
	boolean isRenderingPortal();
	
	
	
	/** 
	 * Returns the player's position for the level they're currently in.
	 * <br><br>
	 * Necessary since Immersive Portals messes with vanilla MC's
	 * variables in order to render the camera in multiple dimensions.
	 */
	@Nullable
	DhBlockPos getActualPlayerBlockPos();
	
	/**
	 * Returns the player's position for the level they're currently in.
	 * <br><br>
	 * Necessary since Immersive Portals messes with vanilla MC's
	 * variables in order to render the camera in multiple dimensions.
	 */
	@Nullable
	DhChunkPos getActualPlayerChunkPos();
	
	/**
	 * Returns the client level the player is currently in.
	 * <br><br>
	 * Necessary since Immersive Portals messes with vanilla MC's
	 * variables in order to render the camera in multiple dimensions.
	 */
	@Nullable
	IClientLevelWrapper getActualClientLevelWrapper();
	
	/**
	 * Returns the camera position for the level the player is currently in.
	 * <br><br>
	 * Necessary since Immersive Portals messes with vanilla MC's
	 * variables in order to render the camera in multiple dimensions.
	 */
	@Nullable
	DhVec3d getActualCameraPos();
	
	void logWarningIfMixinNotRunRecently();
	
	
	
}
