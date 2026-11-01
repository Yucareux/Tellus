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

package com.seibel.distanthorizons.forge112.mixins.client;

import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient
{
	@Shadow
	private WorldClient world;
	
	@Shadow
	private Minecraft client;
	
	@Inject(method = "handleJoinGame", at = @At("RETURN"))
	private void onHandleJoinGameEnd(SPacketJoinGame packetIn, CallbackInfo ci)
	{
		ClientApi.INSTANCE.onClientOnlyConnected();
	}
	
	@Inject(method = "onDisconnect", at = @At("RETURN"))
	private void onDisconnect(ITextComponent reason, CallbackInfo ci)
	{
		ClientApi.INSTANCE.onClientOnlyDisconnected();
	}
	
	@Inject(method = "handleChunkData", at = @At("TAIL"))
	private void onChunkDataHandled(SPacketChunkData packetIn, CallbackInfo ci)
	{
		if (!packetIn.isFullChunk())
		{
			return;
		}
		
		if (client.getCurrentServerData() == null || client.isSingleplayer())
		{
			return;
		}
		
		Chunk chunk = this.world.getChunk(packetIn.getChunkX(), packetIn.getChunkZ());
		
		ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(this.world);
		SharedApi.INSTANCE.applyChunkUpdate(
			new ChunkWrapper(chunk, wrappedLevel),
			wrappedLevel,
			true
		);
	}
}
