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

package com.seibel.distanthorizons.core.wrapperInterfaces.minecraft;

import java.awt.Color;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.ILightMapWrapper;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Contains everything related to
 * rendering in Minecraft.
 *
 * @author James Seibel
 * @version 3-5-2022
 */
public interface IMinecraftRenderWrapper extends IBindable
{
	DhVec3f getLookAtVector();
	
	boolean playerHasBlindingEffect();
	
	/**
	 * percentage of time into the current client tick. <br><br>
	 *
	 * Can be converted to a millisecond frametime 
	 * (IE time between frames in milliseconds) using the formula: <br>
	 * <code>
	 * (partialTickTime/20*1000)
	 * </code> <br>
	 * IE 60 FPS = 16.6 MS <br>
	 *
	 * @link https://fpstoms.com/
	 * @see DhRenderState#partialTickTime
	 */
	float getPartialTickTime();
	
	/** 
	 * When other mods are present and
	 * this method is called outside the render thread
	 * this may return undesirable positions.
	 * (Normally this position is used to get
	 * the position the player's camera).
	 */
	DhVec3d getCameraExactPosition();
	
	Color getFogColor(float partialTicks);
	
	/** Unless you really need to know if the player is blind, use {@link IMinecraftRenderWrapper#isFogStateSpecial()} instead */
	boolean isFogStateSpecial();
	
	Color getSkyColor();
	
	/** Measured in chunks */
	int getRenderDistance();
	
	/**
	 * The player's FOV, measured in degrees. <br>
	 * Doesn't include FOV effects or zoom modifiers.
	 */
	double getFovSetting();
	
	int getFrameLimit();
	
	boolean mcRendersToFrameBuffer();
	boolean runningLegacyOpenGL();
	/** Returns the Graphics API Minecraft is currently using for rendering */
	EDhApiRenderingApi getMcRenderingApi();
	EDhApiDepthDirection getMcDepthDirection();
	
	/** @return -1 if no valid framebuffer is available yet */
	int getTargetFramebuffer(); // Note: Iris is now hooking onto this for DH + Iris compat, try not to change (unless we wanna deal with some annoyances)
								//          Iris commit: https://github.com/IrisShaders/Iris/commit/a76a240527e93780bbcba57c09bef377419d47a7#diff-7b9ded0c79bbcdb130010373387756a28ee8d3640d522c0a5b7acd0abbfc20aeR16
	/** @return -1 if there was an issue or no texture exists */
	int getGlDepthTextureId();
	/** @return -1 if there was an issue or no texture exists */
	int getGlColorTextureId();
	int getTargetFramebufferViewportWidth();
	int getTargetFramebufferViewportHeight();
	
	/** 
	 * generally shouldn't be needed, the frame buffer should generally stay the same 
	 * but in case something goes wrong this allows for re-getting the buffer ID.
	 */
	void clearTargetFrameBuffer();
	
	/** Can return null if the given level hasn't had a light map assigned to it */
	@Nullable
	ILightMapWrapper getLightmapWrapper(@NotNull ILevelWrapper level);
	
	
	
}
