package com.yucareux.tellus.mixin.client;

import com.yucareux.tellus.client.LoadingAttributionLayout;
import com.yucareux.tellus.client.LoadingTerrainScreenTiming;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.StoringChunkProgressListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin({LevelLoadingScreen.class})
public abstract class LevelLoadingScreenMixin {
   private static final int TEXT_COLOR = -1710619;
   private static final int TEXT_PADDING = 20;
   private static final int LINE_SPACING = 2;
   private static final int MAX_TEXT_WIDTH = 420;
   private static final int LOADING_WIDGET_TEXT_GAP = 8;
   private static final float[] TEXT_SCALES = {1.0F, 0.95F, 0.9F, 0.85F, 0.8F, 0.75F};
   private static final List<Component> CONTRIBUTIONS = List.of(
      Component.translatable("tellus.loading.attribution.land_cover"),
      Component.translatable("tellus.loading.attribution.climate"),
      Component.translatable("tellus.loading.attribution.elevation"),
      Component.translatable("tellus.loading.attribution.weather")
   );
   @Shadow
   @Final
   private StoringChunkProgressListener progressListener;

   @Inject(
      method = {"render"},
      at = {@At("HEAD")}
   )
   private void tellus$trackLoadingTerrainScreen(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      LoadingTerrainScreenTiming.onScreenRender((LevelLoadingScreen)(Object)this, "level_loading");
   }

   @Inject(
      method = {"render"},
      at = {@At("TAIL")}
   )
   private void tellus$renderContributions(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (!tellus$isLoadingTellusWorld()) {
         return;
      }

      Font font = Minecraft.getInstance().font;
      int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
      int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
      int centerY = height / 2;
      int chunkDisplayRadius = this.progressListener.getDiameter();
      int loadingWidgetTop = ClientMinecraftCompat.loadingWidgetTop(centerY, chunkDisplayRadius, font.lineHeight);
      int loadingWidgetBottom = ClientMinecraftCompat.loadingWidgetBottom(centerY, chunkDisplayRadius);
      AttributionRenderPlan plan = tellus$createAttributionPlan(
         font, width, height, loadingWidgetTop, loadingWidgetBottom
      );
      if (plan != null) {
         tellus$drawAttributions(graphics, font, width / 2, plan);
      }
   }

   private static AttributionRenderPlan tellus$createAttributionPlan(
      Font font, int width, int height, int loadingWidgetTop, int loadingWidgetBottom
   ) {
      int renderedWrapWidth = Math.max(1, Math.min(MAX_TEXT_WIDTH, width - TEXT_PADDING * 2));
      for (float scale : TEXT_SCALES) {
         int logicalWrapWidth = Math.max(1, (int)Math.floor(renderedWrapWidth / scale));
         List<FormattedCharSequence> lines = new ArrayList<>();
         List<Integer> groupLineCounts = new ArrayList<>();
         for (Component contribution : CONTRIBUTIONS) {
            int firstLine = lines.size();
            for (FormattedCharSequence wrapped : font.split(contribution, logicalWrapWidth)) {
               lines.add(Objects.requireNonNull(wrapped, "wrappedLine"));
            }
            groupLineCounts.add(lines.size() - firstLine);
         }

         LoadingAttributionLayout.Layout layout = LoadingAttributionLayout.arrange(
               groupLineCounts,
               font.lineHeight * scale,
               LINE_SPACING * scale,
               TEXT_PADDING,
               loadingWidgetTop - LOADING_WIDGET_TEXT_GAP,
               loadingWidgetBottom + LOADING_WIDGET_TEXT_GAP,
               height - TEXT_PADDING,
               true
            )
            .orElseGet(
               () -> LoadingAttributionLayout.arrange(
                     groupLineCounts,
                     font.lineHeight * scale,
                     LINE_SPACING * scale,
                     TEXT_PADDING,
                     loadingWidgetTop - LOADING_WIDGET_TEXT_GAP,
                     loadingWidgetBottom + LOADING_WIDGET_TEXT_GAP,
                     height - TEXT_PADDING,
                     false
                  )
                  .orElse(null)
            );
         if (layout != null) {
            return new AttributionRenderPlan(lines, layout, scale);
         }
      }
      return null;
   }

   private static void tellus$drawAttributions(
      GuiGraphics graphics, Font font, int centerX, AttributionRenderPlan plan
   ) {
      for (LoadingAttributionLayout.Block block : plan.layout().blocks()) {
         ClientMinecraftCompat.pushPose(graphics);
         ClientMinecraftCompat.translatePose(graphics, centerX, block.y());
         ClientMinecraftCompat.scalePose(graphics, plan.scale(), plan.scale());
         int y = 0;
         int endLine = block.firstLine() + block.lineCount();
         for (int lineIndex = block.firstLine(); lineIndex < endLine; lineIndex++) {
            FormattedCharSequence line = plan.lines().get(lineIndex);
            graphics.drawString(font, line, -font.width(line) / 2, y, TEXT_COLOR, true);
            y += font.lineHeight + LINE_SPACING;
         }
         ClientMinecraftCompat.popPose(graphics);
      }
   }

   private static boolean tellus$isLoadingTellusWorld() {
      MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
      if (server == null) {
         return false;
      }

      ServerLevel overworld = server.getLevel(Level.OVERWORLD);
      return overworld != null && overworld.getChunkSource().getGenerator() instanceof EarthChunkGenerator;
   }

   private record AttributionRenderPlan(
      List<FormattedCharSequence> lines, LoadingAttributionLayout.Layout layout, float scale
   ) {
   }
}
