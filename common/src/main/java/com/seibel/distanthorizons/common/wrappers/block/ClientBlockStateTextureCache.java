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

import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDirection;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockColorOverrideEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockTextureOverrideEvent;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockFaceTexture;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

#if MC_VER <= MC_1_7_10
import net.minecraftforge.common.util.ForgeDirection;
#elif MC_VER < MC_1_21_5
import net.minecraft.client.renderer.block.model.BakedQuad;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
import org.joml.Vector3fc;
import net.minecraft.client.model.geom.builders.UVPair;
#else
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3fc;
#endif

/**
 * Bakes and stores a small texture for each face of a block state
 * by software rasterizing the state's model quads. <br>
 * This allows LODs to show the block's actual texture and silhouette
 * (IE fence posts and flower cutouts) instead of a single flat color. <br><br>
 *
 * Mod blocks are handled the same way vanilla blocks are since their
 * baked models and sprites are rasterized directly,
 * blocks without usable models (IE blocks rendered via block entities)
 * fall back to their particle texture.
 *
 * @see ClientBlockStateColorCache
 * @see BlockFaceTexture
 * @see BlockTextureRegistry
 */
public class ClientBlockStateTextureCache
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MC = Minecraft.getMinecraft();
	#else
	private static final Minecraft MC = Minecraft.getInstance();
	#endif
	
	/**
	 * The resolution face textures are baked at. <br>
	 * Sprites with a higher resolution (IE from resource packs) are down-sampled.
	 */
	public static final int TEXTURE_WIDTH_AND_HEIGHT = TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
	
	/** The bake order */
	private static final EDhDirection[] FACE_DIRECTIONS =
		{
			EDhDirection.DOWN,
			EDhDirection.UP,
			EDhDirection.NORTH,
			EDhDirection.SOUTH,
			EDhDirection.WEST,
			EDhDirection.EAST
		};
	
	/**
	 * Methods using MC's "RandomSource" object aren't thread safe <br>
	 * so we need to put locks around that logic. <br>
	 * specifically:
	 * <code>
	 * getBlockModel(blockState).getQuads(blockState, direction, RANDOM)
	 * </code>
	 */
	private static final ReentrantLock BAKE_LOCK = new ReentrantLock();
	
	private static final ConcurrentHashMap<BlockStateWrapper, BlockFaceTexture[]> TEXTURES_BY_BLOCK_WRAPPER = new ConcurrentHashMap<>();
	
	/** 
	 * can be enabled to make sure the textures are being parsed
	 * with the correct colors.
	 */
	private static final boolean WRITE_TEXTURES_TO_FILE_FOR_DEBUGGING = false;
	/** should end with a "/" */
	private static final String TEST_TEXTURE_OUTPUT_FOLDER_PATH = "C:/Users/James_Seibel/Desktop/tex_output/";
	
	private static final ThreadLocal<DhApiBlockTextureOverrideEvent.EventParam> TEXTURE_OVERRIDE_EVENT_PARAM_THREAD_LOCAL = ThreadLocal.withInitial(DhApiBlockTextureOverrideEvent.EventParam::new);
	
	
	
	
	//================//
	// public methods //
	//================//
	//region
	
	public static BlockFaceTexture getFaceTexture(BlockStateWrapper blockStateWrapper, EDhDirection direction)
	{
		BlockFaceTexture[] faceTextures = TEXTURES_BY_BLOCK_WRAPPER.computeIfAbsent(
				blockStateWrapper,
				ClientBlockStateTextureCache::computeFaceTextures);
		return faceTextures[direction.faceIndex];
	}
	
	/** Should be called whenever MC's textures change, IE when resource packs are swapped. */
	public static void clearCache() { TEXTURES_BY_BLOCK_WRAPPER.clear(); }
	
	//endregion
	
	
	
	//============//
	// API events //
	//============//
	//region
	
	private static BlockFaceTexture[] computeFaceTextures(BlockStateWrapper blockStateWrapper)
	{
		BlockFaceTexture[] blockFaceTextures = bakeAllFaceTextures(blockStateWrapper);
		
		// allow changing the texture if the API event is requested
		// (this is done to reduce GC pressure and speed up color getting)
		if (blockStateWrapper.allowApiColorOverride())
		{
			DhApiBlockTextureOverrideEvent.EventParam eventParam = TEXTURE_OVERRIDE_EVENT_PARAM_THREAD_LOCAL.get();
			
			for (int faceIndex = 0; faceIndex < FACE_DIRECTIONS.length; faceIndex++)
			{
				BlockFaceTexture blockFaceTexture = blockFaceTextures[faceIndex];
				EDhDirection faceDirection = FACE_DIRECTIONS[faceIndex];
				
				eventParam.update(
					blockStateWrapper,
					faceDirection.getApiVersion(),
					ClientBlockStateTextureCache::ApiTextureRegenFunc
				);
				ApiEventInjector.INSTANCE.fireAllEvents(DhApiBlockTextureOverrideEvent.class, eventParam);
				
				// let the API user change this texture
				blockFaceTexture.updateFromApiEvent(eventParam);
			}
		}
		
		if (WRITE_TEXTURES_TO_FILE_FOR_DEBUGGING)
		{
			writeTopAndNorthTexturesToFile(blockStateWrapper, blockFaceTextures);
		}
		return blockFaceTextures;
	}
	
	public static void ApiTextureRegenFunc(DhApiBlockTextureOverrideEvent.EventParam newEventParam, IDhApiBlockStateWrapper newApiBlockState, EDhApiDirection newApiDirection)
	{
		if (!(newApiBlockState instanceof BlockStateWrapper))
		{
			throw new ClassCastException("Invalid ["+ IDhApiBlockStateWrapper.class.getSimpleName()+"] value given. Block State wrapper object must be one given by the DH API (it can't be a custom implementation), specifically of type ["+ BlockStateWrapper.class.getName()+"].");
		}
		BlockStateWrapper newBlockState = (BlockStateWrapper) newApiBlockState;
		
		
		BlockFaceTexture newFaceTexture = bakeFaceTexture(newBlockState, EDhDirection.fromApiVersion(newApiDirection));
		for (int u = 0; u < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; u++)
		{
			for (int v = 0; v < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; v++)
			{
				int newColor = newFaceTexture.argbPixels[TextureUtil.getPixelIndex(u, v)];
				int a = ColorUtil.getAlpha(newColor);
				int r = ColorUtil.getRed(newColor);
				int g = ColorUtil.getGreen(newColor);
				int b = ColorUtil.getBlue(newColor);
				newEventParam.setColor(u, v, a, r, g, b);
			}
		}
	}
	
	//endregion
	
	
	
	//================//
	// texture baking //
	//================//
	//region
	
	private static BlockFaceTexture[] bakeAllFaceTextures(BlockStateWrapper blockStateWrapper)
	{
		BlockFaceTexture[] faceTextures = new BlockFaceTexture[FACE_DIRECTIONS.length];
		
		
		// only render textures if requested
		// done to handle some modeled blocks that don't rasterize well (like bamboo)
		if (!blockStateWrapper.renderTexture())
		{
			for (int faceIndex = 0; faceIndex < faceTextures.length; faceIndex++)
			{
				// just use the invisible color for each direction
				faceTextures[faceIndex] = BlockFaceTexture.createSolidColor(ColorUtil.INVISIBLE);
			}
			return faceTextures;
		}
		
		
		try
		{
			// getQuads() and RANDOM aren't thread safe so all baking is done in a lock
			BAKE_LOCK.lock();
			
			if (BlockStateWrapper.isAir(blockStateWrapper.blockState))
			{
				// Air shouldn't be rendered and is just here to capture the rare bug state where air's texture is requested.
				BlockFaceTexture invisibleTexture = BlockFaceTexture.createSolidColor(ColorUtil.INVISIBLE);
				Arrays.fill(faceTextures, invisibleTexture);
				return faceTextures;
			}
			
			if (blockStateWrapper.isLiquid())
			{
				// liquids don't have models to rasterize, bake their sprite directly
				BlockFaceTexture liquidTexture = bakeSpriteTexture(getParticleSprite(blockStateWrapper));
				Arrays.fill(faceTextures, liquidTexture);
				return faceTextures;
			}
			
			for (int faceIndex = 0; faceIndex < FACE_DIRECTIONS.length; faceIndex++)
			{
				faceTextures[faceIndex] = bakeFaceTexture(blockStateWrapper, FACE_DIRECTIONS[faceIndex]);
			}
		}
		catch (Exception bakeError)
		{
			LOGGER.warn("Failed to bake face textures for block ["+blockStateWrapper.getSerialString()+"], error: ["+bakeError.getMessage()+"], block will render as hot pink.", bakeError);
		}
		finally
		{
			BAKE_LOCK.unlock();
		}
		
		for (int faceIndex = 0; faceIndex < faceTextures.length; faceIndex++)
		{
			if (faceTextures[faceIndex] == null)
			{
				faceTextures[faceIndex] = BlockFaceTexture.createErrorGridTexture();
			}
		}
		
		
		
		return faceTextures;
	}
	private static BlockFaceTexture bakeFaceTexture(BlockStateWrapper blockStateWrapper, EDhDirection dhDirection)
	{
		#if MC_VER <= MC_1_7_10
		TextureAtlasSprite sprite = getFaceSprite(blockStateWrapper, dhDirection);
		return bakeSpriteTexture(sprite);
		
		#else
		
		//=============//
		// quad lookup //
		//=============//
		
		ArrayList<BakedQuad> rasterQuadList = new ArrayList<>();
		try
		{
			List<BakedQuad> bakedQuads;
			
			if (blockStateWrapper.useBottomTextureForSides()
				&& dhDirection != EDhDirection.UP
				&& dhDirection != EDhDirection.DOWN)
			{
				// Some blocks like grass/paths/mycilum have non-repeating side textures
				// which looks bad when repeated.
				// In those cases we just use the bottom texture.
				bakedQuads = QuadWrapper.getQuadsForDirection(blockStateWrapper.blockState, EDhDirection.DOWN);
			}
			else
			{
				if (dhDirection != EDhDirection.UP)
				{
					// non-up directions all get textures normally
					bakedQuads = QuadWrapper.getQuadsForDirection(blockStateWrapper.blockState, dhDirection);
				}
				else
				{
					// the top direction needs to get unculled quads first for farm land to render properly
					bakedQuads = QuadWrapper.getUnculledQuads(blockStateWrapper.blockState);
					if (bakedQuads == null
						|| bakedQuads.isEmpty())
					{
						bakedQuads = QuadWrapper.getQuadsForDirection(blockStateWrapper.blockState, dhDirection);
					}
				}
			}
			
			// try using the baked quads first if allowed
			if (!blockStateWrapper.alwaysRasterizeTexture())
			{
				if (bakedQuads != null
					&& !bakedQuads.isEmpty())
				{
					// Faces with culled quads cover the whole face (IE full cubes),
					// copying the quad's sprite directly is both more reliable
					// and more accurate than rasterizing.
					
					BakedQuad faceQuad = pickFaceQuad(bakedQuads);
					TextureAtlasSprite quadSprite = getQuadSprite(faceQuad);
					boolean isQuadTinted = isQuadTinted(faceQuad);
					
					return bakeSpriteTexture(quadSprite);
				}
			}
			
			
			// unculled quads (IE fence posts)
			// can be visible from every direction
			List<BakedQuad> unculledQuads = QuadWrapper.getUnculledQuads(blockStateWrapper.blockState);
			if (unculledQuads != null)
			{
				rasterQuadList.addAll(unculledQuads);
			}
		}
		catch (Exception ignore)
		{
			// failing to get quads can happen if the block is invalid
			// (i.e. AIR is somehow passed in)
		}
		
		if (rasterQuadList.isEmpty())
		{
			// blocks without quads (IE blocks rendered via block entities like chests)
			// fall back to their particle texture
			TextureAtlasSprite particleSprite = getParticleSprite(blockStateWrapper);
			return bakeSpriteTexture(particleSprite);
		}
		
		return createTextureByRasterizingQuads(blockStateWrapper, dhDirection, rasterQuadList);
		#endif
	}
	
	#if MC_VER > MC_1_7_10
	/** renders the given quads to a {@link BlockFaceTexture} to simulate the camera looking directly at the block */
	private static BlockFaceTexture createTextureByRasterizingQuads(BlockStateWrapper blockStateWrapper, EDhDirection dhDirection, ArrayList<BakedQuad> quadList)
	{
		//===============//
		// quad decoding //
		//===============//
		//region
		
		ArrayList<QuadGeometry> geometryList = new ArrayList<>(quadList.size());
		boolean anyQuadTinted = false;
		boolean anyQuadUntinted = false;
		for (int quadIndex = 0; quadIndex < quadList.size(); quadIndex++)
		{
			QuadGeometry quadGeometry = decodeQuad(quadList.get(quadIndex), dhDirection);
			geometryList.add(quadGeometry);
			
			anyQuadTinted |= quadGeometry.tinted;
			anyQuadUntinted |= !quadGeometry.tinted;
		}
		
		// Tinting can only be applied to the texture as a whole,
		// so when a face mixes tinted and untinted quads (IE grass block sides
		// where a tinted grass overlay covers untinted dirt) the tinted quads are skipped,
		// otherwise the untinted quads' colors would be tinted as well.
		boolean skipTintedQuads = anyQuadTinted && anyQuadUntinted;
		boolean textureTinted = anyQuadTinted && !anyQuadUntinted;
		
		// quads are drawn back to front
		geometryList.sort(Comparator.comparingDouble(QuadGeometry::getAverageDepth));
		
		//endregion
		
		
		
		//===============//
		// rasterization //
		//===============//
		//region
		
		int[] pixels = new int[TEXTURE_WIDTH_AND_HEIGHT * TEXTURE_WIDTH_AND_HEIGHT];
		boolean anyPixelDrawn = false;
		for (int geometryIndex = 0; geometryIndex < geometryList.size(); geometryIndex++)
		{
			QuadGeometry geometry = geometryList.get(geometryIndex);
			if (skipTintedQuads 
				&& geometry.tinted)
			{
				continue;
			}
			
			anyPixelDrawn |= rasterizeQuad(geometry, pixels);
		}
		
		if (!anyPixelDrawn)
		{
			// nothing is visible from this direction,
			// fall back to the particle texture since LODs expect
			// every face to be renderable
			return bakeSpriteTexture(getParticleSprite(blockStateWrapper));
		}
		
		//endregion
		
		return BlockFaceTexture.createTexture(TEXTURE_WIDTH_AND_HEIGHT, TEXTURE_WIDTH_AND_HEIGHT, pixels);
	}
	#endif
	
	/** Copies the given sprite directly, used for blocks where rasterizing model quads isn't possible. */
	private static BlockFaceTexture bakeSpriteTexture(@Nullable TextureAtlasSprite sprite)
	{
		if (sprite == null)
		{
			return BlockFaceTexture.createErrorGridTexture();
		}
		
		int spriteWidth = TextureAtlasSpriteWrapper.getWidth(sprite);
		int spriteHeight = TextureAtlasSpriteWrapper.getHeight(sprite);
		if (spriteWidth <= 0 
			|| spriteHeight <= 0)
		{
			return BlockFaceTexture.createErrorGridTexture();
		}
		
		int[] pixels = new int[TEXTURE_WIDTH_AND_HEIGHT * TEXTURE_WIDTH_AND_HEIGHT];
		for (int u = 0; u < TEXTURE_WIDTH_AND_HEIGHT; u++)
		{
			for (int v = 0; v < TEXTURE_WIDTH_AND_HEIGHT; v++)
			{
				int texelX = (u * spriteWidth) / TEXTURE_WIDTH_AND_HEIGHT;
				int texelY = (v * spriteHeight) / TEXTURE_WIDTH_AND_HEIGHT;
				pixels[TextureUtil.getPixelIndex(u,v)] = TextureAtlasSpriteWrapper.getPixelARGB(sprite, 0, texelX, texelY);
			}
		}
		return BlockFaceTexture.createTexture(TEXTURE_WIDTH_AND_HEIGHT, TEXTURE_WIDTH_AND_HEIGHT, pixels);
	}
	
	//endregion
	
	
	
	//===============//
	// rasterization //
	//===============//
	//region
	
	#if MC_VER > MC_1_7_10
	/**
	 * Draws the given quad into the pixel array
	 * by splitting it into two triangles and sampling
	 * the quad's sprite for each covered pixel.
	 *
	 * @return true if at least one pixel was drawn
	 */
	private static boolean rasterizeQuad(QuadGeometry geometry, int[] pixels)
	{
		boolean anyPixelDrawn = rasterizeTriangle(geometry, 0, 1, 2, pixels);
		anyPixelDrawn |= rasterizeTriangle(geometry, 0, 2, 3, pixels);
		return anyPixelDrawn;
	}
	
	/** @return true if at least one pixel was drawn */
	private static boolean rasterizeTriangle(QuadGeometry geometry, int vertexIndexA, int vertexIndexB, int vertexIndexC, int[] pixels)
	{
		// face space positions, in the range [0,1] for anything on the face
		float faceAU = geometry.faceUByVertex[vertexIndexA];
		float faceAV = geometry.faceVByVertex[vertexIndexA];
		float faceBU = geometry.faceUByVertex[vertexIndexB];
		float faceBV = geometry.faceVByVertex[vertexIndexB];
		float faceCU = geometry.faceUByVertex[vertexIndexC];
		float faceCV = geometry.faceVByVertex[vertexIndexC];
		
		// twice the triangle's signed area,
		// also the denominator for barycentric weights
		float area = ((faceBU - faceAU) * (faceCV - faceAV)) - ((faceBV - faceAV) * (faceCU - faceAU));
		if (Math.abs(area) < 0.000001f)
		{
			// the triangle is degenerate or perpendicular to this face
			return false;
		}
		
		int spriteWidth = TextureAtlasSpriteWrapper.getWidth(geometry.sprite);
		int spriteHeight = TextureAtlasSpriteWrapper.getHeight(geometry.sprite);
		if (spriteWidth <= 0 
			|| spriteHeight <= 0)
		{
			// no texture to draw
			return false;
		}
		
		// only check pixels inside the triangle's bounding box
		int minPixelU = Math.max((int) Math.floor(Math.min(faceAU, Math.min(faceBU, faceCU)) * TEXTURE_WIDTH_AND_HEIGHT), 0);
		int maxPixelU = Math.min((int) Math.ceil(Math.max(faceAU, Math.max(faceBU, faceCU)) * TEXTURE_WIDTH_AND_HEIGHT), TEXTURE_WIDTH_AND_HEIGHT - 1);
		int minPixelV = Math.max((int) Math.floor(Math.min(faceAV, Math.min(faceBV, faceCV)) * TEXTURE_WIDTH_AND_HEIGHT), 0);
		int maxPixelV = Math.min((int) Math.ceil(Math.max(faceAV, Math.max(faceBV, faceCV)) * TEXTURE_WIDTH_AND_HEIGHT), TEXTURE_WIDTH_AND_HEIGHT - 1);
		
		boolean anyPixelDrawn = false;
		for (int pixelV = minPixelV; pixelV <= maxPixelV; pixelV++)
		{
			for (int pixelU = minPixelU; pixelU <= maxPixelU; pixelU++)
			{
				// sample at the pixel's center
				float sampleU = (pixelU + 0.5f) / TEXTURE_WIDTH_AND_HEIGHT;
				float sampleV = (pixelV + 0.5f) / TEXTURE_WIDTH_AND_HEIGHT;
				
				// barycentric weights, all in [0,1] when the sample is inside the triangle
				float weightB = (((sampleU - faceAU) * (faceCV - faceAV)) - ((sampleV - faceAV) * (faceCU - faceAU))) / area;
				float weightC = (((faceBU - faceAU) * (sampleV - faceAV)) - ((faceBV - faceAV) * (sampleU - faceAU))) / area;
				float weightA = 1.0f - weightB - weightC;
				if (weightA < 0.0f 
					|| weightB < 0.0f 
					|| weightC < 0.0f)
				{
					continue;
				}
				
				// interpolate the sprite coordinates
				float spriteU = (weightA * geometry.spriteUByVertex[vertexIndexA]) + (weightB * geometry.spriteUByVertex[vertexIndexB]) + (weightC * geometry.spriteUByVertex[vertexIndexC]);
				float spriteV = (weightA * geometry.spriteVByVertex[vertexIndexA]) + (weightB * geometry.spriteVByVertex[vertexIndexB]) + (weightC * geometry.spriteVByVertex[vertexIndexC]);
				
				int texelX = MathUtil.clamp(0, (int) (spriteU * spriteWidth), spriteWidth - 1);
				int texelY = MathUtil.clamp(0, (int) (spriteV * spriteHeight), spriteHeight - 1);
				
				int argbSourceColor = TextureAtlasSpriteWrapper.getPixelARGB(geometry.sprite, 0, texelX, texelY);
				if (ColorUtil.getAlpha(argbSourceColor) == 0)
				{
					// fully transparent texels (IE the area around a fence post)
					// shouldn't overwrite anything behind them
					continue;
				}
				
				int pixelIndex = (pixelV * TEXTURE_WIDTH_AND_HEIGHT) + pixelU;
				pixels[pixelIndex] = blendSourceOver(argbSourceColor, pixels[pixelIndex]);
				anyPixelDrawn = true;
			}
		}
		return anyPixelDrawn;
	}
	
	/** standard alpha compositing, drawing the source color over the destination color */
	private static int blendSourceOver(int sourceArgb, int destArgb)
	{
		int sourceAlpha = ColorUtil.getAlpha(sourceArgb);
		if (sourceAlpha == 255)
		{
			return sourceArgb;
		}
		
		int destAlpha = ColorUtil.getAlpha(destArgb);
		int inverseSourceAlpha = 255 - sourceAlpha;
		
		int outAlpha = sourceAlpha + ((destAlpha * inverseSourceAlpha) / 255);
		if (outAlpha == 0)
		{
			return ColorUtil.INVISIBLE;
		}
		
		int outRed = ((ColorUtil.getRed(sourceArgb) * sourceAlpha) + ((ColorUtil.getRed(destArgb) * destAlpha * inverseSourceAlpha) / 255)) / outAlpha;
		int outGreen = ((ColorUtil.getGreen(sourceArgb) * sourceAlpha) + ((ColorUtil.getGreen(destArgb) * destAlpha * inverseSourceAlpha) / 255)) / outAlpha;
		int outBlue = ((ColorUtil.getBlue(sourceArgb) * sourceAlpha) + ((ColorUtil.getBlue(destArgb) * destAlpha * inverseSourceAlpha) / 255)) / outAlpha;
		return ColorUtil.argbToInt(outAlpha, outRed, outGreen, outBlue);
	}
	#endif
	
	//endregion
	
	
	
	//===============//
	// quad decoding //
	//===============//
	//region
	
	#if MC_VER > MC_1_7_10
	private static QuadGeometry decodeQuad(BakedQuad quad, EDhDirection dhDirection)
	{
		QuadGeometry geometry = new QuadGeometry();
		
		geometry.sprite = getQuadSprite(quad);
		geometry.tinted = isQuadTinted(quad);
		
		for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++)
		{
			float x;
			float y;
			float z;
			float u;
			float v;
			
			#if MC_VER <= MC_1_12_2
			// 7 ints per vertex: x, y, z, color, u, v, lightmap
			int[] vertexData = quad.getVertexData();
			int vertexOffset = vertexIndex * 7;
			x = Float.intBitsToFloat(vertexData[vertexOffset]);
			y = Float.intBitsToFloat(vertexData[vertexOffset + 1]);
			z = Float.intBitsToFloat(vertexData[vertexOffset + 2]);
			u = Float.intBitsToFloat(vertexData[vertexOffset + 4]);
			v = Float.intBitsToFloat(vertexData[vertexOffset + 5]);
			#elif MC_VER <= MC_1_21_4
			// 8 ints per vertex: x, y, z, color, u, v, lightmap, normal
			int[] vertexData = quad.getVertices();
			int vertexOffset = vertexIndex * 8;
			x = Float.intBitsToFloat(vertexData[vertexOffset]);
			y = Float.intBitsToFloat(vertexData[vertexOffset + 1]);
			z = Float.intBitsToFloat(vertexData[vertexOffset + 2]);
			u = Float.intBitsToFloat(vertexData[vertexOffset + 4]);
			v = Float.intBitsToFloat(vertexData[vertexOffset + 5]);
			#elif MC_VER <= MC_1_21_10
			// 8 ints per vertex: x, y, z, color, u, v, lightmap, normal
			int[] vertexData = quad.vertices();
			int vertexOffset = vertexIndex * 8;
			x = Float.intBitsToFloat(vertexData[vertexOffset]);
			y = Float.intBitsToFloat(vertexData[vertexOffset + 1]);
			z = Float.intBitsToFloat(vertexData[vertexOffset + 2]);
			u = Float.intBitsToFloat(vertexData[vertexOffset + 4]);
			v = Float.intBitsToFloat(vertexData[vertexOffset + 5]);
			#else
			Vector3fc position = quad.position(vertexIndex);
			x = position.x();
			y = position.y();
			z = position.z();
			long packedUv = quad.packedUV(vertexIndex);
			u = UVPair.unpackU(packedUv);
			v = UVPair.unpackV(packedUv);
			#endif
			
			
			#if MC_VER <= MC_1_21_11
			// vertex UVs are texture atlas coordinates and
			// need to be converted into sprite local coordinates
			float minU = TextureAtlasSpriteWrapper.getMinU(geometry.sprite);
			float maxU = TextureAtlasSpriteWrapper.getMaxU(geometry.sprite);
			float minV = TextureAtlasSpriteWrapper.getMinV(geometry.sprite);
			float maxV = TextureAtlasSpriteWrapper.getMaxV(geometry.sprite);
			geometry.spriteUByVertex[vertexIndex] = (maxU != minU) ? ((u - minU) / (maxU - minU)) : 0.0f;
			geometry.spriteVByVertex[vertexIndex] = (maxV != minV) ? ((v - minV) / (maxV - minV)) : 0.0f;
			#else
			// vertex UVs are already sprite local coordinates,
			// the renderer looks the sprite up via the quad's material instead of the vertex data
			geometry.spriteUByVertex[vertexIndex] = u;
			geometry.spriteVByVertex[vertexIndex] = v;
			#endif
			
			
			projectOntoQuadFace(dhDirection, x, y, z, geometry, vertexIndex);
		}
		
		return geometry;
	}
	/** Converts a block space position into the face space described in {@link QuadGeometry}. */
	private static void projectOntoQuadFace(
		EDhDirection dhDirection, 
		float x, float y, float z, 
		QuadGeometry geometry, int vertexIndex)
	{
		float faceU;
		float faceV;
		float depth;
		switch (dhDirection)
		{
			case UP:
				faceU = x;
				faceV = z;
				depth = y;
				break;
			case DOWN:
				faceU = x;
				faceV = 1.0f - z;
				depth = 1.0f - y;
				break;
			case NORTH:
				faceU = 1.0f - x;
				faceV = 1.0f - y;
				depth = 1.0f - z;
				break;
			case SOUTH:
				faceU = x;
				faceV = 1.0f - y;
				depth = z;
				break;
			case WEST:
				faceU = z;
				faceV = 1.0f - y;
				depth = 1.0f - x;
				break;
			case EAST:
				faceU = 1.0f - z;
				faceV = 1.0f - y;
				depth = x;
				break;
			default:
				throw new IllegalArgumentException("No face projection for direction [" + dhDirection + "].");
		}
		
		geometry.faceUByVertex[vertexIndex] = faceU;
		geometry.faceVByVertex[vertexIndex] = faceV;
		geometry.depthByVertex[vertexIndex] = depth;
	}
	
	//================//
	// helper classes //
	//================//
	//region
	
	/**
	 * A Minecraft {@link BakedQuad} converted into the coordinate 
	 * space of the face it's baked onto.
	 */
	private static class QuadGeometry
	{
		/** 
		 * In the range [0,1] with (0,0) being the face's top left corner. <br>
		 * U increases to the right. <br>
		 */
		public final float[] faceUByVertex = new float[4];
		/**
		 * In the range [0,1] with (0,0) being the face's top left corner. <br>
		 * V increases downward.
		 */
		public final float[] faceVByVertex = new float[4];
		
		/** 
		 * Increases as it moves towards the viewer. <br>
		 * A depth of 1 
		 */
		public final float[] depthByVertex = new float[4];
		
		/** sprite local texture coordinates in the range [0,1] */
		public final float[] spriteUByVertex = new float[4];
		/** sprite local texture coordinates in the range [0,1] */
		public final float[] spriteVByVertex = new float[4];
		
		
		public TextureAtlasSprite sprite;
		public boolean tinted;
		
		
		public double getAverageDepth()
		{ return (this.depthByVertex[0] + this.depthByVertex[1] + this.depthByVertex[2] + this.depthByVertex[3]) / 4.0; }
	}
	
	//endregion
	//endregion
	#endif
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	@Nullable
	private static TextureAtlasSprite getParticleSprite(BlockStateWrapper blockStateWrapper)
	{
		if (blockStateWrapper.blockState == null)
		{
			return null;
		}
		
		try
		{
			#if MC_VER <= MC_1_12_2
			#if MC_VER <= MC_1_7_10
			return getFaceSprite(blockStateWrapper, EDhDirection.UP);
			#else
			return MC.getBlockRendererDispatcher().getBlockModelShapes().getTexture(blockStateWrapper.blockState);
			#endif
			#elif MC_VER <= MC_1_21_11
			return MC.getModelManager().getBlockModelShaper().getParticleIcon(blockStateWrapper.blockState);
			#else
			return MC.getModelManager().getBlockStateModelSet().get(blockStateWrapper.blockState).particleMaterial().sprite();
			#endif
		}
		catch (Exception e)
		{
			LOGGER.warn("Failed to get particle sprite for block ["+blockStateWrapper.getSerialString()+"], error: ["+e.getMessage()+"].", e);
			return null;
		}
	}
	
	#if MC_VER <= MC_1_7_10
	@Nullable
	private static TextureAtlasSprite getFaceSprite(BlockStateWrapper blockStateWrapper, EDhDirection direction)
	{
		if (blockStateWrapper.blockState == null)
		{
			return null;
		}
		
		// shouldn't happen, but just in case
		int directionIndex = 0;
		ForgeDirection forgeDirection = McObjectConverter.convert(direction);
		if (forgeDirection != null)
		{
			directionIndex = forgeDirection.ordinal();
		}
		
		return TextureAtlasSpriteWrapper.resolveFaceSprite(blockStateWrapper.blockState, directionIndex);
	}
	#endif
	
	#if MC_VER > MC_1_7_10
	/** Picks which quad represents the face when several overlap. */
	private static BakedQuad pickFaceQuad(List<BakedQuad> quadList)
	{
		for (int i = 0; i < quadList.size(); i++)
		{
			// When tinted and untinted quads mix 
			// (IE grass block sides where a tinted grass overlay covers untinted dirt) 
			// the untinted quad is used since
			// tinting can only be applied to the whole face texture,
			// this matches how flat color resolution handles those faces.
			if (!isQuadTinted(quadList.get(i)))
			{
				return quadList.get(i);
			}
		}
		
		return quadList.get(0);
	}
	
	private static TextureAtlasSprite getQuadSprite(BakedQuad quad)
	{
		#if MC_VER <= MC_1_12_2
		return quad.getSprite();
		#elif MC_VER < MC_1_17_1
		return quad.sprite;
		#elif MC_VER < MC_1_21_5
		return quad.getSprite();
		#elif MC_VER <= MC_1_21_11
		return quad.sprite();
		#else
		return quad.materialInfo().sprite();
		#endif
	}
	
	private static boolean isQuadTinted(BakedQuad quad)
	{
		#if MC_VER <= MC_1_12_2
		return quad.hasTintIndex();
		#elif MC_VER <= MC_1_21_11
		return quad.isTinted();
		#else
		return quad.materialInfo().isTinted();
		#endif
	}
	#endif
	
	//endregion
	
	
	
	//====================//
	// debug file writing //
	//====================//
	//region
	
	private static void writeTopAndNorthTexturesToFile(BlockStateWrapper blockStateWrapper, BlockFaceTexture[] blockFaceTextures)
	{
		for (int i = 0; i < blockFaceTextures.length; i++)
		{
			// top and north are generally enough when troubleshooting texture problems,
			// although this can be commented out if additional directions need to be tested
			EDhDirection dir = FACE_DIRECTIONS[i];
			if (dir != EDhDirection.UP
				&& dir != EDhDirection.NORTH)
			{
				continue;
			}
			
			
			BlockFaceTexture faceTexture = blockFaceTextures[dir.faceIndex];
			
			String blockSerial = blockStateWrapper
				.getSerialString()
				.replace(":", "-")
				.replace("{", "[")
				.replace("}", "]")
				;
			String filePath = TEST_TEXTURE_OUTPUT_FOLDER_PATH + blockSerial + "_" + dir + ".png";
			try
			{
				writeArgbPixelsToPng(faceTexture, filePath);
			}
			catch (Exception e)
			{
				LOGGER.error("failed to save file ["+filePath+"], error: ["+e.getMessage()+"]");
			}
		}
	}
	
	public static void writeArgbPixelsToPng(BlockFaceTexture faceTexture, String outputPath)
		throws IOException
	{
		// Multiplies the output texture size by this many times.
		// Done to make viewing textures in File Explorer easier.
		int scale = 8;
		
		BufferedImage image = new BufferedImage(
			faceTexture.width * scale,
			faceTexture.height * scale, 
			BufferedImage.TYPE_INT_ARGB);
		
		for (int u = 0; u < faceTexture.width; u++)
		{
			for (int v = 0; v < faceTexture.height; v++)
			{
				int argb = faceTexture.argbPixels[(v * faceTexture.width) + u];
				
				// duplicate the same pixel "scale" times to make it larger
				for (int uScale = 0; uScale < scale; uScale++)
				{
					for (int vScale = 0; vScale < scale; vScale++)
					{
						image.setRGB(
							(u * scale) + uScale, 
							(v * scale) + vScale, 
							argb);
					}
				}
			}
		}
		
		File outputFile = new File(outputPath);
		outputFile.mkdirs();
		if (!ImageIO.write(image, "png", outputFile))
		{
			throw new IOException("No PNG writer found, javax.imageio may not be available.");
		}
	}
	
	//endregion
	
	

}
