package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.screen.EarthCustomizeScreen;
import com.yucareux.tellus.worldgen.TellusWorldPresets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@OnlyIn(Dist.CLIENT)
@Mixin({PresetEditor.class})
public interface PresetEditorMixin {
   @Redirect(
      method = {"<clinit>"},
      at = @At(
         value = "INVOKE",
         target = "Ljava/util/Map;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"
      )
   )
   private static Map<Object, Object> tellus$addEarthEditor(Object key1, Object value1, Object key2, Object value2) {
      Map<Object, Object> editors = new HashMap<>();
      PresetEditor earthEditor = EarthCustomizeScreen::new;
      editors.put(key1, value1);
      editors.put(key2, value2);
      editors.put(Optional.of(TellusWorldPresets.EARTH), earthEditor);
      return Map.copyOf(editors);
   }
}
