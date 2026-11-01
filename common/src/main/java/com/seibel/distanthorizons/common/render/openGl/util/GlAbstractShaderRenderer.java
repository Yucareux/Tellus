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

package com.seibel.distanthorizons.common.render.openGl.util;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;

public abstract class GlAbstractShaderRenderer
{
	protected static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	protected static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	protected GlShaderProgram shader;
	protected boolean init = false;
	
	
	//=======//
	// setup //
	//=======//
	//region
	
	protected GlAbstractShaderRenderer() {}
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.onInit();
	}
	
	//endregion
	
	
	//==================//
	// abstract methods //
	//==================//
	//region
	
	protected void onInit() {}
	
	protected void onApplyUniforms(RenderParams renderParams) {}
	
	protected void onRender() {}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void render(RenderParams renderParams)
	{
		this.init();
		
		this.shader.bind();
		
		this.onApplyUniforms(renderParams);
		
		int width = MC_RENDER.getTargetFramebufferViewportWidth();
		int height = MC_RENDER.getTargetFramebufferViewportHeight();
		GLMC.glViewport(0, 0, width, height);
		
		this.onRender();
		
		this.shader.unbind();
	}
	
	//endregion
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	public void free()
	{
		if (this.shader != null)
		{
			this.shader.free();
		}
	}
	
	// endregion
	
	
	
}
