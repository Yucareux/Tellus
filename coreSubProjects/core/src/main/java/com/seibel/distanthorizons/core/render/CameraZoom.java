package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.util.MathUtil;

import java.util.concurrent.TimeUnit;

/**
 * Describes how far the camera is currently zoomed in
 * and which area of the world is visible through it.
 */
public class CameraZoom
{
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	public static final double NOT_ZOOMED_MAGNIFICATION = 1.0;
	
	/**
	 * The smallest camera magnification that's considered an intentional zoom. <br>
	 * Vanilla FOV effects (IE drawing a bow or swimming underwater) shrink the FOV 
	 * slightly and shouldn't cause LODs to reload. 
	 */
	private static final double MIN_ZOOM_MAGNIFICATION = 1.5;
	/**
	 * How much wider the zoom quality cone is than the zoomed camera's actual FOV.
	 * @see CameraZoom#coneTanHalfAngle
	 */
	private static final double ZOOM_CONE_PADDING_MULTIPLIER = 1.5;
	/**
	 * How long the camera has to stay zoomed in before LOD detail follows it. <br>
	 * MC 26.3 starts the camera's FOV multiplier at 0 on the first world join after launch,
	 * so the first frames look like an 8-16x zoom. Reacting to that queued a cone of
	 * full detail LODs out to the render distance, which could take several minutes to generate.
	 * A spyglass or zoom key is held much longer than this.
	 */
	private static final long ZOOM_ACTIVATION_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
	
	
	public static final CameraZoom INSTANCE = CameraZoom.createNotZoomed();
	private static final CameraZoom NOT_ZOOMED = CameraZoom.createNotZoomed();
	
	
	
	/** how many times larger objects appear on screen compared to the player's FOV setting */
	public double magnification;
	
	/**
	 * The tangent of half the zoom cone's horizontal angle. <br>
	 * The cone is slightly wider than the zoomed camera's FOV so LODs
	 * just off screen can start loading before the camera pans over them.
	 */
	public double coneTanHalfAngle;
	
	/** the camera's look direction projected onto the XZ plane, normalized */
	public double lookDirectionX;
	/** the camera's look direction projected onto the XZ plane, normalized */
	public double lookDirectionZ;
	
	/** when the camera started looking zoomed in, -1 if it isn't */
	private long zoomStartNanos = -1;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	private static CameraZoom createNotZoomed() { return new CameraZoom(NOT_ZOOMED_MAGNIFICATION, 0.0, 0.0, 0.0); }
	private CameraZoom(double magnification, double coneTanHalfAngle, double lookDirectionX, double lookDirectionZ)
	{
		this.magnification = magnification;
		this.coneTanHalfAngle = coneTanHalfAngle;
		this.lookDirectionX = lookDirectionX;
		this.lookDirectionZ = lookDirectionZ;
	}
	
	//endregion
	
	
	
	//==========//
	// updating //
	//==========//
	//region
	
