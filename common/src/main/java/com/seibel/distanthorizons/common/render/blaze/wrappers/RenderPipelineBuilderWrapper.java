package com.seibel.distanthorizons.common.render.blaze.wrappers;


#if MC_VER <= MC_1_21_10
public class RenderPipelineBuilderWrapper {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
#else
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
#endif

#if MC_VER <= MC_1_21_11
import com.mojang.blaze3d.platform.DepthTestFunction;
#elif MC_VER <= MC_26_1_2
import com.mojang.blaze3d.platform.CompareOp;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
#else
#endif

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Optional;

public class RenderPipelineBuilderWrapper
{
	public static final String NAME_PREFIX = "distanthorizons:";
	
	private static final String SHADER_RESOURCE_FOLDER = "assets/distanthorizons/shaders/";
	
	private static final ClassLoader CLASS_LOADER = RenderPipelineBuilderWrapper.class.getClassLoader();
	
	
	private final RenderPipeline.Builder blazePipelineBuilder;
	
	// variables for specific builder options should be put next to their builder methods for simpler organization
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public RenderPipelineBuilderWrapper()
	{
		this.blazePipelineBuilder = RenderPipeline.builder();
	}
	
	//endregion
	
	
	
	//==========//
	// building //
	//==========//
	//region
	
	private boolean writeDepth = false;
	public RenderPipelineBuilderWrapper withDepthWrite(boolean write)
	{
		this.writeDepth = write;
		return this;
	}
	
	private boolean writeColor = false;
	public RenderPipelineBuilderWrapper withColorWrite(boolean write)
	{
		this.writeColor = write;
		return this;
	}
	
	private BlendFunction blendFunction = null;
	public RenderPipelineBuilderWrapper withBlend(BlendFunction blendFunction)
	{
		this.blendFunction = blendFunction;
		return this;
	}
	public RenderPipelineBuilderWrapper withoutBlend()
	{
		this.blendFunction = null;
		return this;
	}
	
	private EDhDepthTest depthTest;
	public RenderPipelineBuilderWrapper withDepthTest(EDhDepthTest depthTest)
	{
		this.depthTest = depthTest;
		return this;
	}
	
	public RenderPipelineBuilderWrapper withFaceCulling(boolean culling)
	{
		this.blazePipelineBuilder.withCull(culling);
		return this;
	}
	
	public RenderPipelineBuilderWrapper withPolygonMode(EDhPolygonMode dhMode)
	{
		PolygonMode blazeMode;
		switch (dhMode)
		{
			case FILL:
				blazeMode = PolygonMode.FILL;
				break;
			case WIREFRAME:
				blazeMode = PolygonMode.WIREFRAME;
				break;
				
			default:
				throw new UnsupportedOperationException("No polygonMode defined for type ["+dhMode+"].");
		}
		
		this.blazePipelineBuilder.withPolygonMode(blazeMode);
		return this;
	}
	
	public RenderPipelineBuilderWrapper withName(String name) throws IllegalArgumentException
	{
		// Identifiers must be of a specific format
		if (!isValidIdentifier(name))
		{
			throw new IllegalArgumentException("Non [a-z0-9/._-] character in name: ["+name+"].");
		}
		
		this.blazePipelineBuilder.withLocation(Identifier.parse(NAME_PREFIX + name));
		return this;
	}
	
	private final ArrayList<String> samplerNames = new ArrayList<>();
	public RenderPipelineBuilderWrapper withSampler(String name) throws IllegalArgumentException
	{
		#if MC_VER <= MC_26_1_2
		this.blazePipelineBuilder.withSampler(name);
		#else
		samplerNames.add(name);
		#endif
		
		return this;
	}
	
	private final ArrayList<String> uniformBufferNames = new ArrayList<>();
	public RenderPipelineBuilderWrapper withUniformBuffer(String name) throws IllegalArgumentException
	{
		#if MC_VER <= MC_26_1_2
		this.blazePipelineBuilder.withUniform(name, UniformType.UNIFORM_BUFFER);
		#else
		uniformBufferNames.add(name);
		#endif
		
		return this;
	}
	
