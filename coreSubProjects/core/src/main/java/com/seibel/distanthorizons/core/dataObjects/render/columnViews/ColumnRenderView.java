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

package com.seibel.distanthorizons.core.dataObjects.render.columnViews;


import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.util.RenderDataPointReducingList;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Maps to part of a {@link ColumnRenderSource} for easier handling.
 * 
 * @see ColumnRenderSource
 */
public final class ColumnRenderView implements AutoCloseable
{
	private static final ConcurrentLinkedQueue<ColumnRenderView> COLUMN_VIEW_POOL = new ConcurrentLinkedQueue<>();
	
	
	/** @see ColumnRenderSource#renderDataContainer */
	public LongArrayList data;
	/** @see ColumnRenderSource#voidRenderDataContainer */
	public LongArrayList voidData;
	
	/** 
	 * How many data points are currently being represented by this view. <br>
	 * Will be equal to or less than {@link ColumnRenderView#maxVerticalSliceCount}.
	 */
	public int size;
	/** 
	 * Vertical size in data points. <Br>
	 * Can be 0 if this column was created for an empty data source.
	 * @see EDhApiVerticalQuality#calculateMaxNumberOfVerticalSlicesAtDetailLevel(byte)
	 */
	public int maxVerticalSliceCount;
	
	/**
	 * Where the relative starting index is in the {@link ColumnRenderView#data} array
	 * if this view is representing part of a {@link ColumnRenderSource}.
	 */
	public int dataOffset;
	/** 
	 * only one void data point is present per column view
	 * since this column is only a single datapoint wide.
	 */
	public int voidDataOffset;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private ColumnRenderView() { }
	
	/** 
	 * returns an un-populated view. <br>
	 * {@link ColumnRenderView#populate(LongArrayList, LongArrayList, int, int, int, int)} must be called before the
	 * view can be used.s
	 */
	public static ColumnRenderView getPooled() { return getPooled(null, null, 0, 0, 0, 0); }
	public static ColumnRenderView getPooled(
		LongArrayList data, LongArrayList voidData, 
		int size, 
		int offset, int voidOffset, 
		int maxVerticalSliceCount) throws IllegalArgumentException
	{
		// try getting an existing pooled object first
		ColumnRenderView view = COLUMN_VIEW_POOL.poll();
		if (view == null)
		{
			// no pooled object 
			view = new ColumnRenderView();
		}
		
		// data will be null if the object will be populated at a later date
		if (data != null)
		{
			view.populate(
				data, voidData, 
				size, 
				offset, voidOffset, 
				maxVerticalSliceCount);
		}
		
		return view;
	}
	
	/**
	 * Mutates this object so the necessary data is visible.  
	 * @throws IllegalArgumentException if the offset is greater than the data's size 
	 */
	public void populate(
		LongArrayList data, LongArrayList voidData,
		int size, 
		int offset, int voidDataOffset, 
		int maxVerticalSliceCount) throws IllegalArgumentException
	{
		this.data = data;
		this.voidData = voidData;
		this.size = size;
		this.dataOffset = offset;
		this.voidDataOffset = voidDataOffset;
		this.maxVerticalSliceCount = maxVerticalSliceCount;
		
		if (this.data.size() < this.dataOffset)
		{
			throw new IllegalArgumentException("data size ["+this.data.size()+"] is shorter than offset ["+this.dataOffset +"].");
		}
		
		if (this.voidData.size() < this.voidDataOffset)
		{
			throw new IllegalArgumentException("void data size ["+this.voidData.size()+"] is shorter than offset ["+this.voidDataOffset +"].");
		}
	}
	
	public void clear()
	{
		this.data = null;
		this.voidData = null;
		this.size = 0;
		this.dataOffset = 0;
		this.voidDataOffset = 0;
		this.maxVerticalSliceCount = 0;
	}
	
	//endregion
	
	
	
	//=====================//
	// getters and setters //
	//=====================//
	//region
	
	public long get(int index) 
	{
		try
		{
			return this.data.getLong(index + this.dataOffset);
		}
		catch (IndexOutOfBoundsException e)
		{
			// we can fairly confidently say this is a concurrent exception over an actual
			// index out of bounds, since we're generally iterating over the whole
			// array any time we use this getter.
			throw new ConcurrentModificationException("Potential concurrent modification detected. Make sure the parent ColumnRenderSource isn't being closed before the ColumnRenderView processing is complete.", e);
		}
	}
	public void set(int index, long value) { this.data.set(index + this.dataOffset, value); }
	
	/** gets the void datapoint below this render column */
	public long getVoid()
	{
		try
		{
			return this.voidData.getLong(this.voidDataOffset);
		}
		catch (IndexOutOfBoundsException e)
		{
			// we can fairly confidently say this is a concurrent exception over an actual
			// index out of bounds, since we're generally iterating over the whole
			// array any time we use this getter.
			throw new ConcurrentModificationException("Potential concurrent modification detected. Make sure the parent ColumnRenderSource isn't being closed before the ColumnRenderView processing is complete.", e);
		}
	}
	/** sets the void datapoint below this render column */
	public void setVoid(long value) { this.voidData.set(this.voidDataOffset, value); }
	