	/**
	 * Updates the given zoom with how far the camera is currently zoomed in
	 * (IE when using a spyglass or zoom mod)
	 * and which direction the zoomed camera is looking. <br><br>
	 *
	 * Sets the input to {@link CameraZoom#NOT_ZOOMED} if the camera isn't zoomed in or zoomed quality increasing is disabled
	 */
	public void update(DhRenderState renderState)
	{
		if (!Config.Client.Advanced.Graphics.Quality.increaseQualityWhenZoomedIn.get())
		{
			// zoom quality disabled
			this.setNotZoomed();
			return;
		}
		
		// will be null before the first frame has rendered
		DhApiMat4f projectionMatrix = renderState.mcProjectionMatrix;
		if (projectionMatrix == null)
		{
			this.setNotZoomed();
			return;
		}
		
		if (projectionMatrix.equals(DhMat4f.IDENTITY))
		{
			// on some MC versions the model view and projection matrices are
			// pre-multiplied together and stored in the model view matrix
			projectionMatrix = renderState.mcModelViewMatrix;
			if (projectionMatrix == null)
			{
				this.setNotZoomed();
				return;
			}
		}
		
		
		
		// For a perspective projection this row's length is the cotangent of half the vertical FOV.
		// The row's length is used instead of m11 alone so the FOV can also be read from
		// pre-multiplied matrices, where the row is rotated by the model view's unit length rotation rows.
		double projectionYScale = Math.sqrt(
			MathUtil.pow2(projectionMatrix.m10)
				+ MathUtil.pow2(projectionMatrix.m11)
				+ MathUtil.pow2(projectionMatrix.m12));
		double fovSettingYScale = 1.0 / Math.tan(Math.toRadians(MC_RENDER.getFovSetting()) / 2.0);
		
		// how many times larger objects appear on screen compared to the player's FOV setting
		double magnification = projectionYScale / fovSettingYScale;
		if (magnification < MIN_ZOOM_MAGNIFICATION)
		{
			// ignores minor FOV reductions (IE vanilla FOV effects), 
			// FOV increases (IE sprinting), 
			// and non-perspective projections (IE shadow map rendering)
			this.setNotZoomed();
			return;
		}
		
		// limit how much additional detail a strong zoom (IE a spyglass) can request,
		// since each additional detail level quadruples the number of LODs that need to be loaded
		double quadraticBase = Config.Client.Advanced.Graphics.Quality.horizontalQuality.get().quadraticBase;
		double maxMagnification = Math.pow(quadraticBase, Config.Client.Advanced.Graphics.Quality.maxZoomQualityIncrease.get());
		magnification = Math.min(magnification, maxMagnification);
		
		// LOD detail is selected in 2D so only the look direction's horizontal component matters
		DhVec3f lookAtVector = MC_RENDER.getLookAtVector();
		double lookLengthXZ = Math.sqrt(MathUtil.pow2(lookAtVector.x) + MathUtil.pow2(lookAtVector.z));
		if (lookLengthXZ < 0.1)
		{
			// looking almost straight up or down,
			// no horizontal direction is being zoomed at
			this.setNotZoomed();
			return;
		}
		
		// same as the vertical FOV above, just for the horizontal FOV
		double projectionXScale = Math.sqrt(
			MathUtil.pow2(projectionMatrix.m00)
				+ MathUtil.pow2(projectionMatrix.m01)
				+ MathUtil.pow2(projectionMatrix.m02));
		double coneTanHalfAngle = (1.0 / projectionXScale) * ZOOM_CONE_PADDING_MULTIPLIER;
		
		
		// only follow zooms that are held,
		// brief FOV dips (IE the first frames after joining a world) shouldn't change LOD detail
		long nowNanos = System.nanoTime();
		if (this.zoomStartNanos == -1)
		{
			this.zoomStartNanos = nowNanos;
		}
		if (nowNanos - this.zoomStartNanos < ZOOM_ACTIVATION_DELAY_NANOS)
		{
			this.set(CameraZoom.NOT_ZOOMED);
			return;
		}
		
		this.set(
			magnification, coneTanHalfAngle,
			lookAtVector.x / lookLengthXZ, lookAtVector.z / lookLengthXZ);
	}
	
	private void setNotZoomed()
	{
		this.zoomStartNanos = -1;
		this.set(CameraZoom.NOT_ZOOMED);
	}
	private void set(CameraZoom that) { this.set(that.magnification, that.coneTanHalfAngle, that.lookDirectionX, that.lookDirectionZ); }
	private void set(double magnification, double coneTanHalfAngle, double lookDirectionX, double lookDirectionZ)
	{
		this.magnification = magnification;
		this.coneTanHalfAngle = coneTanHalfAngle;
		this.lookDirectionX = lookDirectionX;
		this.lookDirectionZ = lookDirectionZ;
	}
	
	//endregion
	
	
	
	//==============//
	// intersection //
	//==============//
	//region
	
	/**
	 * Returns true if any part of the given circle is visible through the zoomed camera. <br>
	 * The check is done in 2D on the XZ plane.
	 */
	public boolean coneIntersectsCircle(
		double coneOriginX, double coneOriginZ, 
		double circleCenterX, double circleCenterZ, double circleRadius)
	{
		double offsetX = circleCenterX - coneOriginX;
		double offsetZ = circleCenterZ - coneOriginZ;
		
		double distanceAlongLook = (offsetX * this.lookDirectionX) + (offsetZ * this.lookDirectionZ);
		if (distanceAlongLook < -circleRadius)
		{
			// entirely behind the camera
			return false;
		}
		
		// 2D cross product, how far the circle's center is from the camera's look line
		double distanceAcrossLook = Math.abs((offsetX * this.lookDirectionZ) - (offsetZ * this.lookDirectionX));
		return distanceAcrossLook <= (distanceAlongLook * this.coneTanHalfAngle) + circleRadius;
	}
	
	//endregion
	
	
	
}
