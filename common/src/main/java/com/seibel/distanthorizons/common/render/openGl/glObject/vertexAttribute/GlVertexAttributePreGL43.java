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

package com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.TreeSet;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.lwjgl.GL15;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;


public final class GlVertexAttributePreGL43 extends GlAbstractVertexAttribute
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logRendererGLEventToFile)
			.chatLevelConfig(Config.Common.Logging.logRendererGLEventToChat)
			.build();
	
	
	// I tried to use raw arrays as much as possible since those lookups
	// happen every frame, and the speed directly affects fps
	int strideSize = 0;
	int[][] bindingPointsToIndex;
	GlVertexPointer[] pointers;
	int[] pointersOffset;
	
	TreeMap<Integer, TreeSet<Integer>> bindingPointsToIndexBuilder;
	ArrayList<GlVertexPointer> pointersBuilder;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	/** This will bind the {@link GlAbstractVertexAttribute} */
	public GlVertexAttributePreGL43()
	{
		super(); // also bind AbstractVertexAttribute
		this.bindingPointsToIndexBuilder = new TreeMap<>();
		this.pointersBuilder = new ArrayList<>();
	}
	
	
	
	//=========//
	// binding //
	//=========//
	
	/** Requires both AbstractVertexAttribute and VertexBuffer to be bound */
	@Override
	public void bindBufferToAllBindingPoints(int buffer)
	{
		// make sure our buffer is bound before we bind the attributes
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
		
		for (int i = 0; i < this.pointers.length; i++)
		{
			LWJGL.glEnableVertexAttribArray(i);
		}
		
		for (int i = 0; i < this.pointers.length; i++)
		{
			GlVertexPointer pointer = this.pointers[i];
			if (pointer == null)
			{
				continue;
			}
			
			if (pointer.useInteger)
			{
				LWJGL.glVertexAttribIPointer(i, pointer.elementCount, pointer.glType,
						this.strideSize, this.pointersOffset[i]);
			}
			else
			{
				LWJGL.glVertexAttribPointer(i, pointer.elementCount, pointer.glType,
					pointer.normalized, this.strideSize, this.pointersOffset[i]);
			}
		}
	}
	
	/** Requires both AbstractVertexAttribute and VertexBuffer to be bound */
	@Override
	public void bindBufferToBindingPoint(int buffer, int bindingPoint)
	{
		// make sure our buffer is bound before we bind the attributes
		LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
		
		int[] bindingPointIndexes = this.bindingPointsToIndex[bindingPoint];
		
		for (int bindingPointIndex : bindingPointIndexes)
		{
			LWJGL.glEnableVertexAttribArray(bindingPointIndex);
		}
		
		for (int bindingPointIndex : bindingPointIndexes)
		{
			GlVertexPointer pointer = this.pointers[bindingPointIndex];
			if (pointer == null)
			{
				continue;
			}
			
			if (pointer.useInteger)
			{
				LWJGL.glVertexAttribIPointer(bindingPointIndex, pointer.elementCount, pointer.glType,
						this.strideSize, this.pointersOffset[bindingPointIndex]);
			}
			else
			{
				LWJGL.glVertexAttribPointer(bindingPointIndex, pointer.elementCount, pointer.glType,
						pointer.normalized, this.strideSize, this.pointersOffset[bindingPointIndex]);
			}
		}
		
	}
	
	
	
	//===========//
	// unbinding //
	//===========//
	
	/** Requires AbstractVertexAttribute to be bound */
	@Override
	public void unbindBuffersFromAllBindingPoint()
	{
		for (int i = 0; i < this.pointers.length; i++)
		{
			LWJGL.glDisableVertexAttribArray(i);
		}
	}
	
	/** Requires AbstractVertexAttribute to be bound */
	@Override
	public void unbindBuffersFromBindingPoint(int bindingPoint)
	{
		int[] bindingPointIndexes = this.bindingPointsToIndex[bindingPoint];
		for (int bindingPointIndex : bindingPointIndexes)
		{
			LWJGL.glDisableVertexAttribArray(bindingPointIndex);
		}
	}
	
	
	
	//==========================//
	// manual attribute setting //
	//==========================//
	
	/** Requires AbstractVertexAttribute to be bound */
	@Override
	public void setVertexAttribute(int bindingPoint, int attributeIndex, GlVertexPointer attribute)
	{
		TreeSet<Integer> intArray = this.bindingPointsToIndexBuilder.computeIfAbsent(bindingPoint, k -> new TreeSet<>());
		intArray.add(attributeIndex);
		
		while (this.pointersBuilder.size() <= attributeIndex)
		{
			// This is dumb, but ArrayList doesn't have a resize, And this code
			// should only be run when it's building the Vertex Attribute anyway.
			this.pointersBuilder.add(null);
		}
		this.pointersBuilder.set(attributeIndex, attribute);
	}
	
	
	
	//============//
	// validation //
	//============//
	
	/** Requires AbstractVertexAttribute to be bound */
	@Override
	public void completeAndCheck(int expectedStrideSize)
	{
		int maxBindPointNumber = this.bindingPointsToIndexBuilder.lastKey();
		this.bindingPointsToIndex = new int[maxBindPointNumber + 1][];
		
		this.bindingPointsToIndexBuilder.forEach((Integer i, TreeSet<Integer> set) -> 
		{
			this.bindingPointsToIndex[i] = new int[set.size()];
			Iterator<Integer> iter = set.iterator();
			for (int j = 0; j < set.size(); j++)
			{
				this.bindingPointsToIndex[i][j] = iter.next();
			}
		});
		
		this.pointers = this.pointersBuilder.toArray(new GlVertexPointer[this.pointersBuilder.size()]);
		this.pointersOffset = new int[this.pointers.length];
		this.pointersBuilder = null; // Release the builder
		this.bindingPointsToIndexBuilder = null; // Release the builder
		
		// Check if all pointers are valid
		int currentOffset = 0;
		for (int i = 0; i < this.pointers.length; i++)
		{
			GlVertexPointer pointer = this.pointers[i];
			if (pointer == null)
			{
				LOGGER.warn("Vertex Attribute index " + i + " is not set! No index should be skipped normally!");
				continue;
			}
			this.pointersOffset[i] = currentOffset;
			currentOffset += pointer.byteSize;
		}
		
		if (currentOffset != expectedStrideSize)
		{
			LOGGER.error("Vertex Attribute calculated stride size " + currentOffset +
					" does not match the provided expected stride size " + expectedStrideSize + "!");
			throw new IllegalArgumentException("Vertex Attribute Incorrect Format");
		}
		this.strideSize = currentOffset;
		LOGGER.info("Vertex Attribute (pre GL43) completed.");
		
		// Debug logging
		LOGGER.debug("AttributeIndex: ElementCount, glType, normalized, strideSize, offset");
		
		for (int i = 0; i < this.pointers.length; i++)
		{
			GlVertexPointer pointer = this.pointers[i];
			if (pointer == null)
			{
				LOGGER.debug(i + ": Null!!!!");
			}
			else
			{
				LOGGER.debug(i + ": " + pointer.elementCount + ", " +
						pointer.glType + ", " + pointer.normalized + ", " + this.strideSize + ", " + this.pointersOffset[i]);
			}
		}
		
	}
	
}
