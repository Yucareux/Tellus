package com.seibel.distanthorizons.common.commands;

import com.seibel.distanthorizons.api.enums.config.DisallowSelectingViaConfigGui;
import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.config.types.AbstractConfigBase;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.util.AnnotationUtil;

#if MC_VER <= MC_1_12_2
import net.minecraft.command.ICommandSender;
#else
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;

import static com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
#endif

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;



/**
 * Command for managing config.
 */
public class ConfigCommand extends AbstractDhCommand
{
	private static boolean isSelectableEnumValue(ConfigEntry<?> configEntry, Enum<?> enumValue)
	{
		if (AnnotationUtil.doesEnumHaveAnnotation(enumValue, DisallowSelectingViaConfigGui.class))
		{
			return false;
		}

		ConfigEntry.IShowEnumOptionFunc showEnumOptionFunc = configEntry.getShowEnumOptionFunc();
		return showEnumOptionFunc == null || showEnumOptionFunc.shouldShowEnum(enumValue);
	}

	#if MC_VER <= MC_1_12_2
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void setConfigValue(ConfigEntry<?> configEntry, String value)
	{
		Class<?> type = configEntry.getType();
		
		if (type == Boolean.class)       ((ConfigEntry) configEntry).set(Boolean.parseBoolean(value));
		else if (type == Integer.class)  ((ConfigEntry) configEntry).set(Integer.parseInt(value));
		else if (type == Double.class)   ((ConfigEntry) configEntry).set(Double.parseDouble(value));
		else if (type == Float.class)    ((ConfigEntry) configEntry).set(Float.parseFloat(value));
		else if (type == Long.class)     ((ConfigEntry) configEntry).set(Long.parseLong(value));
		else if (type == String.class)   ((ConfigEntry) configEntry).set(value);
		else if (type.isEnum())
		{
			Enum<?> enumValue = Enum.valueOf((Class<Enum>) type, value);
			if (!isSelectableEnumValue(configEntry, enumValue))
			{
				throw new IllegalArgumentException("This enum value is not available on this Minecraft version.");
			}
			((ConfigEntry) configEntry).set(enumValue);
		}
		else throw new RuntimeException("Unsupported config type: " + type.getSimpleName());
	}
	
	public void execute(ICommandSender sender, String[] args)
	{
		if (args.length < 2)
		{
			sendMessage(sender, "Usage: /dh config <name> [value]");
			return;
		}
		
		String configName = args[1];
		AbstractConfigBase<?> found = null;
		for (AbstractConfigBase<?> entry : ConfigHandler.INSTANCE.configBaseList)
		{
			if (entry instanceof ConfigEntry && configName.equals(((ConfigEntry<?>) entry).getChatCommandName()))
			{
				found = entry;
				break;
			}
		}
		
		if (found == null)
		{
			sendMessage(sender, "Unknown config: " + configName);
			return;
		}
		
		ConfigEntry<?> configEntry = (ConfigEntry<?>) found;
		if (args.length == 2)
		{
			sendMessage(sender, "Current value of " + configName + " is " + configEntry.get());
		}
		else
		{
			String value = args[2];
			try
			{
				setConfigValue(configEntry, value);
				sendMessage(sender, "Changed " + configName + " to " + value);
			}
			catch (Exception e)
			{
				sendMessage(sender, "Invalid value: " + value);
			}
		}
	}
	#else
	
	private static final List<CommandArgumentData<?>> commandArguments = Arrays.asList(
			new CommandArgumentData<>(Integer.class, configEntry -> integer(configEntry.getMin(), configEntry.getMax()), IntegerArgumentType::getInteger),
			new CommandArgumentData<>(Double.class, configEntry -> doubleArg(configEntry.getMin(), configEntry.getMax()), DoubleArgumentType::getDouble),
			new CommandArgumentData<>(Boolean.class, BoolArgumentType::bool, BoolArgumentType::getBool),
			new CommandArgumentData<>(String.class, StringArgumentType::string, StringArgumentType::getString)
	);
	
	/**
	 * Builds a command tree.
	 */
	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		LiteralArgumentBuilder<CommandSourceStack> builder = literal("config");
		HashSet<String> addedCommands = new HashSet<>();
		
