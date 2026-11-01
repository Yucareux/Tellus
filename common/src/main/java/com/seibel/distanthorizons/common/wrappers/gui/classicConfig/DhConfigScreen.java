package com.seibel.distanthorizons.common.wrappers.gui.classicConfig;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.seibel.distanthorizons.api.enums.config.DisallowSelectingViaConfigGui;
import com.seibel.distanthorizons.common.wrappers.gui.*;
import com.seibel.distanthorizons.common.wrappers.gui.config.ConfigGuiInfo;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigHandler;
import com.seibel.distanthorizons.core.config.types.*;
import com.seibel.distanthorizons.common.wrappers.gui.updater.ChangelogScreen;
import com.seibel.distanthorizons.core.config.types.enums.EConfigCommentTextPosition;
import com.seibel.distanthorizons.core.config.types.enums.EConfigValidity;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.jar.updater.SelfUpdater;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.AnnotationUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.config.IConfigGui;
import com.seibel.distanthorizons.core.wrapperInterfaces.config.ILangWrapper;
import com.seibel.distanthorizons.coreapi.ModInfo;
import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_7_10
import net.minecraft.client.gui.*;
import net.minecraft.util.EnumChatFormatting;
#elif MC_VER <= MC_1_12_2
import net.minecraft.client.gui.*;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
#else
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
#endif
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

#if MC_VER <= MC_1_12_2
#elif MC_VER < MC_1_20_1
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.gui.GuiGraphics;
#else
import net.minecraft.client.gui.GuiGraphicsExtractor;
#endif

#if MC_VER >= MC_1_17_1
import net.minecraft.client.gui.narration.NarratableEntry;
#endif

#if MC_VER <= MC_1_12_2
import net.minecraft.util.ResourceLocation;
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
#else
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLKeycode;
#endif

import static com.seibel.distanthorizons.common.wrappers.gui.GuiHelper.*;
import static com.seibel.distanthorizons.common.wrappers.gui.GuiHelper.Translatable;

