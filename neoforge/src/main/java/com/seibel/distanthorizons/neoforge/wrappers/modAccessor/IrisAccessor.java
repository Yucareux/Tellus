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

package com.seibel.distanthorizons.neoforge.wrappers.modAccessor;

// 1.20.6 is the lowest version Iris supports Neoforge
#if MC_VER >= MC_1_20_6

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;

public class IrisAccessor implements IIrisAccessor
{
	public IrisAccessor()
	{

	}
	
	
	
	@Override
	public String getModName() { return Iris.MODID;}
	
	@Override
	public boolean isShaderPackInUse() { return IrisApi.getInstance().isShaderPackInUse(); }
	
	@Override
	public boolean isRenderingShadowPass() { return IrisApi.getInstance().isRenderingShadowPass(); }
	
	@Override
	public boolean isReverseZDuringShaders()
	{
		#if MC_VER <= MC_1_21_11
		return false;
		#else
		// only supported on Iris for MC 26.1.2 and newer
		return IrisApi.getInstance().isReverseZDuringShaders();
		#endif
	}
	
	@Override
	public int getFramebufferDepthTextureId(Object framebuffer)
	{
		return -1;
	}
	
}

#endif

