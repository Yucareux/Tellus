package com.seibel.distanthorizons.forge112;

import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;

public class RenderHelper
{
	private static DhMat4f modelViewMatrix;
	private static DhMat4f projectionMatrix;
	
	
	
	//=================//
	// matrix handling //
	//=================//
	//region
	
	public static DhMat4f getModelViewMatrix() { return new DhMat4f(modelViewMatrix); }
	public static DhMat4f getProjectionMatrix() { return new DhMat4f(projectionMatrix); }
	
	public static void setModelViewMatrixFromBuffer(FloatBuffer modelviewBuffer)
	{ modelViewMatrix = McObjectConverter.convert(new Matrix4f(modelviewBuffer)); }
	public static void setProjectionMatrixFromBuffer(FloatBuffer projectionBuffer)
	{ projectionMatrix = McObjectConverter.convert(new Matrix4f(projectionBuffer)); }
	
	//endregion
}
