package com.seibel.distanthorizons.common.render.openGl.glObject.texture;

import com.seibel.distanthorizons.common.render.openGl.glObject.enums.EGlVersion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.Locale;
import java.util.Optional;

public enum EGlDhInternalTextureFormat
{
	RGBA(GL11.GL_RGBA, EGlVersion.GL_11, EGlDhPixelFormat.RGBA),
	
	// 8-bit normalized
	R8(GL30.GL_R8, EGlVersion.GL_30, EGlDhPixelFormat.RED),
	RG8(GL30.GL_RG8, EGlVersion.GL_30, EGlDhPixelFormat.RG),
	RGB8(GL11.GL_RGB8, EGlVersion.GL_11, EGlDhPixelFormat.RGB),
	RGBA8(GL11.GL_RGBA8, EGlVersion.GL_11, EGlDhPixelFormat.RGBA),
	
	// 8-bit signed normalized
	R8_SNORM(GL31.GL_R8_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RED),
	RG8_SNORM(GL31.GL_RG8_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RG),
	RGB8_SNORM(GL31.GL_RGB8_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RGB),
	RGBA8_SNORM(GL31.GL_RGBA8_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RGBA),
	
	// 16-bit normalized
	R16(GL30.GL_R16, EGlVersion.GL_30, EGlDhPixelFormat.RED),
	RG16(GL30.GL_RG16, EGlVersion.GL_30, EGlDhPixelFormat.RG),
	RGB16(GL11.GL_RGB16, EGlVersion.GL_11, EGlDhPixelFormat.RGB),
	RGBA16(GL11.GL_RGBA16, EGlVersion.GL_11, EGlDhPixelFormat.RGBA),
	
	// 16-bit signed normalized
	R16_SNORM(GL31.GL_R16_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RED),
	RG16_SNORM(GL31.GL_RG16_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RG),
	RGB16_SNORM(GL31.GL_RGB16_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RGB),
	RGBA16_SNORM(GL31.GL_RGBA16_SNORM, EGlVersion.GL_31, EGlDhPixelFormat.RGBA),
	
	// 16-bit float
	R16F(GL30.GL_R16F, EGlVersion.GL_30, EGlDhPixelFormat.RED),
	RG16F(GL30.GL_RG16F, EGlVersion.GL_30, EGlDhPixelFormat.RG),
	RGB16F(GL30.GL_RGB16F, EGlVersion.GL_30, EGlDhPixelFormat.RGB),
	RGBA16F(GL30.GL_RGBA16F, EGlVersion.GL_30, EGlDhPixelFormat.RGBA),
	
	// 32-bit float
	R32F(GL30.GL_R32F, EGlVersion.GL_30, EGlDhPixelFormat.RED),
	RG32F(GL30.GL_RG32F, EGlVersion.GL_30, EGlDhPixelFormat.RG),
	RGB32F(GL30.GL_RGB32F, EGlVersion.GL_30, EGlDhPixelFormat.RGB),
	RGBA32F(GL30.GL_RGBA32F, EGlVersion.GL_30, EGlDhPixelFormat.RGBA),
	
	// 8-bit integer
	R8I(GL30.GL_R8I, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG8I(GL30.GL_RG8I, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB8I(GL30.GL_RGB8I, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA8I(GL30.GL_RGBA8I, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// 8-bit unsigned integer
	R8UI(GL30.GL_R8UI, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG8UI(GL30.GL_RG8UI, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB8UI(GL30.GL_RGB8UI, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA8UI(GL30.GL_RGBA8UI, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// 16-bit integer
	R16I(GL30.GL_R16I, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG16I(GL30.GL_RG16I, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB16I(GL30.GL_RGB16I, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA16I(GL30.GL_RGBA16I, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// 16-bit unsigned integer
	R16UI(GL30.GL_R16UI, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG16UI(GL30.GL_RG16UI, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB16UI(GL30.GL_RGB16UI, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA16UI(GL30.GL_RGBA16UI, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// 32-bit integer
	R32I(GL30.GL_R32I, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG32I(GL30.GL_RG32I, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB32I(GL30.GL_RGB32I, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA32I(GL30.GL_RGBA32I, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// 32-bit unsigned integer
	R32UI(GL30.GL_R32UI, EGlVersion.GL_30, EGlDhPixelFormat.RED_INTEGER),
	RG32UI(GL30.GL_RG32UI, EGlVersion.GL_30, EGlDhPixelFormat.RG_INTEGER),
	RGB32UI(GL30.GL_RGB32UI, EGlVersion.GL_30, EGlDhPixelFormat.RGB_INTEGER),
	RGBA32UI(GL30.GL_RGBA32UI, EGlVersion.GL_30, EGlDhPixelFormat.RGBA_INTEGER),
	
	// Mixed
	R3_G3_B2(GL11.GL_R3_G3_B2, EGlVersion.GL_11, EGlDhPixelFormat.RGB),
	RGB5_A1(GL11.GL_RGB5_A1, EGlVersion.GL_11, EGlDhPixelFormat.RGBA),
	RGB10_A2(GL11.GL_RGB10_A2, EGlVersion.GL_11, EGlDhPixelFormat.RGBA),
	R11F_G11F_B10F(GL30.GL_R11F_G11F_B10F, EGlVersion.GL_30, EGlDhPixelFormat.RGB),
	RGB9_E5(GL30.GL_RGB9_E5, EGlVersion.GL_30, EGlDhPixelFormat.RGB);
	
	
	
	private final int glFormat;
	private final EGlVersion minimumGlVersion;
	private final EGlDhPixelFormat expectedPixelFormat;
	
	
	
	EGlDhInternalTextureFormat(int glFormat, EGlVersion minimumGlVersion, EGlDhPixelFormat expectedPixelFormat)
	{
		this.glFormat = glFormat;
		this.minimumGlVersion = minimumGlVersion;
		this.expectedPixelFormat = expectedPixelFormat;
	}
	
	
	
	public static Optional<EGlDhInternalTextureFormat> fromString(String name)
	{
		try
		{
			return Optional.of(EGlDhInternalTextureFormat.valueOf(name.toUpperCase(Locale.US)));
		}
		catch (IllegalArgumentException e)
		{
			return Optional.empty();
		}
	}
	
	public int getGlFormat() { return this.glFormat; }
	
	public EGlDhPixelFormat getPixelFormat() { return this.expectedPixelFormat; }
	
	public EGlVersion getMinimumGlVersion() { return this.minimumGlVersion; }
	
	
	
}
