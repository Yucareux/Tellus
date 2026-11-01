package com.seibel.distanthorizons.common.render.openGl.glObject.texture;

import com.seibel.distanthorizons.common.render.openGl.glObject.enums.EGlVersion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.Locale;
import java.util.Optional;

public enum EGlDhPixelType
{
	BYTE(GL11.GL_BYTE, EGlVersion.GL_11),
	SHORT(GL11.GL_SHORT, EGlVersion.GL_11),
	INT(GL11.GL_INT, EGlVersion.GL_11),
	HALF_FLOAT(GL30.GL_HALF_FLOAT, EGlVersion.GL_30),
	FLOAT(GL11.GL_FLOAT, EGlVersion.GL_11),
	UNSIGNED_BYTE(GL11.GL_UNSIGNED_BYTE, EGlVersion.GL_11),
	UNSIGNED_BYTE_3_3_2(GL12.GL_UNSIGNED_BYTE_3_3_2, EGlVersion.GL_12),
	UNSIGNED_BYTE_2_3_3_REV(GL12.GL_UNSIGNED_BYTE_2_3_3_REV, EGlVersion.GL_12),
	UNSIGNED_SHORT(GL11.GL_UNSIGNED_SHORT, EGlVersion.GL_11),
	UNSIGNED_SHORT_5_6_5(GL12.GL_UNSIGNED_SHORT_5_6_5, EGlVersion.GL_12),
	UNSIGNED_SHORT_5_6_5_REV(GL12.GL_UNSIGNED_SHORT_5_6_5_REV, EGlVersion.GL_12),
	UNSIGNED_SHORT_4_4_4_4(GL12.GL_UNSIGNED_SHORT_4_4_4_4, EGlVersion.GL_12),
	UNSIGNED_SHORT_4_4_4_4_REV(GL12.GL_UNSIGNED_SHORT_4_4_4_4_REV, EGlVersion.GL_12),
	UNSIGNED_SHORT_5_5_5_1(GL12.GL_UNSIGNED_SHORT_5_5_5_1, EGlVersion.GL_12),
	UNSIGNED_SHORT_1_5_5_5_REV(GL12.GL_UNSIGNED_SHORT_1_5_5_5_REV, EGlVersion.GL_12),
	UNSIGNED_INT(GL11.GL_UNSIGNED_INT, EGlVersion.GL_11),
	UNSIGNED_INT_8_8_8_8(GL12.GL_UNSIGNED_INT_8_8_8_8, EGlVersion.GL_12),
	UNSIGNED_INT_8_8_8_8_REV(GL12.GL_UNSIGNED_INT_8_8_8_8_REV, EGlVersion.GL_12),
	UNSIGNED_INT_10_10_10_2(GL12.GL_UNSIGNED_INT_10_10_10_2, EGlVersion.GL_12),
	UNSIGNED_INT_2_10_10_10_REV(GL12.GL_UNSIGNED_INT_2_10_10_10_REV, EGlVersion.GL_12);
	
	
	
	private final int glFormat;
	private final EGlVersion minimumGlVersion;
	
	
	
	EGlDhPixelType(int glFormat, EGlVersion minimumGlVersion)
	{
		this.glFormat = glFormat;
		this.minimumGlVersion = minimumGlVersion;
	}
	
	
	
	public static Optional<EGlDhPixelType> fromString(String name)
	{
		try
		{
			return Optional.of(EGlDhPixelType.valueOf(name.toUpperCase(Locale.US)));
		}
		catch (IllegalArgumentException e)
		{
			return Optional.empty();
		}
	}
	
	public int getGlFormat() { return glFormat; }
	
	public EGlVersion getMinimumGlVersion() { return minimumGlVersion; }
	
}