	//endregion
	
	
	
	//=========//
	// subview //
	//=========//
	//region
	
	/** should be called in a try-finally block for automatic cleanup */
	public ColumnRenderView subView(int dataIndexStart, int dataCount)
	{
		return ColumnRenderView.getPooled(
			this.data, this.voidData,
			dataCount * this.maxVerticalSliceCount,
			this.dataOffset + dataIndexStart * this.maxVerticalSliceCount,
			this.voidDataOffset + dataIndexStart,
			this.maxVerticalSliceCount);
	}
	
	
	/** can be used to determine sub-view starting indexes */
	public int subViewCount() { return (this.maxVerticalSliceCount != 0) ? (this.size / this.maxVerticalSliceCount) : 0; }
	
	//endregion
	
	
	
	//======================//
	// change vertical size //
	//======================//
	//region
	
	public void changeVerticalSizeFrom(ColumnRenderView source, RenderDataPointReducingList reducingList)
	{
		if (this.subViewCount() != source.subViewCount())
		{
			throw new IllegalArgumentException("Cannot copy and resize to views with different dataCounts");
		}
		
		if (this.maxVerticalSliceCount >= source.maxVerticalSliceCount)
		{
			this.copyFrom(source, 0);
		}
		else
		{
			for (int i = 0; i < this.subViewCount(); i++)
			{
				try(ColumnRenderView sourceSubView = source.subView(i, 1);
					ColumnRenderView thisSubView = this.subView(i, 1))
				{
					mergeMultiData(sourceSubView, reducingList, thisSubView);
				}
			}
		}
		
		// copy over the void data too
		this.setVoid(source.getVoid());
	}
	private void copyFrom(ColumnRenderView source, int outputDataIndexOffset)
	{
		if (source.maxVerticalSliceCount > this.maxVerticalSliceCount)
		{
			throw new IllegalArgumentException("source verticalSize must be <= self's verticalSize to copy");
		}
		else if (source.subViewCount() + outputDataIndexOffset > this.subViewCount())
		{
			throw new IllegalArgumentException("dataIndexStart + source.dataCount() must be <= self.dataCount() to copy");
		}
		else if (source.maxVerticalSliceCount != this.maxVerticalSliceCount)
		{
			for (int i = 0; i < source.subViewCount(); i++)
			{
				int outputOffset = this.dataOffset + (outputDataIndexOffset * this.maxVerticalSliceCount) + (i * this.maxVerticalSliceCount);
				try(ColumnRenderView subView = source.subView(i, 1))
				{
					subView.copyTo(this.data.elements(), outputOffset, source.maxVerticalSliceCount);
					Arrays.fill(this.data.elements(),
						outputOffset + source.maxVerticalSliceCount,
						outputOffset + this.maxVerticalSliceCount,
						0);
				}
			}
		}
		else
		{
			source.copyTo(this.data.elements(), this.dataOffset + outputDataIndexOffset * this.maxVerticalSliceCount, source.size);
		}
	}
	private void copyTo(long[] target, int offset, int size) { System.arraycopy(this.data.elements(), this.dataOffset, target, offset, size); }
	/**
	 * This method merge column of multiple data together
	 *
	 * @param sourceData one or more columns of data
	 * @param output one column of space for the result to be written to
	 */
	private static void mergeMultiData(ColumnRenderView sourceData, RenderDataPointReducingList reducingList, ColumnRenderView output)
	{
		int target = output.maxVerticalSliceCount;
		if (target <= 0)
		{
			// I expect this to never be the case,
			// but RenderDataPointReducingList handles it sanely,
			// so I might as well handle it sanely here too.
			output.fill(RenderDataPointUtil.EMPTY_DATA);
		}
		else if (target == 1)
		{
			output.set(0, RenderDataPointReducingList.reduceToOne(sourceData));
			for (int index = 1, size = output.size; index < size; index++)
			{
				output.set(index, RenderDataPointUtil.EMPTY_DATA);
			}
		}
		else
		{
			reducingList.populate(sourceData);
			reducingList.reduce(output.maxVerticalSliceCount);
			reducingList.copyTo(output);
		}
	}
	private void fill(long value) { Arrays.fill(this.data.elements(), this.dataOffset, this.dataOffset + this.size, value); }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("S:").append(this.size);
		sb.append(" V:").append(this.maxVerticalSliceCount);
		sb.append(" O:").append(this.dataOffset);
		sb.append(" A:").append(this.voidDataOffset);
		sb.append(" = ").append(this.getVoid());
		
		sb.append(" [");
		for (int i = 0; i < this.size; i++)
		{
			sb.append(RenderDataPointUtil.toString(this.data.getLong(this.dataOffset + i)));
			if (i < this.size - 1)
			{
				sb.append(",\n");
			}
		}
		sb.append("]");
		
		return sb.toString();
	}
	
	@Override
	public void close() 
	{
		// no validation is done to make sure this object is only added to the pool once
		// please only use this object in a try-finally so the close is handled implicitly
		COLUMN_VIEW_POOL.add(this); 
	}
	
	//endregion
	
	
	
}