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

package com.seibel.distanthorizons.common.wrappers.block;

import com.seibel.distanthorizons.common.backports.IBlockState;
import com.seibel.distanthorizons.common.wrappers.interfaces.IMixinTextureAtlasSprite;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IGregTechCommonAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

#if MC_VER <= MC_1_7_10
import net.minecraft.block.Block;
import net.minecraft.client.renderer.IconFlipped;
import net.minecraft.util.IIcon;
import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
#endif

#if MC_VER < MC_1_17_1
#elif MC_VER < MC_1_21_3
#else
import net.minecraft.client.renderer.texture.SpriteContents;
#endif

/**
 * For wrapping/utilizing around TextureAtlasSprite
 *
 * @author Ran
 */
public class TextureAtlasSpriteWrapper
{
	private static final IGregTechCommonAccessor GREG_TECH_ACCESSOR = ModAccessorInjector.INSTANCE.get(IGregTechCommonAccessor.class);

	#if MC_VER <= MC_1_7_10
	/**
	 * 1.7.10 stores anisotropic sprites with an eight-pixel wrapping border on
	 * every edge. The border is atlas padding, not part of the block texture.
	 */
	private static final int ANISOTROPIC_SPRITE_BORDER = 8;
	#endif
	
	
	
	public static int getPixelARGB(TextureAtlasSprite sprite, int frameIndex, int x, int y)
	{
		#if MC_VER <= MC_1_7_10
		// In 1.7.10 the sprite's pixel array isn't publicly accessible, so we rely on a Mixin
		// (see forge17/.../MixinTextureAtlasSprite) which caches the base mipmap level.
		IMixinTextureAtlasSprite spriteExt = (IMixinTextureAtlasSprite) sprite;
		int[] spriteData = spriteExt.distanthorizons$getSpriteData();
		if (spriteData == null)
		{
			// missing texture sentinel (matches the pink "missing" color used elsewhere)
			return ColorUtil.HOT_PINK;
		}
		if (sprite.useAnisotropicFiltering)
		{
			x += ANISOTROPIC_SPRITE_BORDER;
			y += ANISOTROPIC_SPRITE_BORDER;
		}

		return spriteData[sprite.getIconWidth() * y + x];
		#elif MC_VER <= MC_1_12_2
		int[][] frameData = sprite.getFrameTextureData(frameIndex);
		int argb = frameData[0][y * sprite.getIconWidth() + x];
		return argb;
        #elif MC_VER < MC_1_17_1
        int rgba = sprite.mainImage[0].getPixelRGBA(
                x + sprite.framesX[frameIndex] * sprite.getWidth(),
                y + sprite.framesY[frameIndex] * sprite.getHeight());
        return convertRgbaToArgb(rgba);
        #elif MC_VER < MC_1_19_4
		if (sprite.animatedTexture != null)
		{
			x += sprite.animatedTexture.getFrameX(frameIndex) * sprite.width;
			y += sprite.animatedTexture.getFrameY(frameIndex) * sprite.height;
		}
		int rgba = sprite.mainImage[0].getPixelRGBA(x, y);
		return convertRgbaToArgb(rgba);
		#elif MC_VER < MC_1_21_3
		if (sprite.contents().animatedTexture != null)
		{
			x += sprite.contents().animatedTexture.getFrameX(frameIndex) * sprite.contents().width();
			y += sprite.contents().animatedTexture.getFrameY(frameIndex) * sprite.contents().width();
		}
		int rgba = sprite.contents().originalImage.getPixelRGBA(x, y);
		return convertRgbaToArgb(rgba);
        #else
		
		SpriteContents content = sprite.contents(); // don't close, otherwise MC will be corrupted and you won't be able to re-access the texture
		if (content.animatedTexture != null)
		{
			x += content.animatedTexture.getFrameX(frameIndex) * content.width();
			y += content.animatedTexture.getFrameY(frameIndex) * content.width();
		}
		
		int argb = content.originalImage.getPixel(x, y);
		return argb;
        #endif
	}
	
	// used for some MC versions
	private static int convertRgbaToArgb(int rgba)
	{
		int r = (rgba & 0x000000FF);
		int g = (rgba & 0x0000FF00) >>> 8;
		int b = (rgba & 0x00FF0000) >>> 16;
		int a = (rgba & 0xFF000000) >>> 24;
		return ColorUtil.argbToInt(a, r, g, b);
	}
	
	
	
