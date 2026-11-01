package com.seibel.distanthorizons.common.commands;

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.network.messages.base.CodecCrashMessage;

#if MC_VER <= MC_1_12_2
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.core.world.IDhServerWorld;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
#else
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import static net.minecraft.commands.Commands.literal;
#endif


public class CrashCommand extends AbstractDhCommand
{
	#if MC_VER <= MC_1_12_2
	public void execute(ICommandSender sender, String[] args)
	{
		if (!(sender instanceof EntityPlayerMP))
		{
			sendMessage(sender, "This command can only be run by a player");
			return;
		}
		
		if (args.length < 2)
		{
			sendMessage(sender, "Usage: /dh crash <encode|decode>");
			return;
		}
		
		IDhServerWorld world = SharedApi.tryGetDhServerWorld();
		if (world == null)
		{
			return;
		}
		
		ServerPlayerState serverPlayerState = 
			world
			.getServerPlayerStateManager()
			.getConnectedPlayer(ServerPlayerWrapper.getWrapper((EntityPlayerMP) sender));
		
		if (serverPlayerState == null)
		{
			return;
		}
		
		switch (args[1])
		{
			case "encode":
				serverPlayerState.networkSession.sendMessage(new CodecCrashMessage(CodecCrashMessage.ECrashPhase.ENCODE));
				break;
			case "decode":
				serverPlayerState.networkSession.sendMessage(new CodecCrashMessage(CodecCrashMessage.ECrashPhase.DECODE));
				break;
			default:
				sendMessage(sender, "Usage: /dh crash <encode|decode>");
		}
	}
	#else
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		return literal("crash")
				.requires(this::isPlayerSource)
				.then(literal("encode")
						.executes(c -> {
							assert SharedApi.tryGetDhServerWorld() != null;
							
							ServerPlayerState serverPlayerState = SharedApi.tryGetDhServerWorld().getServerPlayerStateManager()
									.getConnectedPlayer(this.getSourcePlayer(c));
							if (serverPlayerState != null)
							{
								serverPlayerState.networkSession.sendMessage(new CodecCrashMessage(CodecCrashMessage.ECrashPhase.ENCODE));
							}
							return 1;
						}))
				.then(literal("decode")
						.executes(c -> {
							assert SharedApi.tryGetDhServerWorld() != null;
							
							ServerPlayerState serverPlayerState = SharedApi.tryGetDhServerWorld().getServerPlayerStateManager()
									.getConnectedPlayer(this.getSourcePlayer(c));
							if (serverPlayerState != null)
							{
								serverPlayerState.networkSession.sendMessage(new CodecCrashMessage(CodecCrashMessage.ECrashPhase.DECODE));
							}
							return 1;
						}));
	}
	#endif
	
}
