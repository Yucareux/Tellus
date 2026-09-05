package com.yucareux.tellus.client.preview;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.client.compat.AbstractTellusWidget;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
public final class TerrainPreviewWidget extends AbstractTellusWidget implements AutoCloseable {
   private static final float DEFAULT_ROTATION_X = (float)Math.toRadians(18.0);
   private static final float DEFAULT_ROTATION_Y = (float)Math.toRadians(18.0);
   private static final float DEFAULT_CAMERA_DISTANCE = 1.9F;
   private static final float MIN_ROTATION_X = (float)Math.toRadians(0.0);
   private static final float MAX_ROTATION_X = (float)Math.toRadians(80.0);
   private static final float MIN_CAMERA_DISTANCE = 0.9F;
   private static final float MAX_CAMERA_DISTANCE = 6.0F;
   private static final float ROTATION_SPEED = 0.01F;
   private static final float CAMERA_DOLLY_SPEED = 0.2F;
   private static final float AUTO_ROTATION_SPEED = -0.0022F;
   private static final long AUTO_RESUME_DELAY_MS = 1200L;
   private static final int MODE_BUTTON_WIDTH = 36;
   private static final int MODE_BUTTON_HEIGHT = 20;
   private static final int MODE_BUTTON_PADDING = 6;
   private static final int INFO_BUTTON_SIZE = 20;
   private static final int INFO_BUTTON_PADDING = 6;
   private static final int INFO_PANEL_WIDTH = 320;
   private static final int INFO_PANEL_PADDING = 8;
   private static final int INFO_TITLE_SPACING = 14;
   private static final int INFO_LINE_SPACING = 11;
   private static final int INFO_PANEL_BG = -1609957376;
   private static final int INFO_PANEL_BORDER = -12961222;
   private static final int INFO_TEXT = -1710619;
   private static final int INFO_SUBTLE = -4605511;
   private static final int INFO_GOOD = 0xFF6ED470;
   private static final int INFO_BAD = 0xFFF06262;
   private static final int FULLSCREEN_BUTTON_SIZE = 20;
   private static final int FULLSCREEN_BUTTON_PADDING = 6;
   private static final int CLOUD_BUTTON_SIZE = 20;
   private static final int CLOUD_BUTTON_SPACING = 4;
   private static final int CLOUD_ICON_SIZE = 16;
   private static final int CLOUD_ICON_TEXTURE_WIDTH = 32;
   private static final int CLOUD_ICON_TEXTURE_HEIGHT = 16;
   private static final Object CLOUD_TOGGLE_TEXTURE = Objects.requireNonNull(
      Tellus.id("textures/gui/cloud_toggle.png"), "cloudToggleTexture"
   );
   
   private static final Component FULLSCREEN_LABEL = Component.literal("[ ]");
   private static final Component INFO_LABEL = Component.literal("i");
   private static final int LOADING_PANEL_BG = -871362544;
   private static final int LOADING_PANEL_BORDER = -12961222;
   private static final int LOADING_BAR_BG = -15066598;
   private static final int LOADING_BAR_BORDER = -13816531;
   private static final int LOADING_BAR_FILL = -12599473;
   private static final int LOADING_TEXT = -1;
   private final TerrainPreview preview;
   private final boolean ownsPreview;
   private final Button modeButton;
   private final Button infoButton;
   private final Button fullscreenButton;
   private final Button cloudButton;

   private Runnable fullscreenAction;
   private boolean dragging;
   private boolean infoPanelVisible;
   private boolean cloudsVisible = true;
   private float rotationX = DEFAULT_ROTATION_X;
   private float rotationY = DEFAULT_ROTATION_Y;
   private float cameraDistance = DEFAULT_CAMERA_DISTANCE;
   private TerrainPreviewWidget.RenderMode renderMode = TerrainPreviewWidget.RenderMode.FULL_DETAIL;
   private long lastInteractionTime;

   public TerrainPreviewWidget(int x, int y, int width, int height) {
      this(x, y, width, height, new TerrainPreview(), true);
   }

