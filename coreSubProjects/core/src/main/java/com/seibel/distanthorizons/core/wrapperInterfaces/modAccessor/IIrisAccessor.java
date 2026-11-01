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

public interface IIrisAccessor extends IModAccessor
{
	String FRAMEBUFFER_MIXIN_CLASS = "net.irisshaders.iris.gl.framebuffer.GlFramebuffer";
	String READABLE_NAME = "Iris";
	
	
	boolean isShaderPackInUse();
	
	boolean isRenderingShadowPass();
	
	boolean isReverseZDuringShaders();
	
	/**
	 * Returns the depth texture ID Iris created for the given Minecraft {@code Framebuffer}
	 * via its mixin injection.
	 * Returns -1 if the framebuffer wasn't patched by Iris, or for versions above 1.12.2.
	 * Useful only on versions where Minecraft uses a renderbuffer for depth instead of a texture.
	 */
	int getFramebufferDepthTextureId(Object framebuffer);
	
}
