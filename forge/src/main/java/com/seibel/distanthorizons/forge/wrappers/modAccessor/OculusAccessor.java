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

package com.seibel.distanthorizons.forge.wrappers.modAccessor;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

// Oculus (Iris) 1.8.0 (the oldest that supports DH) is only available for MC 1.20.1
#if MC_VER == MC_1_20_1
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
#else
#endif

public class OculusAccessor implements IIrisAccessor
{
	protected static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	public OculusAccessor()
	{ LOGGER.warn("Partial Oculus support enabled. Some DH features may be disabled or behave strangely, use Iris instead if possible."); }
	
	
	
	@Override
	public String getModName()
	{
		// Oculus (Iris) 1.8.0 (the oldest that supports DH) is only available for MC 1.20.1
		#if MC_VER == MC_1_20_1
		return Iris.MODID;
		#else
		return "iris";
		#endif
	}
	
	@Override
	public boolean isShaderPackInUse()
	{
		// Oculus (Iris) 1.8.0 (the oldest that supports DH) is only available for MC 1.20.1
		#if MC_VER == MC_1_20_1
		return IrisApi.getInstance().isShaderPackInUse();
		#else
		return true;
		#endif
	}
	
	@Override
	public boolean isRenderingShadowPass()
	{
		// Oculus (Iris) 1.8.0 (the oldest that supports DH) is only available for MC 1.20.1
		#if MC_VER == MC_1_20_1
		return IrisApi.getInstance().isRenderingShadowPass();
		#else
		return false;
		#endif
	}
	
	@Override
	public boolean isReverseZDuringShaders()
	{
		// Reverse Z isn't supported on Iris' end
		// for these older versions of MC 
		return false;
	}
	
	@Override
	public int getFramebufferDepthTextureId(Object framebuffer)
	{
		return -1;
	}
	
}
