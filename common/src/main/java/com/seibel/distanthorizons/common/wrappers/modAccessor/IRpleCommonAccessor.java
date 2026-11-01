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
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IRpleAccessor;
#if MC_VER == MC_1_7_10
import net.minecraft.block.Block;
#endif

/**
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting to MC 1.7.10 <br><br>
 * 
 * The common Accessor allows for MC
 * version specific methods, vs the base accessor
 * which is for MC agnostic methods.
 */
public interface IRpleCommonAccessor extends IRpleAccessor
{
	#if MC_VER == MC_1_7_10
	int getColor(Block block, int meta);
	#endif
}
