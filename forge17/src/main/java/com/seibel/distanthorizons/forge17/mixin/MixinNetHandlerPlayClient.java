package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.seibel.distanthorizons.core.api.internal.ClientApi;

@Mixin(NetHandlerPlayClient.class)
public class MixinNetHandlerPlayClient 
{

    @Inject(method = "handleJoinGame", at = @At("RETURN"))
    private void connect(S01PacketJoinGame packetIn, CallbackInfo ci) 
    { ClientApi.INSTANCE.onClientOnlyConnected(); }

    @Inject(method = "cleanup", at = @At("RETURN"))
    private void disconnect(CallbackInfo ci) 
    { ClientApi.INSTANCE.onClientOnlyDisconnected(); }
	
	
	
}
