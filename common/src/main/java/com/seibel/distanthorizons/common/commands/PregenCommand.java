package com.seibel.distanthorizons.common.commands;

import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.generation.PregenManager;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.world.DhServerWorld;

#if MC_VER <= MC_1_12_2
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
#else
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
#endif

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;


public class PregenCommand extends AbstractDhCommand
{
	private PregenManager getPregenManager()
	{
		DhServerWorld world = (DhServerWorld) Objects.requireNonNull(SharedApi.getAbstractDhWorld());
		return world.getPregenManager();
	}
	
	#if MC_VER <= MC_1_12_2
	public void execute(MinecraftServer server, ICommandSender sender, String[] args)
	{
		if (args.length < 2)
		{
			sendMessage(sender, "Usage: /dh pregen <status|start|stop>");
			return;
		}
		
		switch (args[1])
		{
			case "status":
			{
				String statusString = this.getPregenManager().getStatusString();
				sendMessage(sender, statusString != null ? statusString : "Pregen is not running");
				break;
			}
			case "start":
			{
				if (args.length < 5)
				{
					sendMessage(sender, "Usage: /dh pregen start <dimension> <x> <z> <chunkRadius>");
					return;
				}
				
				try
				{
					String dimensionName = args[2];
					int x = Integer.parseInt(args[3]);
					int z = Integer.parseInt(args[4]);
					int chunkRadius = args.length >= 6 ? Integer.parseInt(args[5]) : 32;
					
					// find the world by dimension name
					WorldServer world = null;
					#if MC_VER <= MC_1_7_10
					for (WorldServer possibleWorld : server.worldServers)
					#else
					for (WorldServer possibleWorld : server.worlds)
					#endif
					{
						#if MC_VER <= MC_1_7_10
						if (possibleWorld.provider.getDimensionName().equals(dimensionName) 
							|| String.valueOf(possibleWorld.provider.dimensionId).equals(dimensionName))
						#else
						if (possibleWorld.provider.getDimensionType().getName().equals(dimensionName))
						#endif
						{
							world = possibleWorld;
							break;
						}
					}
					
					if (world == null)
					{
						sendMessage(sender, "Unknown dimension: " + dimensionName);
						return;
					}
					
					sendMessage(sender, "Starting pregen. Progress will be in the server console.");
					
					final ICommandSender finalSender = sender;
					CompletableFuture<Void> future = this.getPregenManager().startPregen(
						ServerLevelWrapper.getWrapper(world),
						new DhBlockPos2D(x, z),
						chunkRadius
					);
					
					future.whenComplete((result, throwable) -> {
						if (throwable instanceof CancellationException)
						{
							sendMessage(finalSender, "Pregen is cancelled");
							return;
						}
						else if (throwable != null)
						{
							sendMessage(finalSender, "Pregen failed: " + throwable.getMessage());
							return;
						}
						sendMessage(finalSender, "Pregen is complete");
					});
				}
				catch (NumberFormatException e)
				{
					sendMessage(sender, "Invalid number format");
				}
				break;
			}
			case "stop":
			{
				CompletableFuture<Void> runningPregen = this.getPregenManager().getRunningPregen();
				if (runningPregen == null)
				{
					sendMessage(sender, "Pregen is not running");
					return;
				}
				runningPregen.cancel(true);
				break;
			}
			default:
				sendMessage(sender, "Unknown subcommand: " + args[1]);
		}
	}
    #else
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		LiteralArgumentBuilder<CommandSourceStack> statusCommand = literal("status")
				.executes(this::pregenStatus);
		
		LiteralArgumentBuilder<CommandSourceStack> startCommand = literal("start")
				.then(argument("dimension", DimensionArgument.dimension())
						.then(argument("origin", ColumnPosArgument.columnPos())
								.then(argument("chunkRadius", integer(32))
										.executes(this::pregenStart))));
		
		LiteralArgumentBuilder<CommandSourceStack> stopCommand = literal("stop")
				.executes(this::pregenStop);
		
		return literal("pregen")
				.then(statusCommand)
				.then(startCommand)
				.then(stopCommand);
	}
	
	
	private int pregenStatus(CommandContext<CommandSourceStack> c)
	{
		String statusString = this.getPregenManager().getStatusString();
		//noinspection ReplaceNullCheck
		if (statusString != null)
		{
			return this.sendSuccessResponse(c, statusString, false);
		}
		else
		{
			return this.sendSuccessResponse(c, "Pregen is not running", false);
		}
	}
	
	private int pregenStart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException
	{
		this.sendSuccessResponse(c, "Starting pregen. Progress will be in the server console.", true);
		
		ServerLevel level = DimensionArgument.getDimension(c, "dimension");
		ColumnPos origin = ColumnPosArgument.getColumnPos(c, "origin");
		int chunkRadius = getInteger(c, "chunkRadius");
		
		CompletableFuture<Void> future = this.getPregenManager().startPregen(
				ServerLevelWrapper.getWrapper(level),
				new DhBlockPos2D(#if MC_VER >= MC_1_19_2 origin.x(), origin.z() #else origin.x, origin.z #endif),
				chunkRadius
		);
		
		future.whenComplete((result, throwable) -> {
			if (throwable instanceof CancellationException)
			{
				this.sendSuccessResponse(c, "Pregen is cancelled", true);
				return;
			}
			else if (throwable != null)
			{
				this.sendFailureResponse(c, "Pregen failed: " + throwable.getMessage() + "\n Check the logs for more details.");
				return;
			}
			
			this.sendSuccessResponse(c, "Pregen is complete", true);
		});
		
		return 1;
	}
	
	private int pregenStop(CommandContext<CommandSourceStack> c)
	{
		CompletableFuture<Void> runningPregen = this.getPregenManager().getRunningPregen();
		if (runningPregen == null)
		{
			return this.sendFailureResponse(c, "Pregen is not running");
		}
		
		runningPregen.cancel(true);
		return 1;
	}
	#endif
}
