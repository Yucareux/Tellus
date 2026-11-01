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

package com.seibel.distanthorizons.core.generation.tasks;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

import java.util.concurrent.CompletableFuture;

/**
 * @see DataSourceRetrievalResult
 */
public final class DataSourceRetrievalTask
{
	public final long pos;
	/** 
	 * Usually the same as {@link DataSourceRetrievalTask#pos}, but
	 * can differ if the task needs something different.
	 */
	public final byte requestDetailLevel;
	public final int widthInChunks;
	
	public final CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
	
	
	
	//=============//
 	// constructor //
	//=============//
	
	public DataSourceRetrievalTask(long pos, byte dataDetail)
	{
		this.pos = pos;
		this.requestDetailLevel = dataDetail;
		
		int widthInBlocks = BitShiftUtil.powerOfTwo(DhSectionPos.getDetailLevel(this.pos));
		this.widthInChunks = widthInBlocks / LodUtil.CHUNK_WIDTH; 
	}
	
	
	
}
