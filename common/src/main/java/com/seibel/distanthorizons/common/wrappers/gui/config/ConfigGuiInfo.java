package com.seibel.distanthorizons.common.wrappers.gui.config;

import com.seibel.distanthorizons.core.config.gui.IConfigGuiInfo;
import com.seibel.distanthorizons.core.config.types.AbstractConfigBase;
#if MC_VER <= MC_1_12_2
import com.seibel.distanthorizons.common.wrappers.gui.OnPressed;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
#if MC_VER > MC_1_7_10
import net.minecraft.util.text.ITextComponent;
#endif
#else
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
#endif
import org.jetbrains.annotations.Nullable;

import java.util.AbstractMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** 
 * holds information needed by the config GUI for rendering.
 * 
 * @see AbstractConfigBase
 */
public class ConfigGuiInfo implements IConfigGuiInfo
{
	/**
	 * Used to display validation errors.
	 * Null if no error is present.
	 */

	@Nullable
	#if MC_VER <= MC_1_7_10
	public String errorMessage;
	#elif MC_VER <= MC_1_12_2
	public ITextComponent errorMessage;
	#else
	public Component errorMessage;
	#endif
	
	#if MC_VER <= MC_1_12_2
	public BiFunction<GuiTextField, GuiButton, Predicate<String>> tooltipFunction;
	#else
	public BiFunction<EditBox, Button , Predicate<String>> tooltipFunction;
	#endif
	
	/** determines which options the button will show */
	#if MC_VER <= MC_1_7_10
	public AbstractMap.SimpleEntry<OnPressed, Function<Object, String>> buttonOptionMap;
	#elif MC_VER <= MC_1_12_2
	public AbstractMap.SimpleEntry<OnPressed, Function<Object, ITextComponent>> buttonOptionMap;
	#else
	public AbstractMap.SimpleEntry<Button.OnPress, Function<Object, Component>> buttonOptionMap;
	#endif
	
}
