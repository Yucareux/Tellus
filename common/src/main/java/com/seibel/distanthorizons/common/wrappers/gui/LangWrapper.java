package com.seibel.distanthorizons.common.wrappers.gui;

import com.seibel.distanthorizons.core.wrapperInterfaces.config.ILangWrapper;
#if MC_VER <= MC_1_12_2
#if MC_VER <= MC_1_7_10
import net.minecraft.util.StatCollector;
#else
import net.minecraft.client.resources.I18n;
#endif
#else
import net.minecraft.client.resources.language.I18n;
#endif

public class LangWrapper implements ILangWrapper
{
	public static final LangWrapper INSTANCE = new LangWrapper();
	
	@Override
	public boolean langExists(String str)
	{
		#if MC_VER <= MC_1_7_10
		return StatCollector.canTranslate(str);
		#elif MC_VER <= MC_1_12_2
		return I18n.hasKey(str);
		#elif MC_VER <= MC_26_1_2
		return I18n.exists(str);
		#else
		String translated = getLang(str);
		return translated != null 
			// if this isn't translatable it will generally return
			// the same string as was passed in
			&& !translated.equalsIgnoreCase(str);
		#endif
	}
	
	@Override
	public String getLang(String str)
	{
		#if MC_VER <= MC_1_7_10
		return StatCollector.translateToLocal(str);
		#elif MC_VER <= MC_1_12_2
		return I18n.format(str);
		#else
		return I18n.get(str);
		#endif
	}
	
	
	
}