   public TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview) {
      this(x, y, width, height, preview, false);
   }

   private TerrainPreviewWidget(int x, int y, int width, int height, TerrainPreview preview, boolean ownsPreview) {
      super(x, y, width, height, Objects.requireNonNull(Component.empty(), "widgetMessage"));
      this.preview = preview;
      this.ownsPreview = ownsPreview;
      this.modeButton = Button.builder(this.renderMode.buttonLabel(), button -> this.toggleRenderMode())
         .bounds(0, 0, MODE_BUTTON_WIDTH, MODE_BUTTON_HEIGHT)
         .build();
      this.modeButton.setTooltip(Tooltip.create(Component.translatable("tellus.preview.mode.tooltip")));
      this.infoButton = Button.builder(Objects.requireNonNull(INFO_LABEL, "infoLabel"), button -> this.toggleInfoPanel())
         .bounds(0, 0, INFO_BUTTON_SIZE, INFO_BUTTON_SIZE)
         .build();
      this.infoButton.setTooltip(Tooltip.create(Component.translatable("tellus.preview.info.button.tooltip")));
      this.fullscreenButton = Button.builder(Objects.requireNonNull(FULLSCREEN_LABEL, "fullscreenLabel"), button -> {
         if (this.fullscreenAction != null) {
            this.fullscreenAction.run();
         }
      }).bounds(0, 0, FULLSCREEN_BUTTON_SIZE, FULLSCREEN_BUTTON_SIZE).build();
      this.fullscreenButton.active = false;
      this.cloudButton = Button.builder(Component.empty(), button -> this.toggleClouds())
         .bounds(0, 0, CLOUD_BUTTON_SIZE, CLOUD_BUTTON_SIZE)
         .build();
      this.updateCloudButtonTooltip();
   }

   public void requestRebuild(EarthGeneratorSettings settings) {
      this.preview.requestRebuild(settings);
   }

   public void setFullscreenAction(Runnable action) {
      this.fullscreenAction = action;
      this.fullscreenButton.active = action != null;
   }

   public TerrainPreview getPreview() {
      return this.preview;
   }

   public TerrainPreviewWidget.ViewState getViewState() {
      return new TerrainPreviewWidget.ViewState(this.rotationX, this.rotationY, this.cameraDistance, this.renderMode, this.cloudsVisible);
   }

   public void setViewState(TerrainPreviewWidget.ViewState state) {
      this.rotationX = Mth.clamp(state.rotationX(), MIN_ROTATION_X, MAX_ROTATION_X);
      this.rotationY = state.rotationY();
      this.cameraDistance = Mth.clamp(state.cameraDistance(), MIN_CAMERA_DISTANCE, MAX_CAMERA_DISTANCE);
      this.renderMode = state.renderMode();
      this.cloudsVisible = state.cloudsVisible();
      this.updateModeButtonLabel();
      this.updateCloudButtonTooltip();
   }

   public void tick() {
      this.preview.tick();
      long now = System.currentTimeMillis();
      if (!this.dragging && now - this.lastInteractionTime > AUTO_RESUME_DELAY_MS) {
         this.rotationY += AUTO_ROTATION_SPEED;
      }
   }

   protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      this.renderBlurredBackground(graphics);
      int inset = 0;
      int contentX = this.getX() + inset;
      int contentY = this.getY() + inset;
      int contentWidth = Math.max(1, this.width - inset * 2);
      int contentHeight = Math.max(1, this.height - inset * 2);
      this.preview
         .render(
            graphics,
            contentX,
            contentY,
            contentWidth,
            contentHeight,
            this.rotationX,
            this.rotationY,
            this.cameraDistance,
            this.renderMode,
            this.cloudsVisible
         );
      this.renderLoadingOverlay(graphics, contentX, contentY, contentWidth, contentHeight);
      this.renderModeButton(graphics, mouseX, mouseY, delta);
      this.renderInfoButton(graphics, mouseX, mouseY, delta);
      this.renderInfoPanel(graphics, mouseX, mouseY, delta);
      this.renderFullscreenButton(graphics, mouseX, mouseY, delta);
      this.renderCloudButton(graphics, mouseX, mouseY, delta);
   }

   @Override
   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      if (!this.active || !this.visible || !this.isMouseOver(mouseX, mouseY)) {
         return false;
      } else if (this.modeButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.modeButton, mouseX, mouseY, button);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else if (this.infoButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.infoButton, mouseX, mouseY, button);
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.fullscreenButton, mouseX, mouseY, button);
         return true;
      } else if (this.fullscreenAction != null && this.cloudButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.cloudButton, mouseX, mouseY, button);
         return true;
      } else if (this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)) {
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else {
         if (button == 0) {
            this.dragging = true;
            this.lastInteractionTime = System.currentTimeMillis();
         }

         return true;
      }
   }

   protected void tellusOnClick(double mouseX, double mouseY) {
      if (this.modeButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.modeButton, mouseX, mouseY, 0);
         this.lastInteractionTime = System.currentTimeMillis();
      } else if (this.infoButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.infoButton, mouseX, mouseY, 0);
         this.lastInteractionTime = System.currentTimeMillis();
      } else if (this.fullscreenAction != null && this.fullscreenButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.fullscreenButton, mouseX, mouseY, 0);
      } else if (this.fullscreenAction != null && this.cloudButton.isMouseOver(mouseX, mouseY)) {
         ClientMinecraftCompat.mouseClicked(this.cloudButton, mouseX, mouseY, 0);
      } else if (this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)) {
         this.lastInteractionTime = System.currentTimeMillis();
      } else {
         this.dragging = true;
         this.lastInteractionTime = System.currentTimeMillis();
      }
   }

   protected void tellusOnDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
      if (this.dragging) {
         this.rotationY += (float)deltaX * ROTATION_SPEED;
         this.rotationX = Mth.clamp(this.rotationX + (float)deltaY * ROTATION_SPEED, MIN_ROTATION_X, MAX_ROTATION_X);
         this.lastInteractionTime = System.currentTimeMillis();
      }
   }

   protected void tellusOnRelease(double mouseX, double mouseY) {
      ClientMinecraftCompat.mouseReleased(this.modeButton, mouseX, mouseY, 0);
      ClientMinecraftCompat.mouseReleased(this.infoButton, mouseX, mouseY, 0);
      if (this.fullscreenAction != null) {
         ClientMinecraftCompat.mouseReleased(this.fullscreenButton, mouseX, mouseY, 0);
         ClientMinecraftCompat.mouseReleased(this.cloudButton, mouseX, mouseY, 0);
      }

      this.dragging = false;
      this.lastInteractionTime = System.currentTimeMillis();
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
      return this.handleMouseScrolled(mouseX, mouseY, amount);
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
      return this.handleMouseScrolled(mouseX, mouseY, verticalAmount);
   }

   private boolean handleMouseScrolled(double mouseX, double mouseY, double amount) {
      if (this.modeButton.isMouseOver(mouseX, mouseY)
         || this.infoButton.isMouseOver(mouseX, mouseY)
         || this.infoPanelVisible && this.isMouseOverInfoPanel(mouseX, mouseY)
         || this.fullscreenAction != null
            && (this.fullscreenButton.isMouseOver(mouseX, mouseY) || this.cloudButton.isMouseOver(mouseX, mouseY))) {
         return false;
      } else if (this.isMouseOver(mouseX, mouseY)) {
         this.cameraDistance = Mth.clamp(
            this.cameraDistance - (float)amount * CAMERA_DOLLY_SPEED, MIN_CAMERA_DISTANCE, MAX_CAMERA_DISTANCE
         );
         this.lastInteractionTime = System.currentTimeMillis();
         return true;
      } else {
         return false;
      }
   }

   public void setHeight(int height) {
      this.height = height;
   }

   protected void updateWidgetNarration(NarrationElementOutput narration) {
   }

   private void renderBlurredBackground(GuiGraphics graphics) {
      int left = this.getX();
      int top = this.getY();
      int right = this.getX() + this.width;
      int bottom = this.getY() + this.height;
      graphics.fill(left, top, right, bottom, -2013265920);
      graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 855638016);
      graphics.fillGradient(left, top, right, top + 8, 1140850688, 335544320);
      graphics.fillGradient(left, bottom - 8, right, bottom, 335544320, 1140850688);
      graphics.renderOutline(left, top, this.width, this.height, 402653184);
   }

   private void renderLoadingOverlay(GuiGraphics graphics, int x, int y, int width, int height) {
      if (this.preview.isLoading()) {
         TerrainPreview.PreviewStatus status = this.preview.getStatus();
         if (status.stage() != TerrainPreview.PreviewStage.COMPLETE) {
            String activityKey = status.activity();
            if (activityKey == null) {
               activityKey = switch (status.stage()) {
                  case DOWNLOADING -> "tellus.preview.loading.downloading";
                  case PROCESSING_OSM -> "tellus.preview.loading.processing_osm";
                  case LOADING, COMPLETE -> "tellus.preview.loading.processing";
               };
            }
            String label = Component.translatable(activityKey).getString();
            float displayedProgress = displayedProgress(status);
            float percentValue = status.stage() == TerrainPreview.PreviewStage.COMPLETE ? 100.0F : Mth.clamp(displayedProgress * 100.0F, 0.0F, 99.9F);
            String percentText = Objects.requireNonNull(
               percentValue >= 10.0F ? String.format(Locale.ROOT, "%.0f%%", percentValue) : String.format(Locale.ROOT, "%.1f%%", percentValue),
               "loadingPercent"
            );
            Font font = Minecraft.getInstance().font;
            int padding = 6;
            int maxInnerWidth = Math.max(20, width - padding * 2 - 4);
            int barWidth = Math.min(180, maxInnerWidth);
            int textLineWidth = font.width(label) + font.width(percentText) + 12;
            int innerWidth = Math.min(maxInnerWidth, Math.max(barWidth, textLineWidth));
            int panelWidth = innerWidth + padding * 2;
            int barHeight = 8;
            int panelHeight = padding * 3 + 9 + barHeight;
            int panelX = x + (width - panelWidth) / 2;
            int panelY = y + height - panelHeight - 10;
            panelY = Mth.clamp(panelY, y + 6, y + height - panelHeight - 6);
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, LOADING_PANEL_BG);
            graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, LOADING_PANEL_BORDER);
            int textY = panelY + padding;
            graphics.drawString(font, label, panelX + padding, textY, LOADING_TEXT, true);
            graphics.drawString(font, percentText, panelX + panelWidth - padding - font.width(percentText), textY, LOADING_TEXT, true);
            int barX = panelX + padding;
            int barY = textY + 9 + padding;
            int barInnerWidth = innerWidth - 2;
            graphics.fill(barX, barY, barX + innerWidth, barY + barHeight, LOADING_BAR_BG);
            graphics.renderOutline(barX, barY, innerWidth, barHeight, LOADING_BAR_BORDER);
            float clamped = Mth.clamp(displayedProgress, 0.0F, 1.0F);
            int fillWidth = Math.round(barInnerWidth * clamped);
            if (fillWidth == 0 && clamped > 0.0F) {
               fillWidth = 1;
            }

            if (fillWidth > 0) {
               graphics.fill(barX + 1, barY + 1, barX + 1 + fillWidth, barY + barHeight - 1, LOADING_BAR_FILL);
            }
         }
      }
   }

   private static float displayedProgress(TerrainPreview.PreviewStatus status) {
      float stageProgress = Mth.clamp(status.progress(), 0.0F, 1.0F);

      return switch (status.stage()) {
         case DOWNLOADING -> stageProgress * 0.4F;
         case PROCESSING_OSM -> 0.4F + stageProgress * 0.6F;
         case LOADING -> 0.4F + stageProgress * 0.6F;
         case COMPLETE -> 1.0F;
      };
   }

   private void toggleRenderMode() {
      this.renderMode = this.renderMode.toggle();
      this.updateModeButtonLabel();
      this.lastInteractionTime = System.currentTimeMillis();
      this.dragging = false;
   }

   private void toggleInfoPanel() {
      this.infoPanelVisible = !this.infoPanelVisible;
      this.lastInteractionTime = System.currentTimeMillis();
      this.dragging = false;
   }

   private void toggleClouds() {
      this.cloudsVisible = !this.cloudsVisible;
      this.updateCloudButtonTooltip();
      this.lastInteractionTime = System.currentTimeMillis();
      this.dragging = false;
   }

   private void updateModeButtonLabel() {
      this.modeButton.setMessage(this.renderMode.buttonLabel());
   }

   private void updateCloudButtonTooltip() {
      String key = this.cloudsVisible ? "tellus.preview.clouds.visible.tooltip" : "tellus.preview.clouds.hidden.tooltip";
      this.cloudButton.setTooltip(Tooltip.create(Component.translatable(key)));
   }

   private void renderModeButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      int buttonX = this.getX() + MODE_BUTTON_PADDING;
      int buttonY = this.getY() + MODE_BUTTON_PADDING;
      this.modeButton.setX(buttonX);
      this.modeButton.setY(buttonY);
      this.modeButton.setWidth(MODE_BUTTON_WIDTH);
      ClientMinecraftCompat.setWidgetHeight(this.modeButton, MODE_BUTTON_HEIGHT);
      this.modeButton.render(graphics, mouseX, mouseY, delta);
   }

   private void renderInfoButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      int buttonX = this.getX() + MODE_BUTTON_PADDING;
      int buttonY = this.getY() + MODE_BUTTON_PADDING + MODE_BUTTON_HEIGHT + INFO_BUTTON_PADDING;
      this.infoButton.setX(buttonX);
      this.infoButton.setY(buttonY);
      this.infoButton.setWidth(INFO_BUTTON_SIZE);
      ClientMinecraftCompat.setWidgetHeight(this.infoButton, INFO_BUTTON_SIZE);
      this.infoButton.render(graphics, mouseX, mouseY, delta);
   }

   private void renderInfoPanel(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (!this.infoPanelVisible) {
         return;
      }

      Font font = Minecraft.getInstance().font;
      TerrainPreviewWidget.InfoPanelLayout layout = this.infoPanelLayout(font);
      graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), INFO_PANEL_BG);
      graphics.renderOutline(layout.x(), layout.y(), layout.width(), layout.height(), INFO_PANEL_BORDER);
      int textX = layout.x() + INFO_PANEL_PADDING;
      int textY = layout.y() + INFO_PANEL_PADDING;
      TerrainPreview.PreviewInfo info = this.preview.getInfo();
      if (info == null) {
         graphics.drawString(font, Component.translatable("tellus.preview.info.title"), textX, textY, INFO_TEXT, false);
         graphics.drawString(font, Component.translatable("tellus.preview.info.loading"), textX, textY + INFO_TITLE_SPACING, INFO_SUBTLE, false);
      } else {
         graphics.drawString(font, Component.translatable("tellus.preview.info.title"), textX, textY, INFO_TEXT, false);
         textY += INFO_TITLE_SPACING;
         String demSummary = this.demSummary(info);
         graphics.drawString(font, this.truncate(font, demSummary, layout.contentWidth()), textX, textY, INFO_TEXT, false);
         textY += INFO_LINE_SPACING;
         String resolutionSummary = this.resolutionSummary(info);
         graphics.drawString(font, this.truncate(font, resolutionSummary, layout.contentWidth()), textX, textY, INFO_SUBTLE, false);
         textY += INFO_LINE_SPACING;
         if (info.hasBlendedProviders()) {
            String blended = Component.translatable("tellus.preview.info.blended").getString() + ": " + this.providerNames(info.blendedProviders());
            graphics.drawString(font, this.truncate(font, blended, layout.contentWidth()), textX, textY, INFO_SUBTLE, false);
            textY += INFO_LINE_SPACING;
         }

         String altitude = Component.translatable("tellus.preview.info.highest").getString() + ": " + String.format(Locale.ROOT, "%.0f m", info.maxElevationMeters());
         graphics.drawString(font, this.truncate(font, altitude, layout.contentWidth()), textX, textY, INFO_TEXT, false);
         textY += INFO_TITLE_SPACING;
         this.drawValueLine(
            graphics,
            font,
            textX,
            textY,
            Component.translatable("tellus.preview.info.min_y").getString(),
            Integer.toString(info.minSurfaceY()),
            info.minWithinLimits() ? INFO_GOOD : INFO_BAD,
            layout.contentWidth()
         );
         textY += INFO_LINE_SPACING;
         this.drawValueLine(
            graphics,
            font,
            textX,
            textY,
            Component.translatable("tellus.preview.info.max_y").getString(),
            Integer.toString(info.maxSurfaceY()),
            info.maxWithinLimits() ? INFO_GOOD : INFO_BAD,
            layout.contentWidth()
         );
      }
   }

   private void renderFullscreenButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (this.fullscreenAction != null) {
         int buttonX = this.getX() + this.width - FULLSCREEN_BUTTON_SIZE - FULLSCREEN_BUTTON_PADDING;
         int buttonY = this.getY() + FULLSCREEN_BUTTON_PADDING;
         this.fullscreenButton.setX(buttonX);
         this.fullscreenButton.setY(buttonY);
         this.fullscreenButton.setWidth(FULLSCREEN_BUTTON_SIZE);
         ClientMinecraftCompat.setWidgetHeight(this.fullscreenButton, FULLSCREEN_BUTTON_SIZE);
         this.fullscreenButton.render(graphics, mouseX, mouseY, delta);
      }
   }

   private void renderCloudButton(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      if (this.fullscreenAction != null) {
         int buttonX = this.getX() + this.width - CLOUD_BUTTON_SIZE - FULLSCREEN_BUTTON_PADDING;
         int buttonY = this.getY() + FULLSCREEN_BUTTON_PADDING + FULLSCREEN_BUTTON_SIZE + CLOUD_BUTTON_SPACING;
         this.cloudButton.setX(buttonX);
         this.cloudButton.setY(buttonY);
         this.cloudButton.setWidth(CLOUD_BUTTON_SIZE);
         ClientMinecraftCompat.setWidgetHeight(this.cloudButton, CLOUD_BUTTON_SIZE);
         this.cloudButton.render(graphics, mouseX, mouseY, delta);
         int iconX = buttonX + (CLOUD_BUTTON_SIZE - CLOUD_ICON_SIZE) / 2;
         int iconY = buttonY + (CLOUD_BUTTON_SIZE - CLOUD_ICON_SIZE) / 2;
         float textureX = this.cloudsVisible ? 0.0F : CLOUD_ICON_SIZE;
         ClientMinecraftCompat.blit(
            graphics,
            CLOUD_TOGGLE_TEXTURE,
            iconX,
            iconY,
            textureX,
            0.0F,
            CLOUD_ICON_SIZE,
            CLOUD_ICON_SIZE,
            CLOUD_ICON_TEXTURE_WIDTH,
            CLOUD_ICON_TEXTURE_HEIGHT
         );
      }
   }

   private TerrainPreviewWidget.InfoPanelLayout infoPanelLayout(Font font) {
      int infoButtonX = this.getX() + MODE_BUTTON_PADDING;
      int infoButtonY = this.getY() + MODE_BUTTON_PADDING + MODE_BUTTON_HEIGHT + INFO_BUTTON_PADDING;
      int x = infoButtonX + INFO_BUTTON_SIZE + 8;
      int width = Math.min(INFO_PANEL_WIDTH, Math.max(190, this.getX() + this.width - x - 8));
      TerrainPreview.PreviewInfo info = this.preview.getInfo();
      int textLines = info == null ? 2 : 6 + (info.hasBlendedProviders() ? 1 : 0);
      int textHeight = INFO_TITLE_SPACING + (textLines - 1) * INFO_LINE_SPACING;
      int height = INFO_PANEL_PADDING + textHeight + INFO_PANEL_PADDING;
      int y = infoButtonY;
      return new TerrainPreviewWidget.InfoPanelLayout(x, y, width, height);
   }

   private boolean isMouseOverInfoPanel(double mouseX, double mouseY) {
      if (!this.infoPanelVisible) {
         return false;
      } else {
         TerrainPreviewWidget.InfoPanelLayout layout = this.infoPanelLayout(Minecraft.getInstance().font);
         return mouseX >= layout.x() && mouseX < layout.right() && mouseY >= layout.y() && mouseY < layout.bottom();
      }
   }

   private String demSummary(TerrainPreview.PreviewInfo info) {
      if (info.primaryProviders().isEmpty()) {
         return Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (!info.hasMixedPrimaryProviders()) {
         return Component.translatable("tellus.preview.info.dem_main").getString() + ": " + info.mainProvider().provider().label().getString();
      } else {
         StringBuilder builder = new StringBuilder(Component.translatable("tellus.preview.info.dem_mix").getString()).append(": ");

         for (int i = 0; i < info.primaryProviders().size(); i++) {
            TerrainPreview.PreviewProviderShare share = info.primaryProviders().get(i);
            if (i > 0) {
               builder.append(", ");
            }

            builder.append(share.provider().label().getString())
               .append(" (")
               .append(String.format(Locale.ROOT, "%.0f%%", share.share() * 100.0))
               .append(")");
         }

         return builder.toString();
      }
   }

   private String providerNames(Iterable<TellusElevationSource.DemUsage> providers) {
      StringBuilder builder = new StringBuilder();

      for (TellusElevationSource.DemUsage provider : providers) {
         if (!builder.isEmpty()) {
            builder.append(", ");
         }

         builder.append(provider.label().getString());
      }

      return builder.toString();
   }

   private String resolutionSummary(TerrainPreview.PreviewInfo info) {
      String label = Component.translatable("tellus.preview.info.resolution").getString();
      if (info.primaryResolutions().isEmpty()) {
         return label + ": " + Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (!info.hasMixedPrimaryResolutions()) {
         return label + ": " + this.formatResolution(info.mainResolution().resolutionMeters());
      } else {
         StringBuilder builder = new StringBuilder(label).append(": ");

         for (int i = 0; i < info.primaryResolutions().size(); i++) {
            TerrainPreview.PreviewResolutionShare share = info.primaryResolutions().get(i);
            if (i > 0) {
               builder.append(", ");
            }

            builder.append(this.formatResolution(share.resolutionMeters()))
               .append(" (")
               .append(String.format(Locale.ROOT, "%.0f%%", share.share() * 100.0))
               .append(")");
         }

         return builder.toString();
      }
   }

   private String formatResolution(double resolutionMeters) {
      if (!Double.isFinite(resolutionMeters) || resolutionMeters <= 0.0) {
         return Component.translatable("tellus.preview.info.dem_unknown").getString();
      } else if (Math.abs(resolutionMeters - Math.rint(resolutionMeters)) < 0.05) {
         return String.format(Locale.ROOT, "%.0f m", resolutionMeters);
      } else {
         return String.format(Locale.ROOT, "%.1f m", resolutionMeters);
      }
   }

   private String truncate(Font font, String text, int width) {
      if (font.width(text) <= width) {
         return text;
      } else {
         String truncated = font.plainSubstrByWidth(text, Math.max(0, width - font.width("...")));
         return truncated + "...";
      }
   }

   private void drawValueLine(GuiGraphics graphics, Font font, int x, int y, String label, String value, int valueColor, int width) {
      graphics.drawString(font, label, x, y, INFO_TEXT, false);
      int valueWidth = font.width(value);
      int valueX = Math.min(x + font.width(label) + 16, x + Math.max(0, width - valueWidth));
      graphics.drawString(font, value, valueX, y, valueColor, false);
   }

   @Override
   public void close() {
      if (this.ownsPreview) {
         this.preview.close();
      }
   }
   private record InfoPanelLayout(int x, int y, int width, int height) {
      private int right() {
         return this.x + this.width;
      }

      private int bottom() {
         return this.y + this.height;
      }

      private int contentWidth() {
         return this.width - INFO_PANEL_PADDING * 2;
      }
   }
   public record ViewState(
      float rotationX,
      float rotationY,
      float cameraDistance,
      TerrainPreviewWidget.RenderMode renderMode,
      boolean cloudsVisible
   ) {
      public ViewState(
         float rotationX,
         float rotationY,
         float cameraDistance,
         TerrainPreviewWidget.RenderMode renderMode,
         boolean cloudsVisible
      ) {
         this.rotationX = rotationX;
         this.rotationY = rotationY;
         this.cameraDistance = cameraDistance;
         this.renderMode = Objects.requireNonNull(renderMode, "renderMode");
         this.cloudsVisible = cloudsVisible;
      }
   }
   public static enum RenderMode {
      FULL_DETAIL("3D+"),
      TERRAIN_ONLY("3D");

      private final Component buttonLabel;

      private RenderMode(String buttonLabel) {
         this.buttonLabel = Component.literal(buttonLabel);
      }

      private Component buttonLabel() {
         return this.buttonLabel;
      }

      private TerrainPreviewWidget.RenderMode toggle() {
         return this == FULL_DETAIL ? TERRAIN_ONLY : FULL_DETAIL;
      }
   }
}
