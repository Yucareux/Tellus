package com.seibel.distanthorizons.common.commands;

import com.seibel.distanthorizons.core.logging.f3.F3Screen;

#if MC_VER <= MC_1_12_2
import net.minecraft.command.ICommandSender;
#else
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import static net.minecraft.commands.Commands.literal;
#endif

import java.util.ArrayList;
import java.util.List;


public class DebugCommand extends AbstractDhCommand
{
	private static String getDebugString()
	{
		List<String> lines = new ArrayList<>();
		F3Screen.addStringToDisplay(lines);
		return String.join("\n", lines);
	}
	
	#if MC_VER <= MC_1_12_2
	public void execute(ICommandSender sender)
	{ sendMessage(sender, getDebugString()); }
	#else
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
      return literal("debug")
            .executes(c -> {
                return this.sendSuccessResponse(c, getDebugString(), false);
            });
	}
	#endif
	
}
