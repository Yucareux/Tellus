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
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IHodgePodgeAccessor;
#if MC_VER == MC_1_7_10
import net.minecraft.world.World;
#endif

public interface IHodgePodgeCommonAccessor extends IHodgePodgeAccessor
{
	#if MC_VER == MC_1_7_10
	void preventChunkSimulation(World world, int x, int z);
	void allowChunkSimulation(World world, int x, int z);
	#endif
}