	private VertexFormat vertexFormat = null;
	public RenderPipelineBuilderWrapper withVertexFormat(VertexFormat vertexFormat)
	{
		this.vertexFormat = vertexFormat;
		return this;
	}
	
	private EDhVertexMode vertexMode = null;
	public RenderPipelineBuilderWrapper withVertexMode(EDhVertexMode vertexMode)
	{
		this.vertexMode = vertexMode;
		return this;
	}
	
	public RenderPipelineBuilderWrapper withVertexShader(String scriptResourcePath) { return this.withShader(EDhShaderType.VERTEX, scriptResourcePath); }
	public RenderPipelineBuilderWrapper withFragmentShader(String scriptResourcePath) { return this.withShader(EDhShaderType.FRAGMENT, scriptResourcePath); }
	private RenderPipelineBuilderWrapper withShader(EDhShaderType shaderType, String scriptResourcePath)
	{
		String fullShaderResourcePath = SHADER_RESOURCE_FOLDER + scriptResourcePath + shaderType.fileExtension;
		
		// confirm the shader file exists
		try (InputStream scriptListInputStream = CLASS_LOADER.getResourceAsStream(fullShaderResourcePath))
		{
			if (scriptListInputStream == null)
			{
				throw new NullPointerException("Failed to find the SQL Script list file [" + fullShaderResourcePath + "], no auto update scripts can be run.");
			}
		}
		catch (IOException e)
		{
			// shouldn't happen, but just in case
			throw new RuntimeException("Unexpected issue closing resource stream for shader type: ["+shaderType+"] at: ["+fullShaderResourcePath+"], error: ["+e.getMessage()+"].", e);
		}
		
		
		
		if (shaderType == EDhShaderType.VERTEX)
		{
			this.blazePipelineBuilder.withVertexShader(Identifier.parse(NAME_PREFIX + scriptResourcePath));
		}
		else
		{
			this.blazePipelineBuilder.withFragmentShader(Identifier.parse(NAME_PREFIX + scriptResourcePath));
		}
		
		return this;
	}
	
	//endregion
	
	
	
	//=====//
	// end //
	//=====//
	//region
	
