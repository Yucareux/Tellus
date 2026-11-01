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

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IGregTechAccessor;
import org.jetbrains.annotations.Nullable;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.IBlockState;
import net.minecraft.util.IIcon;
#endif

/**
 * The common Accessor allows for MC
 * version specific methods, vs the base accessor
 * which is for MC agnostic methods.
 */
public interface IGregTechCommonAccessor extends IGregTechAccessor
{
	
	#if MC_VER <= MC_1_7_10
	@Nullable
	IIcon resolveIcon(IBlockState blockState);
	#endif
	
}
