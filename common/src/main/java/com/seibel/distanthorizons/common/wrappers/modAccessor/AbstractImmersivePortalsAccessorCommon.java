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

package com.seibel.distanthorizons.common.wrappers.modAccessor;

#if MC_VER <= MC_1_12_2
public abstract class AbstractImmersivePortalsAccessorCommon {}

#else
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

#if MC_VER > MC_1_19_2
#else
#endif

#if MC_VER < MC_1_17_1
import java.lang.reflect.Field;
#endif

public abstract class AbstractImmersivePortalsAccessorCommon extends AbstractImmersivePortalsAccessor
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public static final long MS_BEFORE_WARNING_LOG = 5_000L;
	
	private static volatile boolean mixinWarningLogged = false;
	public static volatile long lastUpdatedMsTime = -1L;
	
	
	
	// We don't use the fields in RenderStates because they are not volatile.
	@Nullable
	public static volatile DhBlockPos actualBlockPos;
	@Override
	@Nullable
	public DhBlockPos getActualPlayerBlockPos() { return actualBlockPos; }
	
	@Nullable
	public static volatile DhChunkPos actualChunkPos;
	@Override
	@Nullable
	public DhChunkPos getActualPlayerChunkPos() { return actualChunkPos; }
	
	@Nullable
	public static volatile ClientLevel actualLevel;
	@Override
	@Nullable
	public IClientLevelWrapper getActualClientLevelWrapper() { return ClientLevelWrapper.getWrapper(actualLevel, false); }
	
	@Nullable
	public static volatile DhVec3d actualCameraPos;
	@Override
	@Nullable
	public DhVec3d getActualCameraPos() { return actualCameraPos; }
	
	
	
	@Override
	public void logWarningIfMixinNotRunRecently()
	{
		// if the variables haven't been updated recently, assume the mixin hasn't been applied properly and complain
		long timeSinceLastUpdatedMs = System.currentTimeMillis() - lastUpdatedMsTime;
		if (timeSinceLastUpdatedMs < MS_BEFORE_WARNING_LOG)
		{
			return;
		}
		
		if (!mixinWarningLogged)
		{
			mixinWarningLogged = true;
			
			String message =
				MinecraftTextFormat.ORANGE + "Distant Horizons: Immersive Portals Mixin Fail." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
					"Distant Horizons' mixin into Immersive Portals\n" +
					"hasn't run, Disant Horizons rendering may\n" +
					"fail or look corrupted.\n";
			ClientApi.INSTANCE.queueSlowChatMessage(message);
			
			LOGGER.warn("Immersive Portals mixin run within the expected time period, rendering may fail or look corrupted.");
		}
	}
	
	
	
}

#endif