	public RenderPipeline build() throws UnsupportedOperationException
	{
		// depth/color
		{
			#if MC_VER <= MC_1_21_11
			
			this.blazePipelineBuilder.withDepthWrite(this.writeDepth);
			this.blazePipelineBuilder.withColorWrite(this.writeColor);
			
			if (this.blendFunction != null)
			{
				this.blazePipelineBuilder.withBlend(this.blendFunction);
			}
			else
			{
				this.blazePipelineBuilder.withoutBlend();
			}
			
			DepthTestFunction depthTestFunction;
			switch (this.depthTest)
			{
				case NONE:
					depthTestFunction = DepthTestFunction.NO_DEPTH_TEST;
					break;
				case LESS:
					depthTestFunction = DepthTestFunction.LESS_DEPTH_TEST;
					break;
				
				default:
					throw new UnsupportedOperationException("No depth test defined for type ["+this.depthTest+"].");
			}
			this.blazePipelineBuilder.withDepthTestFunction(depthTestFunction);
			
			#else
			
			CompareOp compareOp;
			switch (this.depthTest)
			{
				case NONE:
					compareOp = CompareOp.ALWAYS_PASS;
					break;
				case LESS:
					compareOp = CompareOp.LESS_THAN;
					break;
				case GREATER:
					compareOp = CompareOp.GREATER_THAN;
					break;
				
				default:
					throw new UnsupportedOperationException("No depth test defined for type ["+this.depthTest+"].");
			}
			this.blazePipelineBuilder.withDepthStencilState(new DepthStencilState(compareOp, this.writeDepth));
			
			#if MC_VER <= MC_26_1_2
			this.blazePipelineBuilder.withColorTargetState(
				new ColorTargetState(
					Optional.ofNullable(this.blendFunction), 
					this.writeColor ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE
				)
			);
			#else
			this.blazePipelineBuilder.withColorTargetState(
				new ColorTargetState(
					Optional.ofNullable(this.blendFunction),
					GpuFormat.RGBA8_UNORM,
					this.writeColor ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE
				)
			);
			#endif
			
			#endif
		}
		
		
		// vertex format
		{
			#if MC_VER <= MC_26_1_2
			VertexFormat.Mode blazeVertexMode;
			switch (this.vertexMode)
			{
				case TRIANGLES:
					blazeVertexMode = VertexFormat.Mode.TRIANGLES;
					break;
				case TRIANGLE_FAN:
					blazeVertexMode = VertexFormat.Mode.TRIANGLE_FAN;
					break;
				case LINES:
					blazeVertexMode = VertexFormat.Mode.DEBUG_LINES;
					break;
				
				default:
					throw new UnsupportedOperationException("No vertex mode defined for type ["+this.vertexMode+"].");
			}
			
			this.blazePipelineBuilder.withVertexFormat(vertexFormat, blazeVertexMode);
			#else
			
			PrimitiveTopology primitiveTopology;
			switch (this.vertexMode)
			{
				case TRIANGLES:
					primitiveTopology = PrimitiveTopology.TRIANGLES;
					break;
				case TRIANGLE_FAN:
					primitiveTopology = PrimitiveTopology.TRIANGLE_FAN;
					break;
				case LINES:
					primitiveTopology = PrimitiveTopology.DEBUG_LINES;
					break;
				
				default:
					throw new UnsupportedOperationException("No PrimitiveTopology defined for type ["+this.vertexMode+"].");
			}
			
			this.blazePipelineBuilder.withVertexBinding(0, vertexFormat);
			this.blazePipelineBuilder.withPrimitiveTopology(primitiveTopology);
			
			#endif
		}
		
		
		// uniform buffers
		{
			#if MC_VER <= MC_26_1_2
			// handled before this point
			#else
			
			BindGroupLayout.Builder bindGroupBuilder = BindGroupLayout.builder();
			
			for (String name : this.samplerNames)
			{
				#if MC_VER <= MC_26_2_0
				bindGroupBuilder.withSampler(name);
				#else
				bindGroupBuilder.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
				#endif
			}
			
			for (String name : this.uniformBufferNames)
			{
				bindGroupBuilder.withUniform(name, UniformType.UNIFORM_BUFFER);
			}
			
			BindGroupLayout bindGroup = bindGroupBuilder.build();
			this.blazePipelineBuilder.withBindGroupLayout(bindGroup);
			#endif
		}
		
		
		return this.blazePipelineBuilder.build();
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static boolean isValidIdentifier(String identifier)
	{
		for (int i = 0; i < identifier.length(); i++)
		{
			char ch = identifier.charAt(i);
			if (!isValidNamespaceChar(ch))
			{
				return false;
			}
		}
		
		return true;
	}
	private static boolean isValidNamespaceChar(final char ch)
	{
		return ch == '_' 
			|| ch == '-' 
			// only lower case characters
			|| (ch >= 'a' && ch <= 'z') 
			|| (ch >= '0' && ch <= '9') 
			|| ch == '.';
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	public enum EDhPolygonMode
	{
		FILL,
		WIREFRAME;
	}
	
	public enum EDhVertexMode
	{
		TRIANGLES,
		TRIANGLE_FAN,
		LINES;
	}
	
	public enum EDhDepthTest
	{
		NONE,
		GREATER,
		LESS;
	}
	
	private enum EDhShaderType
	{
		FRAGMENT(".fsh"),
		VERTEX(".vsh");
		
		
		public final String fileExtension;
		
		EDhShaderType(String fileExtension)
		{
			this.fileExtension = fileExtension;
		}
	}
	
	
	//endregion
	
	
	
}
#endif