		for (AbstractConfigBase<?> type : ConfigHandler.INSTANCE.configBaseList)
		{
			// Skip non-config entries
			if (!(type instanceof ConfigEntry))
			{
				continue;
			}
			
			//noinspection PatternVariableCanBeUsed
			ConfigEntry configEntry = (ConfigEntry) type;
			if (configEntry.getChatCommandName() == null)
			{
				continue;
			}
			
			if (!addedCommands.add(configEntry.getChatCommandName()))
			{
				throw new IllegalStateException("Duplicate command name: " + configEntry.getChatCommandName());
			}
			
			LiteralArgumentBuilder<CommandSourceStack> subcommand = literal(configEntry.getChatCommandName())
					.executes(commandContext -> this.sendSuccessResponse(commandContext,
							"\n" +
									"Description of §l" + configEntry.getChatCommandName() + "§r:\n" +
									"§o" + configEntry.getComment().trim() + "§r\n" +
									"§7Config file name: §f" + configEntry.name + "§7, category: §f" + configEntry.category + "\n" +
									"\n" +
									"Current value of " + configEntry.getChatCommandName() + " is §n" + configEntry.get() + "§r",
							false
					));
			
			ToIntBiFunction<CommandContext<CommandSourceStack>, Object> updateConfigValue = (commandContext, value) -> {
				configEntry.set(value);
				return this.sendSuccessResponse(commandContext, "Changed the value of [" + configEntry.getChatCommandName() + "] to [" + value + "]", true);
			};
			
			// Enum type needs a special case since enums aren't represented by existing argument type
			// and need literals for each individual value
			if (Enum.class.isAssignableFrom(configEntry.getType()))
			{
				for (Object choice : configEntry.getType().getEnumConstants())
				{
					if (!isSelectableEnumValue(configEntry, (Enum<?>) choice))
					{
						continue;
					}

					subcommand.then(
							literal(choice.toString())
									.executes(c -> updateConfigValue.applyAsInt(c, choice))
					);
				}
			}
			else
			{
				boolean setterAdded = false;
				for (CommandArgumentData<?> commandArgumentData : commandArguments)
				{
					if (!commandArgumentData.argumentClass.isAssignableFrom(configEntry.getType()))
					{
						continue;
					}
					
					subcommand.then(argument("value", commandArgumentData.getArgumentType(configEntry))
							.executes(c -> updateConfigValue.applyAsInt(c, commandArgumentData.getValue(c, "value"))));
					
					setterAdded = true;
					break;
				}
				
				if (!setterAdded)
				{
					throw new RuntimeException("Config type of " + type.getName() + " is not supported: " + configEntry.getType().getSimpleName());
				}
			}
			
			builder.then(subcommand);
		}
		
		return builder;
	}
	
	
	
	private static class CommandArgumentData<T>
	{
		public final Class<T> argumentClass;
		public final Function<ConfigEntry<T>, ArgumentType<T>> argumentTypeFunction;
		private final BiFunction<CommandContext<CommandSourceStack>, String, T> valueGetter;
		
		public CommandArgumentData(Class<T> argumentClass, Supplier<ArgumentType<T>> argumentTypeSupplier, BiFunction<CommandContext<CommandSourceStack>, String, T> valueGetter)
		{
			this(argumentClass, configEntry -> argumentTypeSupplier.get(), valueGetter);
		}
		public CommandArgumentData(Class<T> argumentClass, Function<ConfigEntry<T>, ArgumentType<T>> argumentTypeFunction, BiFunction<CommandContext<CommandSourceStack>, String, T> valueGetter)
		{
			this.argumentClass = argumentClass;
			this.argumentTypeFunction = argumentTypeFunction;
			this.valueGetter = valueGetter;
		}
		
		public ArgumentType<T> getArgumentType(ConfigEntry<T> configEntry)
		{
			return this.argumentTypeFunction.apply(configEntry);
		}
		
		public T getValue(CommandContext<CommandSourceStack> commandContext, String argumentName)
		{
			return this.valueGetter.apply(commandContext, argumentName);
		}
		
	}
	#endif
	
}
