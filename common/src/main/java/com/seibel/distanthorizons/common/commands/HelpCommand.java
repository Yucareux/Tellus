package com.seibel.distanthorizons.common.commands;

import static com.seibel.distanthorizons.core.network.messages.MessageRegistry.DEBUG_CODEC_CRASH_MESSAGE;

#if MC_VER <= MC_1_12_2
import net.minecraft.command.ICommandSender;
#else
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import static net.minecraft.commands.Commands.literal;
#endif


public class HelpCommand extends AbstractDhCommand
{
	private static String getHelpString()
	{
		StringBuilder help = new StringBuilder();
		
		help.append("Distant Horizons commands:\n");
		help.append("§e/dh help§r - Show this command summary.\n");
		help.append("  Shape: /dh help\n");
		help.append("\n");
		help.append("§e/dh config <name> [value]§r - Read a config entry, or set it when a value is provided.\n");
		help.append("  Shapes: /dh config <name>; /dh config <name> <value>\n");
		help.append("  Config key names can be viewed with /help dh config.\n");
		help.append("  Use /dh config <name> without a value for the entry description and current value.\n");
		help.append("\n");
		help.append("§e/dh debug§r - Print Distant Horizons debug/F3 information.\n");
		help.append("  Shape: /dh debug\n");
		help.append("\n");
		help.append("§e/dh pregen status§r - Show current pregeneration progress.\n");
		help.append("  Shape: /dh pregen status\n");
		help.append("§e/dh pregen start <dimension> <x> <z> <chunkRadius>§r - Start pregeneration around a block position.\n");
		help.append("  Shape: /dh pregen start <dimension> <x> <z> <chunkRadius>\n");
		help.append("§e/dh pregen stop§r - Cancel the running pregeneration task.\n");
		help.append("  Shape: /dh pregen stop\n");
		
		if (DEBUG_CODEC_CRASH_MESSAGE)
		{
			help.append("\n");
			help.append("§e/dh crash <encode|decode>§r - Send a codec crash test message to the calling player.\n");
			help.append("  Shapes: /dh crash encode; /dh crash decode\n");
		}
		
		help.append("\n");
		help.append("Examples:\n");
		help.append("  /dh config generation.plan\n");
		help.append("  /dh config generation.plan SURFACE_THEN_CHUNKS\n");
		help.append("  /dh config generation.bounds.radiusInChunks 512\n");
		help.append("  /dh pregen status\n");
		#if MC_VER <= MC_1_12_2
		help.append("  /dh pregen start overworld 0 0 256");
		#else
		help.append("  /dh pregen start minecraft:overworld 0 0 256");
		#endif
		
		return help.toString();
	}
	
	#if MC_VER <= MC_1_12_2
	public void execute(ICommandSender sender)
	{
		sendMessage(sender, getHelpString());
	}
	#else
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		return literal("help")
				.executes(c -> this.sendSuccessResponse(c, getHelpString(), false));
	}
	#endif
	
}
