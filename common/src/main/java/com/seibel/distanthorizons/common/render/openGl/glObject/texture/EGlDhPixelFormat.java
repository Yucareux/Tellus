package com.seibel.distanthorizons.common.render.openGl.glObject.texture;

import com.seibel.distanthorizons.common.render.openGl.glObject.enums.EGlVersion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.Locale;
import java.util.Optional;

public enum EGlDhPixelFormat
{
	RED(GL11.GL_RED, EGlVersion.GL_11, false),
	RG(GL30.GL_RG, EGlVersion.GL_30, false),
	RGB(GL11.GL_RGB, EGlVersion.GL_11, false),
	BGR(GL12.GL_BGR, EGlVersion.GL_12, false),
	RGBA(GL11.GL_RGBA, EGlVersion.GL_11, false),
	BGRA(GL12.GL_BGRA, EGlVersion.GL_12, false),
	RED_INTEGER(GL30.GL_RED_INTEGER, EGlVersion.GL_30, true),
	RG_INTEGER(GL30.GL_RG_INTEGER, EGlVersion.GL_30, true),
	RGB_INTEGER(GL30.GL_RGB_INTEGER, EGlVersion.GL_30, true),
	BGR_INTEGER(GL30.GL_BGR_INTEGER, EGlVersion.GL_30, true),
	RGBA_INTEGER(GL30.GL_RGBA_INTEGER, EGlVersion.GL_30, true),
	BGRA_INTEGER(GL30.GL_BGRA_INTEGER, EGlVersion.GL_30, true);
	
	
	
	private final int glFormat;
	private final EGlVersion minimumGlVersion;
	private final boolean isInteger;
	
	
	
	EGlDhPixelFormat(int glFormat, EGlVersion minimumGlVersion, boolean isInteger)
	{
		this.glFormat = glFormat;
		this.minimumGlVersion = minimumGlVersion;
		this.isInteger = isInteger;
	}
	
	
	
	public static Optional<EGlDhPixelFormat> fromString(String name)
	{
		try
		{
			return Optional.of(EGlDhPixelFormat.valueOf(name.toUpperCase(Locale.US)));
		}
		catch (IllegalArgumentException e)
		{
			return Optional.empty();
		}
	}
	
	public int getGlFormat() { return this.glFormat; }
	
	public EGlVersion getMinimumGlVersion() { return this.minimumGlVersion; }
	
	public boolean isInteger() { return this.isInteger; }
	
}
