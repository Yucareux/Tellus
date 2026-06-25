package com.yucareux.tellus.client.widget.map.component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import net.minecraft.client.gui.GuiGraphics;

@OnlyIn(Dist.CLIENT)
public interface MapComponent {
   void onDrawMap(SlippyMap var1, GuiGraphics var2, int var3, int var4, SlippyMapPoint var5);

   default boolean onMouseClicked(SlippyMap map, SlippyMapPoint mouse, int button) {
      return false;
   }

   default boolean onMouseReleased(SlippyMap map, SlippyMapPoint mouse, int button) {
      return false;
   }
}
