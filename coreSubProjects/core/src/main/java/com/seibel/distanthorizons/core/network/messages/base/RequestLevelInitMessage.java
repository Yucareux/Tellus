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

package com.seibel.distanthorizons.core.network.messages.base;

import com.seibel.distanthorizons.core.util.MoreObjects;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import io.netty.buffer.ByteBuf;

/** used for full DH support */
public class RequestLevelInitMessage extends AbstractNetworkMessage
{
	public String dimensionResourceLocation;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RequestLevelInitMessage() { }
	public RequestLevelInitMessage(String dimensionResourceLocation) { this.dimensionResourceLocation = dimensionResourceLocation; }
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out) { this.writeString(this.dimensionResourceLocation, out); }
	
	@Override
	public void decode(ByteBuf in) { this.dimensionResourceLocation = this.readString(in); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("dimensionResourceLocation", this.dimensionResourceLocation);
	}
	
}