@SuppressWarnings("deprecation") // ResourceLocation constructor is deprecated on some MC versions
class DhConfigScreen extends DhScreen
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final ILangWrapper LANG_WRAPPER = SingletonInjector.INSTANCE.get(ILangWrapper.class);
	
	private static final String TRANSLATION_PREFIX = ModInfo.ID + ".config.";
	
	private static final MinecraftClientWrapper MC_CLIENT = MinecraftClientWrapper.INSTANCE;
	
	#if MC_VER <= MC_1_12_2
	private static final int changelogButton_id = 101;
	#endif
	
	#if MC_VER <= MC_1_12_2
	private final GuiScreen parent;
	#else
	private final Screen parent;
	#endif
	
	private final String category;
	private ClassicConfigGUI.ConfigListWidget configListWidget;
	private boolean reload = false;
	
	#if MC_VER <= MC_1_12_2
	private GuiButton doneButton;
	#else
	private Button doneButton;
	#endif
	#if MC_VER <= MC_1_7_10
	private final Map<GuiTextField, Predicate<String>> textFieldProcessors = new HashMap<>();
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	#if MC_VER <= MC_1_12_2
	protected DhConfigScreen(GuiScreen parent, String category)
	#else
	protected DhConfigScreen(Screen parent, String category)
	#endif
	{
		super(Translatable(
			LANG_WRAPPER.langExists(ModInfo.ID + ".config" + (category.isEmpty() ? "." + category : "") + ".title") ?
				ModInfo.ID + ".config.title" :
				ModInfo.ID + ".config" + (category.isEmpty() ? "" : "." + category) + ".title")
		);
		this.parent = parent;
		this.category = category;
	}
	
	//endregion
	
	
	
	//===================//
	// menu UI lifecycle //
	//===================//
	//region
	
	@Override
	#if MC_VER <= MC_1_12_2
	public void updateScreen()
	{
		super.updateScreen();
		
		#if MC_VER <= MC_1_7_10
		for (GuiTextField field : this.textFieldProcessors.keySet())
		{
			field.updateCursorCounter();
		}
		#endif
	}
	#else
	public void tick() { super.tick(); }
	#endif
	
	//endregion
	
	
	
	//==================//
	// menu UI creation //
	//==================//
	//region
	
	@Override
	#if MC_VER <= MC_1_12_2
	public void initGui()
	#else
	protected void init()
	#endif
	{
		#if MC_VER <= MC_1_12_2
		super.initGui();
		#else
		super.init();
		#endif
		
		if (!this.reload)
		{
			ConfigHandler.INSTANCE.configFileHandler.loadFromFile();
		}
		
		// Changelog button
		if (Config.Client.Advanced.AutoUpdater.enableAutoUpdater.get()
			// we only have changelogs for stable builds		
			&& !ModInfo.IS_DEV_BUILD)
		{
			this.addBtn(new TexturedButtonWidget(
				#if MC_VER <= MC_1_12_2
				changelogButton_id,
				#endif
				// Where the button is on the screen
				this.width - 28, this.height - 28,
				// Width and height of the button
				20, 20,
				// texture UV Offset
				0, 0,
				// Some texture stuff
				0, 
				#if MC_VER < MC_1_21_1
				new ResourceLocation(ModInfo.ID, "textures/gui/changelog.png"),
				#elif MC_VER <= MC_1_21_10
				ResourceLocation.fromNamespaceAndPath(ModInfo.ID, "textures/gui/changelog.png"),
				#else
				Identifier.fromNamespaceAndPath(ModInfo.ID, "textures/gui/changelog.png"),
				#endif
				20, 20,
				// Create the button and tell it where to go
				#if MC_VER > MC_1_12_2
				(buttonWidget) -> {
					ChangelogScreen changelogScreen = new ChangelogScreen(this);
					if (changelogScreen.usable)
					{
						DhScreenUtil.setScreen(changelogScreen);
					}
					else
					{
						LOGGER.warn("Changelog was not able to open");
					}
				},
				#endif
				
				// Add a title to the button
				#if MC_VER == MC_1_12_2
				Translatable(ModInfo.ID + ".updater.title").getFormattedText()
				#else
				Translatable(ModInfo.ID + ".updater.title")
				#endif
			));
		}
		
		
		// back button
		this.addBtn(MakeBtn(Translatable("distanthorizons.general.back"),
			(this.width / 2) - 154, this.height - 28,
			ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_HEIGHT,
			(button) ->
			{
				ConfigHandler.INSTANCE.configFileHandler.loadFromFile();
				DhScreenUtil.setScreen(this.parent);
			}));
		
		// done/close button
		this.doneButton = this.addBtn(
			MakeBtn(Translatable("distanthorizons.general.done"),
				(this.width / 2) + 4, this.height - 28,
				ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_HEIGHT,
				(button) ->
				{
					ConfigHandler.INSTANCE.configFileHandler.saveToFile();
					DhScreenUtil.setScreen(this.parent);
				}));
		
		#if MC_VER <= MC_1_12_2
		this.configListWidget = new ClassicConfigGUI.ConfigListWidget(this.mc, this.width * 2, this.height, 32, 32, 25);
		#else
		this.configListWidget = new ClassicConfigGUI.ConfigListWidget(this.minecraft, this.width * 2, this.height, 32, 32, 25);
		#endif
		
	    #if MC_VER <= MC_1_12_2
		#elif MC_VER < MC_1_20_6 // no background is rendered in MC 1.20.6+
		if (this.minecraft != null && this.minecraft.level != null)
		{
			this.configListWidget.setRenderBackground(false);
		}
		#endif
		
		#if MC_VER > MC_1_12_2
		this.addWidget(this.configListWidget);
		#endif
		
		for (AbstractConfigBase<?> configEntry : ConfigHandler.INSTANCE.configBaseList)
		{
			try
			{
				if (configEntry.getCategory().matches(this.category)
					&& configEntry.getAppearance().showInGui)
				{
					this.addMenuItem(configEntry);
				}
			}
			catch (Exception e)
			{
				String message = "ERROR: Failed to show [" + configEntry.getNameAndCategory() + "], error: [" + e.getMessage() + "]";
				if (configEntry.get() != null)
				{
					message += " with the value [" + configEntry.get() + "] with type [" + configEntry.getType() + "]";
				}
				
				LOGGER.error(message, e);
			}
		}
		
		ClassicConfigGUI.CONFIG_CORE_INTERFACE.onScreenChangeListenerList.forEach((listener) -> listener.run());
	}
	private void addMenuItem(AbstractConfigBase<?> configEntry)
	{
		trySetupConfigEntry(configEntry);
		
		if (this.tryCreateInputField(configEntry)) return;
		if (this.tryCreateCategoryButton(configEntry)) return;
		if (this.tryCreateButton(configEntry)) return;
		if (this.tryCreateComment(configEntry)) return;
		if (this.tryCreateSpacer(configEntry)) return;
		if (this.tryCreateLinkedEntry(configEntry)) return;
		
		LOGGER.warn("Config [" + configEntry.getNameAndCategory() + "] failed to show. Please try something like changing its type.");
	}
	
	private static void trySetupConfigEntry(AbstractConfigBase<?> configMenuOption)
	{
		configMenuOption.guiValue = new ConfigGuiInfo();
		Class<?> configValueClass = configMenuOption.getType();
		
		if (configMenuOption instanceof ConfigEntry)
		{
			ConfigEntry<?> configEntry = (ConfigEntry<?>) configMenuOption;
			
			if (configValueClass == Integer.class)
			{
				setupTextMenuOption(configEntry, Integer::parseInt, ClassicConfigGUI.INTEGER_ONLY_REGEX, true);
			}
			else if (configValueClass == Double.class)
			{
				setupTextMenuOption(configEntry, Double::parseDouble, ClassicConfigGUI.DECIMAL_ONLY_REGEX, false);
			}
			else if (configValueClass == Float.class)
			{
				setupTextMenuOption(configEntry, Float::parseFloat, ClassicConfigGUI.DECIMAL_ONLY_REGEX, false);
			}
			else if (configValueClass == String.class || configValueClass == List.class)
			{
				// For string or list
				setupTextMenuOption(configEntry, String::length, null, true);
			}
			else if (configValueClass == Boolean.class)
			{
				ConfigEntry<Boolean> booleanConfigEntry = (ConfigEntry<Boolean>) configEntry;
				setupBooleanMenuOption(booleanConfigEntry);
			}
			else if (configValueClass.isEnum())
			{
				ConfigEntry<Enum<?>> enumConfigEntry = (ConfigEntry<Enum<?>>) configEntry;
				Class<? extends Enum<?>> configEnumClass = (Class<? extends Enum<?>>) configValueClass;
				setupEnumMenuOption(enumConfigEntry, configEnumClass);
			}
			else
			{
				LOGGER.error("No definition for config with type: [" + configValueClass.getName() + "], for config: [" + configMenuOption.name + "].");
			}
		}
		
	}
	private static void setupTextMenuOption(AbstractConfigBase<?> configMenuOption, Function<String, Number> parsingFunc, @Nullable Pattern pattern, boolean cast)
	{
		final ConfigGuiInfo configGuiInfo = ((ConfigGuiInfo) configMenuOption.guiValue);
		
		configGuiInfo.tooltipFunction =
			(editBox, button) ->
				(stringValue) ->
				{
					boolean isNumber = (pattern != null);
					
					stringValue = stringValue.trim();
					if (!(stringValue.isEmpty() || !isNumber || pattern.matcher(stringValue).matches()))
					{
						return false;
					}
					
					
					Number numberValue = configMenuOption.typeIsFloatingPointNumber() ? 0.0 : 0; // different default values are needed so implicit casting works correctly (if not done casting from 0 (an int) to a double will cause an exception)
					configGuiInfo.errorMessage = null;
					if (isNumber
						&& !stringValue.isEmpty()
						&& !stringValue.equals("-")
						&& !stringValue.equals("."))
					{
						ConfigEntry<Number> numberConfigEntry = (ConfigEntry<Number>) configMenuOption;
						
						try
						{
							numberValue = parsingFunc.apply(stringValue);
						}
						catch (Exception e)
						{
							numberValue = null;
						}
						
						EConfigValidity validity = numberConfigEntry.getValidity(numberValue);
						switch (validity)
						{
							case VALID:
								configGuiInfo.errorMessage = null;
								break;
							case NUMBER_TOO_LOW:
								configGuiInfo.errorMessage = TextOrTranslatable("§cMinimum length is " + numberConfigEntry.getMin());
								break;
							case NUMBER_TOO_HIGH:
								configGuiInfo.errorMessage = TextOrTranslatable("§cMaximum length is " + numberConfigEntry.getMax());
								break;
							case INVALID:
								configGuiInfo.errorMessage = TextOrTranslatable("§cValue is invalid");
								break;
						}
					}
					
					editBox.setTextColor(((ConfigEntry<Number>) configMenuOption).getValidity(numberValue) == EConfigValidity.VALID ? 0xFFFFFFFF : 0xFFFF7777); // white and red
					
					
					if (configMenuOption.getType() == String.class
						|| configMenuOption.getType() == List.class)
					{
						((ConfigEntry<String>) configMenuOption).uiSetWithoutSaving(stringValue);
					}
					else if (((ConfigEntry<Number>) configMenuOption).getValidity(numberValue) == EConfigValidity.VALID)
					{
						if (!cast)
						{
							((ConfigEntry<Number>) configMenuOption).uiSetWithoutSaving(numberValue);
						}
						else
						{
							((ConfigEntry<Number>) configMenuOption).uiSetWithoutSaving(numberValue != null ? numberValue.intValue() : 0);
						}
					}
					
					return true;
				};
	}
	private static void setupBooleanMenuOption(ConfigEntry<Boolean> booleanConfigEntry)
	{
		// For boolean
		#if MC_VER <= MC_1_7_10
		Function<Object, String> func = value -> (((Boolean) value ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + Translatable("distanthorizons.general."+((Boolean) value ? "true" : "false")));
		#elif MC_VER <= MC_1_12_2
		Function<Object, ITextComponent> func = value -> Translatable("distanthorizons.general."+((Boolean) value ? "true" : "false")).setStyle(new Style().setColor((Boolean) value ? TextFormatting.GREEN : TextFormatting.RED));
		#else
		Function<Object, Component> func = value -> Translatable("distanthorizons.general." + ((Boolean) value ? "true" : "false")).withStyle((Boolean) value ? ChatFormatting.GREEN : ChatFormatting.RED);
		#endif
		
		final ConfigGuiInfo configGuiInfo = ((ConfigGuiInfo) booleanConfigEntry.guiValue);
		
		configGuiInfo.buttonOptionMap =
			#if MC_VER <= MC_1_7_10
			new AbstractMap.SimpleEntry<OnPressed, Function<Object, String>>(
			#elif MC_VER <= MC_1_12_2
			new AbstractMap.SimpleEntry<OnPressed, Function<Object, ITextComponent>>(
			#else
			new AbstractMap.SimpleEntry<Button.OnPress, Function<Object, Component>>(
			#endif
				(button) ->
				{
					#if MC_VER <= MC_1_12_2
					button.enabled = !booleanConfigEntry.apiIsOverriding();
					#else
					button.active = !booleanConfigEntry.apiIsOverriding();
					#endif
					
					booleanConfigEntry.uiSetWithoutSaving(!booleanConfigEntry.get());
					
					#if MC_VER <= MC_1_7_10
					button.displayString = func.apply(booleanConfigEntry.get());
					#elif MC_VER <= MC_1_12_2
					button.displayString = func.apply(booleanConfigEntry.get()).getFormattedText();
					#else
					button.setMessage(func.apply(booleanConfigEntry.get()));
					#endif
				}, func);
	}
	private static void setupEnumMenuOption(ConfigEntry<Enum<?>> enumConfigEntry, Class<? extends Enum<?>> enumClass)
	{
		List<Enum<?>> enumList = Arrays.asList(enumClass.getEnumConstants());
		
		final ConfigGuiInfo configGuiInfo = ((ConfigGuiInfo) enumConfigEntry.guiValue);
		
		String translatableEnumPrefix = TRANSLATION_PREFIX + "enum." + enumClass.getSimpleName() + ".";
		#if MC_VER <= MC_1_7_10
		Function<Object, String> getEnumTranslatableFunc = (value) -> Translatable(translatableEnumPrefix + value.toString());
		#elif MC_VER <= MC_1_12_2
		Function<Object, ITextComponent> getEnumTranslatableFunc = (value) -> Translatable(translatableEnumPrefix + value.toString());
		#else
		Function<Object, Component> getEnumTranslatableFunc = (value) -> Translatable(translatableEnumPrefix + value.toString());
		#endif
		
		configGuiInfo.buttonOptionMap =
			#if MC_VER <= MC_1_7_10
			new AbstractMap.SimpleEntry<OnPressed, Function<Object, String>>(
			#elif MC_VER <= MC_1_12_2
			new AbstractMap.SimpleEntry<OnPressed, Function<Object, ITextComponent>>(
			#else
			new AbstractMap.SimpleEntry<Button.OnPress, Function<Object, Component>>(
			#endif
				(button) ->
				{
					// get the currently selected enum and enum index
					int startingIndex = enumList.indexOf(enumConfigEntry.get());
					Enum<?> enumValue = enumList.get(startingIndex);
					
					boolean shiftPressed;
					#if MC_VER <= MC_1_12_2
					shiftPressed = GuiScreen.isShiftKeyDown();
					#elif MC_VER <= MC_26_2_0
					shiftPressed = InputConstants.isKeyDown(MC_CLIENT.getGlfwWindowId(), GLFW.GLFW_KEY_LEFT_SHIFT) 
						|| InputConstants.isKeyDown(MC_CLIENT.getGlfwWindowId(), GLFW.GLFW_KEY_RIGHT_SHIFT);
					#else
					shiftPressed = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
						|| InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
					#endif
					
					// move forward or backwards depending on if the shift key is pressed
					int index = shiftPressed ? startingIndex - 1 : startingIndex + 1;
					
					// wrap around to the other side of the array when necessary
					if (index >= enumList.size())
					{
						index = 0;
					}
					else if (index < 0)
					{
						index = enumList.size() - 1;
					}
					
					
					// walk through the enums to find the next selectable one
					while (index != startingIndex)
					{
						enumValue = enumList.get(index);
						
						// does enum have ignore attribute?
						boolean selectable = !AnnotationUtil.doesEnumHaveAnnotation(enumValue, DisallowSelectingViaConfigGui.class);
						if (selectable)
						{
							// does this enum have a show function?
							ConfigEntry.IShowEnumOptionFunc showEnumFunc = enumConfigEntry.getShowEnumOptionFunc();
							if (showEnumFunc != null)
							{
								selectable = showEnumFunc.shouldShowEnum(enumValue);
							}
						}
						
						if (selectable)
						{
							// this enum is a valid option
							break;
						}
						
						
						// move forward or backwards depending on if the shift key is pressed
						index = shiftPressed ? index - 1 : index + 1;
						
						// wrap around to the other side of the array when necessary
						if (index >= enumList.size())
						{
							index = 0;
						}
						else if (index < 0)
						{
							index = enumList.size() - 1;
						}
					}
					
					
					if (index == startingIndex)
					{
						// one of the enums should be selectable, this is a programmer error
						enumValue = enumList.get(startingIndex);
						LOGGER.warn("Enum [" + enumValue.getClass() + "] doesn't contain any values that should be selectable via the UI, sticking to the currently selected value [" + enumValue + "].");
					}
					
					
					enumConfigEntry.uiSetWithoutSaving(enumValue);
					
					#if MC_VER <= MC_1_7_10
					button.enabled = !enumConfigEntry.apiIsOverriding();
					button.displayString = getEnumTranslatableFunc.apply(enumConfigEntry.get());
					#elif MC_VER <= MC_1_12_2
					button.enabled = !enumConfigEntry.apiIsOverriding();
					button.displayString = getEnumTranslatableFunc.apply(enumConfigEntry.get()).getFormattedText();
					#else
					button.active = !enumConfigEntry.apiIsOverriding();
					button.setMessage(getEnumTranslatableFunc.apply(enumConfigEntry.get()));
					#endif
				}, getEnumTranslatableFunc);
	}
	
	private boolean tryCreateInputField(AbstractConfigBase<?> configBase)
	{
		final ConfigGuiInfo configGuiInfo = ((ConfigGuiInfo) configBase.guiValue);
		
		if (configBase instanceof ConfigEntry)
		{
			ConfigEntry configEntry = (ConfigEntry) configBase;
			
			
			//==============//
			// reset button //
			//==============//
			//region
			
			#if MC_VER <= MC_1_12_2 OnPressed #else Button.OnPress #endif btnAction = (button) ->
			{
				configEntry.uiSetWithoutSaving(configEntry.getDefaultValue());
				this.reload = true;
				DhScreenUtil.setScreen(this);
			};
			
			int resetButtonPosX = this.width
				- ClassicConfigGUI.ConfigScreenConfigs.RESET_BUTTON_WIDTH
				- ClassicConfigGUI.ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN;
			int resetButtonPosZ = 0;
			
			#if MC_VER <= MC_1_12_2 GuiButton #else Button #endif resetButton = MakeBtn(
				#if MC_VER <= MC_1_7_10
				EnumChatFormatting.RED + Translatable("distanthorizons.general.reset"),
				#elif MC_VER <= MC_1_12_2
				Translatable("distanthorizons.general.reset").setStyle(new Style().setColor(TextFormatting.RED)),
				#else
				Translatable("distanthorizons.general.reset").withStyle(ChatFormatting.RED),
				#endif
				resetButtonPosX, resetButtonPosZ,
				ClassicConfigGUI.ConfigScreenConfigs.RESET_BUTTON_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.RESET_BUTTON_HEIGHT,
				btnAction
			);
			
			
			if (configEntry.mcVersionOverridePresent())
			{
				#if MC_VER <= MC_1_7_10
				resetButton.displayString = EnumChatFormatting.DARK_GRAY + Translatable("distanthorizons.general.unsupportedMcVersion");
				resetButton.enabled = false;
				#elif MC_VER <= MC_1_12_2
				resetButton.displayString = Translatable("distanthorizons.general.unsupportedMcVersion").setStyle(new Style().setColor(TextFormatting.DARK_GRAY)).getFormattedText();
				resetButton.enabled = false;
				#else
				resetButton.active = false;
				resetButton.setMessage(Translatable("distanthorizons.general.unsupportedMcVersion").withStyle(ChatFormatting.DARK_GRAY));
				#endif
			}
			else if (configEntry.apiIsOverriding())
			{
				#if MC_VER <= MC_1_7_10
				resetButton.displayString = EnumChatFormatting.DARK_GRAY + Translatable("distanthorizons.general.apiOverride");
				resetButton.enabled = false;
				#elif MC_VER <= MC_1_12_2
				resetButton.displayString = Translatable("distanthorizons.general.apiOverride").setStyle(new Style().setColor(TextFormatting.DARK_GRAY)).getFormattedText();
				resetButton.enabled = false;
				#else
				resetButton.active = false;
				resetButton.setMessage(Translatable("distanthorizons.general.apiOverride").withStyle(ChatFormatting.DARK_GRAY));
				#endif
			}
			else
			{
				#if MC_VER <= MC_1_12_2
				resetButton.enabled = true;
				#else
				resetButton.active = true;
				#endif
			}
			
			//endregion
			
			
			
			//==============//
			// option field //
			//==============//
			//region
			
			#if MC_VER <= MC_1_7_10
			String textComponent = this.GetTranslatableTextComponentForConfig(configEntry);
			#elif MC_VER <= MC_1_12_2
			ITextComponent textComponent = this.GetTranslatableTextComponentForConfig(configEntry);
			#else
			Component textComponent = this.GetTranslatableTextComponentForConfig(configEntry);
			#endif
			
			int optionFieldPosX = this.width
				- ClassicConfigGUI.ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN
				- ClassicConfigGUI.ConfigScreenConfigs.RESET_BUTTON_WIDTH
				- ClassicConfigGUI.ConfigScreenConfigs.BUTTON_WIDTH_SPACING
				- ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH;
			int optionFieldPosZ = 0;
			
			if (configGuiInfo.buttonOptionMap != null)
			{
				// enum/multi option input button
				#if MC_VER <= MC_1_7_10
				Map.Entry<OnPressed, Function<Object, String>> widget = configGuiInfo.buttonOptionMap;
				#elif MC_VER <= MC_1_12_2
				Map.Entry<OnPressed, Function<Object, ITextComponent>> widget = configGuiInfo.buttonOptionMap;
				#else
				Map.Entry<Button.OnPress, Function<Object, Component>> widget = configGuiInfo.buttonOptionMap;
				#endif
				
				if (configEntry.getType().isEnum())
				{
					widget.setValue((value) -> Translatable(TRANSLATION_PREFIX + "enum." + configEntry.getType().getSimpleName() + "." + value.toString()));
				}
				
				#if MC_VER <= MC_1_12_2
				GuiButton button = MakeBtn(
				#else
				Button button = MakeBtn(
				#endif
					widget.getValue().apply(configEntry.get()),
					optionFieldPosX, optionFieldPosZ,
					ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT,
					widget.getKey());
				
				
				// deactivate the button if the API is overriding it
				// or the MC version doesn't support it
				if (configEntry.mcVersionOverridePresent()
					|| configEntry.apiIsOverriding())
				{
					#if MC_VER <= MC_1_12_2
					button.enabled = false;
					#else
					button.active = false;
					#endif
				}
				
				
				
				this.configListWidget.addButton(this, configEntry,
					button,
					resetButton,
					null,
					textComponent);
				
				return true;
			}
			else
			{
				// text box input
				#if MC_VER <= MC_1_12_2
				GuiTextField widget = new GuiTextField( #if MC_VER <= MC_1_7_10 this.fontRendererObj #else 0, this.fontRenderer #endif,
					optionFieldPosX, optionFieldPosZ,
					ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH - 4, ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT);
				widget.setMaxStringLength(3_000_000); // hopefully 3 million characters should be enough for any normal use-case, lol
				widget.setText(String.valueOf(configEntry.get()));
				#else
				EditBox widget = new EditBox(this.font,
					optionFieldPosX, optionFieldPosZ,
					ClassicConfigGUI.ConfigScreenConfigs.OPTION_FIELD_WIDTH - 4, ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT,
					Translatable(""));
				widget.setMaxLength(3_000_000); // hopefully 3 million characters should be enough for any normal use-case, lol
				widget.insertText(String.valueOf(configEntry.get()));
				#endif
				
				Predicate<String> processor = configGuiInfo.tooltipFunction.apply(widget, this.doneButton);
				#if MC_VER <= MC_1_7_10
				this.textFieldProcessors.put(widget, processor);
				#elif MC_VER <= MC_1_12_2
				widget.setValidator(processor::test);
				#elif MC_VER <= MC_1_21_11
				widget.setFilter(processor);
				#else
				widget.setResponder(processor::test);
				#endif
				
				this.configListWidget.addButton(this, configEntry, widget, resetButton, null, textComponent);
				
				return true;
			}
			
			//endregion
		}
		
		return false;
	}
	private boolean tryCreateCategoryButton(AbstractConfigBase<?> configType)
	{
		if (configType instanceof ConfigCategory)
		{
			ConfigCategory configCategory = (ConfigCategory) configType;
			
			#if MC_VER <= MC_1_7_10
			String textComponent = this.GetTranslatableTextComponentForConfig(configCategory);
			#elif MC_VER <= MC_1_12_2
			ITextComponent textComponent = this.GetTranslatableTextComponentForConfig(configCategory);
			#else
			Component textComponent = this.GetTranslatableTextComponentForConfig(configCategory);
			#endif
			
			int categoryPosX = this.width - ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_WIDTH - ClassicConfigGUI.ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN;
			int categoryPosZ = this.height - ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT; // Note: the posZ value here seems to be ignored
			
			#if MC_VER <= MC_1_12_2
			GuiButton widget = MakeBtn(
			#else
			Button widget = MakeBtn(
			#endif
				textComponent,
				categoryPosX, categoryPosZ,
				ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT,
				((button) ->
				{
					ConfigHandler.INSTANCE.configFileHandler.saveToFile();
					DhScreenUtil.setScreen(ClassicConfigGUI.getScreen(this, configCategory.getDestination()));
				}));
			this.configListWidget.addButton(this, configType, widget, null, null, null);
			
			return true;
		}
		
		return false;
	}
	private boolean tryCreateButton(AbstractConfigBase<?> configType)
	{
		if (configType instanceof ConfigUIButton)
		{
			ConfigUIButton configUiButton = (ConfigUIButton) configType;
			
			#if MC_VER <= MC_1_7_10
			String textComponent = this.GetTranslatableTextComponentForConfig(configUiButton);
			#elif MC_VER <= MC_1_12_2
			ITextComponent textComponent = this.GetTranslatableTextComponentForConfig(configUiButton);
			#else
			Component textComponent = this.GetTranslatableTextComponentForConfig(configUiButton);
			#endif
			
			int buttonPosX = this.width - ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_WIDTH - ClassicConfigGUI.ConfigScreenConfigs.SPACE_FROM_RIGHT_SCREEN;
			
			#if MC_VER <= MC_1_12_2
			GuiButton widget = MakeBtn(
			#else
			Button widget = MakeBtn(
			#endif
				textComponent,
				buttonPosX, this.height - 28,
				ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_WIDTH, ClassicConfigGUI.ConfigScreenConfigs.CATEGORY_BUTTON_HEIGHT,
				(button) -> ((ConfigUIButton) configType).runAction());
			this.configListWidget.addButton(this, configType, widget, null, null, null);
			
			return true;
		}
		
		return false;
	}
	private boolean tryCreateComment(AbstractConfigBase<?> configType)
	{
		if (configType instanceof ConfigUIComment)
		{
			ConfigUIComment configUiComment = (ConfigUIComment) configType;
			
			#if MC_VER <= MC_1_7_10
			String textComponent = this.GetTranslatableTextComponentForConfig(configUiComment);
			#elif MC_VER <= MC_1_12_2
			ITextComponent textComponent = this.GetTranslatableTextComponentForConfig(configUiComment);
			#else
			Component textComponent = this.GetTranslatableTextComponentForConfig(configUiComment);
			#endif
			if (configUiComment.parentConfigPath != null)
			{
				textComponent = Translatable(TRANSLATION_PREFIX + configUiComment.parentConfigPath);
			}
			
			this.configListWidget.addButton(this, configType, null, null, null, textComponent);
			
			return true;
		}
		
		return false;
	}
	private boolean tryCreateSpacer(AbstractConfigBase<?> configType)
	{
		if (configType instanceof ConfigUISpacer)
		{   
			#if MC_VER <= MC_1_12_2
			GuiButton spacerButton = MakeBtn(
			#else
			Button spacerButton = MakeBtn(
			#endif
				Translatable("distanthorizons.general.spacer"),
				10, 10, // having too small of a size causes division by 0 errors in older MC versions (IE 1.20.1)
				1, 1,
				(button) -> { });
			
			spacerButton.visible = false;
			this.configListWidget.addButton(this, configType, spacerButton, null, null, null);
			
			return true;
		}
		
		return false;
	}
	private boolean tryCreateLinkedEntry(AbstractConfigBase<?> configType)
	{
		if (configType instanceof ConfigUiLinkedEntry)
		{
			this.addMenuItem(((ConfigUiLinkedEntry) configType).get());
			
			return true;
		}
		
		return false;
	}
	
	#if MC_VER <= MC_1_7_10
	private String GetTranslatableTextComponentForConfig(AbstractConfigBase<?> configType)
	#elif MC_VER <= MC_1_12_2
	private ITextComponent GetTranslatableTextComponentForConfig(AbstractConfigBase<?> configType)
	#else
	private Component GetTranslatableTextComponentForConfig(AbstractConfigBase<?> configType)
	#endif
	{ return Translatable(TRANSLATION_PREFIX + configType.getNameAndCategory()); }
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override
	#if MC_VER <= MC_1_12_2
	public void drawScreen(int mouseX, int mouseY, float delta)
	#elif MC_VER < MC_1_20_1
	public void render(PoseStack matrices, int mouseX, int mouseY, float delta)
	#elif MC_VER <= MC_1_21_11
	public void render(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	#else
	public void extractRenderState(GuiGraphicsExtractor matrices, int mouseX, int mouseY, float delta)
	#endif
	{
		#if MC_VER <= MC_1_12_2
		this.drawDefaultBackground();
		#elif MC_VER < MC_1_20_2 // 1.20.2 now enables this by default in the `this.list.render` function
		this.renderBackground(matrices);
		#elif MC_VER <= MC_1_21_11
		super.render(matrices, mouseX, mouseY, delta);
		#else
		super.extractRenderState(matrices, mouseX, mouseY, delta);
		#endif
		
		// Render buttons
		#if MC_VER <= MC_1_12_2
		this.configListWidget.drawScreen(mouseX, mouseY, delta);
		#elif MC_VER <= MC_1_21_11
		this.configListWidget.render(matrices, mouseX, mouseY, delta);
		#else
		this.configListWidget.extractRenderState(matrices, mouseX, mouseY, delta);
		#endif
		
		
		// Render config title
		this.DhDrawCenteredString(
			#if MC_VER > MC_1_12_2	
			matrices, this.font,
			#endif
			this.title,
			this.width / 2, 15, 
			#if MC_VER < MC_1_21_6
			0xFFFFFF // RGB white
			#else 
			0xFFFFFFFF // ARGB white
			#endif);
		
		
		// render DH version
		this.DhDrawString(
			#if MC_VER > MC_1_12_2	
			matrices, this.font,
			#endif
			TextOrLiteral(ModInfo.VERSION), 2, this.height - 10, 
			#if MC_VER < MC_1_21_6
			0xAAAAAA // RGB white
			#else
			0xFFAAAAAA // ARGB white
			#endif);
		
		// If the update is pending, display this message to inform the user that it will apply when the game restarts
		if (SelfUpdater.deleteOldJarOnJvmShutdown)
		{
			this.DhDrawString(
				#if MC_VER > MC_1_12_2	
				matrices, this.font,
				#endif
				Translatable(ModInfo.ID + ".updater.waitingForClose"), 4, this.height - 42, 
				#if MC_VER < MC_1_21_6
				0xFFFFFF // RGB white
				#else
				0xFFFFFFFF // ARGB white
				#endif);
		}
		
		#if MC_VER <= MC_1_12_2
		super.drawScreen(mouseX, mouseY, delta);
		#elif MC_VER < MC_1_20_2
		super.render(matrices, mouseX, mouseY, delta);
		#endif
		
		#if MC_VER <= MC_1_12_2
		this.renderTooltip(mouseX, mouseY, delta);
		#else
		this.renderTooltip(matrices, mouseX, mouseY, delta);
		#endif
	}
	
	#if MC_VER <= MC_1_12_2
	private void renderTooltip(int mouseX, int mouseY, float delta)
	#elif MC_VER < MC_1_20_1
	private void renderTooltip(PoseStack matrices, int mouseX, int mouseY, float delta)
	#elif MC_VER <= MC_1_21_11
	private void renderTooltip(GuiGraphics matrices, int mouseX, int mouseY, float delta)
	#else
	private void renderTooltip(GuiGraphicsExtractor matrices, int mouseX, int mouseY, float delta)
	#endif
	{
		#if MC_VER <= MC_1_12_2
		Gui hoveredWidget = this.configListWidget.getHoveredButton(mouseX, mouseY);
		#else
		AbstractWidget hoveredWidget = this.configListWidget.getHoveredButton(mouseX, mouseY);
		#endif
		if (hoveredWidget == null)
		{
			return;
		}
		
		
		ClassicConfigGUI.DhButtonEntry button = ClassicConfigGUI.DhButtonEntry.BUTTON_BY_WIDGET.get(hoveredWidget);
		
		
		// A quick fix for tooltips on linked entries
		AbstractConfigBase<?> configBase = ConfigUiLinkedEntry.class.isAssignableFrom(button.dhConfigType.getClass()) ?
			((ConfigUiLinkedEntry) button.dhConfigType).get() :
			button.dhConfigType;
		
		boolean apiOverrideActive = false;
		boolean unsupportedMcVersion = false;
		String apiUser = null;
		if (configBase instanceof ConfigEntry)
		{
			apiOverrideActive = ((ConfigEntry<?>) configBase).apiIsOverriding();
			unsupportedMcVersion = ((ConfigEntry<?>) configBase).mcVersionOverridePresent();
			apiUser = ((ConfigEntry<?>) configBase).getApiUser();
		}
		if (apiUser == null
			|| apiUser.isEmpty())
		{
			apiUser = "UNKNOWN";
		}
		
		String key = TRANSLATION_PREFIX + (configBase.category.isEmpty() ? "" : configBase.category + ".") + configBase.getName() + ".@tooltip";
		
		if (unsupportedMcVersion)
		{
			key = "distanthorizons.general.unsupportedMcVersion.@tooltip";
		}
		else if (apiOverrideActive)
		{
			key = "distanthorizons.general.disabledByApi.@tooltip";
		}
		
		// display the validation error tooltip if present
		final ConfigGuiInfo configGuiInfo = ((ConfigGuiInfo) configBase.guiValue);
		if (configGuiInfo.errorMessage != null)
		{
			#if MC_VER <= MC_1_12_2
			this.DhRenderTooltip(configGuiInfo.errorMessage, mouseX, mouseY);
			#else
			this.DhRenderTooltip(matrices, this.font, configGuiInfo.errorMessage, mouseX, mouseY);
			#endif
		}
		// display the tooltip if present
		else if (LANG_WRAPPER.langExists(key))
		{
			#if MC_VER <= MC_1_7_10
			List<String> list = new ArrayList<>();
			#elif MC_VER <= MC_1_12_2
			List<ITextComponent> list = new ArrayList<>();
			#else
			List<Component> list = new ArrayList<>();
			#endif
			
			// 1.7.10 doesn't convert \n to new lines so we must do it manually
			String lang = LANG_WRAPPER.getLang(key).replace("\\n", "\n");
			for (String langLine : lang.split("\n"))
			{
				list.add(TextOrTranslatable(langLine));
			}
			
			if (apiOverrideActive)
			{
				// raw text is used here since we don't want to force
				// API users to translate their name
				list.add(TextOrLiteral(MinecraftTextFormat.ORANGE + apiUser + MinecraftTextFormat.CLEAR_FORMATTING));
			}
			
			#if MC_VER <= MC_1_12_2
			this.DhRenderComponentTooltip(list, mouseX, mouseY);
			#else
			this.DhRenderComponentTooltip(matrices, this.font, list, mouseX, mouseY);
			#endif
		}
	}
	
	#if MC_VER <= MC_1_12_2
	@Override
	protected void actionPerformed(GuiButton button)
	{
		super.actionPerformed(button);
		if(button.id == changelogButton_id)
		{
			ChangelogScreen changelogScreen = new ChangelogScreen(this);
			if (changelogScreen.usable)
			{
				DhScreenUtil.setScreen(changelogScreen);
			}
			else
			{
				LOGGER.warn("Changelog was not able to open");
			}
		}
	}
	
	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) 
		#if MC_VER > MC_1_7_10 throws java.io.IOException #endif
	{
		super.mouseClicked(mouseX, mouseY, mouseButton);
		
		if (mouseY >= this.configListWidget.top 
			&& mouseY <= this.configListWidget.bottom)
		{
			for (ClassicConfigGUI.DhButtonEntry entry : this.configListWidget.children)
			{
				if (entry.button instanceof GuiButton 
					&& ((GuiButton)entry.button).visible)
				{
					GuiButton btn = (GuiButton)entry.button;
					
					if (btn.mousePressed(this.mc, mouseX, mouseY))
					{
						btn.playPressSound(this.mc.getSoundHandler());
						
						OnPressed handler = GuiHelper.HANDLER_BY_BUTTON.get(btn);
						if (handler != null)
						{
							handler.pressed(btn);
						}
					}
				}
				else if (entry.button instanceof GuiTextField 
					&& ((GuiTextField)entry.button).getVisible())
				{
					GuiTextField field = (GuiTextField) entry.button;
					field.mouseClicked(mouseX, mouseY, mouseButton);
				}
				
				if (entry.resetButton instanceof GuiButton 
					&& ((GuiButton)entry.resetButton).visible)
				{
					GuiButton reset = (GuiButton) entry.resetButton;
					
					if (reset.mousePressed(this.mc, mouseX, mouseY))
					{
						reset.playPressSound(this.mc.getSoundHandler());
						
						OnPressed handler = GuiHelper.HANDLER_BY_BUTTON.get(reset);
						if (handler != null)
						{
							handler.pressed(reset);
						}
					}
				}
			}
		}
	}
	
	@Override
	protected void keyTyped(char typedChar, int keyCode) 
		#if MC_VER > MC_1_7_10 throws java.io.IOException #endif
	{
		super.keyTyped(typedChar, keyCode);
		for (ClassicConfigGUI.DhButtonEntry entry : this.configListWidget.children)
		{
			if (entry.button instanceof GuiTextField)
			{
				GuiTextField field = (GuiTextField) entry.button;
				
				field.textboxKeyTyped(typedChar, keyCode);
				
				#if MC_VER <= MC_1_7_10
				Predicate<String> processor = this.textFieldProcessors.get(field);
				if (processor != null)
				{
					processor.test(field.getText());
				}
				#endif
			}
		}
	}
	
	@Override
	public void handleMouseInput() 
		#if MC_VER > MC_1_7_10 throws java.io.IOException #endif
	{
		super.handleMouseInput();
		
		#if MC_VER > MC_1_7_10
		this.configListWidget.handleMouseInput();
		#endif
	}
	#endif
	
	//endregion
	
	
	
	//==========//
	// shutdown //
	//==========//
	//region
	/** When you close it, it goes to the previous screen and saves */
	@Override
	#if MC_VER <= MC_1_12_2
	public void onGuiClosed()
	#else
	public void onClose()
	#endif
	{
		ConfigHandler.INSTANCE.configFileHandler.saveToFile();
		#if MC_VER <= MC_1_12_2
		// Handled by button to avoid recursive loop
		#else
		DhScreenUtil.setScreen(this.parent);
		#endif
		ClassicConfigGUI.CONFIG_CORE_INTERFACE.onScreenChangeListenerList.forEach((listener) -> listener.run());
	}
	
	//endregion
	
}