	public static int getWidth(TextureAtlasSprite texture)
	{
		#if MC_VER <= MC_1_12_2
		#if MC_VER <= MC_1_7_10
		return texture.getIconWidth() - (texture.useAnisotropicFiltering ? ANISOTROPIC_SPRITE_BORDER * 2 : 0);
		#else
		return texture.getIconWidth();
		#endif
        #elif MC_VER < MC_1_19_4
		return texture.getWidth();
        #else
		return texture.contents().width();
        #endif
	}
	public static int getHeight(TextureAtlasSprite texture)
	{
		#if MC_VER <= MC_1_12_2
		#if MC_VER <= MC_1_7_10
		return texture.getIconHeight() - (texture.useAnisotropicFiltering ? ANISOTROPIC_SPRITE_BORDER * 2 : 0);
		#else
		return texture.getIconHeight();
		#endif
        #elif MC_VER < MC_1_19_4
		return texture.getHeight();
        #else
		return texture.contents().height();
        #endif
	}
	
	public static float getMinU(TextureAtlasSprite sprite)
	{
		#if MC_VER <= MC_1_12_2
		return sprite.getMinU();
		#else
		return sprite.getU0();
		#endif
	}
	public static float getMaxU(TextureAtlasSprite sprite)
	{
		#if MC_VER <= MC_1_12_2
		return sprite.getMaxU();
		#else
		return sprite.getU1();
		#endif
	}
	
	public static float getMinV(TextureAtlasSprite sprite)
	{
		#if MC_VER <= MC_1_12_2
		return sprite.getMinV();
		#else
		return sprite.getV0();
		#endif
	}
	public static float getMaxV(TextureAtlasSprite sprite)
	{
		#if MC_VER <= MC_1_12_2
		return sprite.getMaxV();
		#else
		return sprite.getV1();
		#endif
	}
	
	
	
	#if MC_VER <= MC_1_7_10
	/**
	 * Resolves the {@link TextureAtlasSprite} for the given block face in 1.7.10. <br>
	 * 1.7.10 predates the baked model system, so there are no quads to rasterize;
	 * textures are fetched directly via {@link IIcon} using the same mod-compat
	 * handling {@link ClientBlockStateColorCache} uses
	 * (GregTech, {@link IconFlipped}, TwilightForest, IC2).
	 *
	 * @param sideOrdinal the {@link net.minecraftforge.common.util.ForgeDirection}/vanilla side
	 *                    index passed to {@link Block#getIcon(int, int)}
	 * @return the resolved sprite, or null if none could be found
	 */
	@Nullable
	public static TextureAtlasSprite resolveFaceSprite(IBlockState blockstate, int sideOrdinal)
	{
		IIcon icon = null;
		
		// GregTech
		if (GREG_TECH_ACCESSOR != null)
		{
			// GregTech icons are resolved per block/meta, not per face
			icon = GREG_TECH_ACCESSOR.resolveIcon(blockstate);
		}
		if (icon == null)
		{
			icon = blockstate.getBlock().getIcon(sideOrdinal, blockstate.getMeta());
		}
		
		if (icon instanceof IconFlipped)
		{
			icon = ((IconFlipped) icon).baseIcon;
		}
		
		// twilight forest
		if (icon != null 
			&& icon.getClass().getName().equals("twilightforest.block.GiantBlockIcon"))
		{
			icon = unwrapIcon(icon, "baseIcon");
		}
		// Industrial Craft 2
		if (icon != null 
			&& icon.getClass().getName().equals("ic2.core.block.BlockTextureStitched"))
		{
			icon = unwrapIcon(icon, "mappedTexture");
		}
		
		return (icon instanceof TextureAtlasSprite) ? (TextureAtlasSprite) icon : null;
	}

	/**
	 * Some mods wrap their real atlas sprite inside an {@link IIcon} field
	 * (IE TwilightForest's GiantBlockIcon, IC2's BlockTextureStitched). <br>
	 * This returns the icon stored in the named field, or the original {@code icon}
	 * unchanged if the field is missing, inaccessible, or null.
	 */
	private static IIcon unwrapIcon(IIcon icon, String fieldName)
	{
		try
		{
			Field field = icon.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			IIcon innerIcon = (IIcon) field.get(icon);
			return innerIcon != null ? innerIcon : icon;
		}
		catch (NoSuchFieldException | IllegalAccessException e)
		{
			return icon;
		}
	}
	#endif



}
