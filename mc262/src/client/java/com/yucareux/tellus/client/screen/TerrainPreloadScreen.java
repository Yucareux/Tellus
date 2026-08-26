package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MapComponent;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.preload.TerrainPreloadArea;
import com.yucareux.tellus.preload.TerrainPreloadJob;
import com.yucareux.tellus.preload.TerrainPreloadJobManager;
import com.yucareux.tellus.preload.TerrainPreloadProgress;
import com.yucareux.tellus.preload.TerrainPreloadText;
import com.yucareux.tellus.preload.TerrainPreloadSettingsOverrides;
import com.yucareux.tellus.preload.TerrainPreloadStage;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WorldProjection;
import com.yucareux.tellus.worldgen.ExperimentalHeightSupport;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class TerrainPreloadScreen extends Screen {
   private static final int MAX_PANEL_WIDTH = 216;
   private static final int MIN_PANEL_WIDTH = 196;
   private static final int PANEL_MARGIN = 8;
   private static final int PANEL_PADDING = 8;
   private static final int CONTROL_HEIGHT = 18;
   private static final int CONTROL_GAP = 4;
   private static final int TEXT_WHITE = 0xFFFFFFFF;
   private static final int TEXT_MUTED = 0xFFC0C0C0;
   private static final int TEXT_SUMMARY = 0xFFE0E0E0;
   private static final Component TITLE = Component.translatable("tellus.preload.title");

   private final EarthCustomizeScreen parent;
   private SlippyMapWidget mapWidget;
   private PlaceSearchWidget searchWidget;
   private TerrainPreloadScreen.SelectionComponent selectionComponent;
   private MarkerMapComponent centerMarker;
   private TerrainPreloadSettingsOverrides overrides;
   private TerrainPreloadArea area;
   private int chunksPerSide = TerrainPreloadArea.DEFAULT_CHUNKS_PER_SIDE;
   private boolean manualMode;
   private TerrainPreloadScreen.Panel openPanel = TerrainPreloadScreen.Panel.NONE;
   private TerrainPreloadJob job;
   private boolean confirmingStart;
   private boolean confirmingCancel;
   private boolean suppressMapRelease;
   private Component validationError;

   private Button pregenerationButton;
   private Button worldButton;
   private Button modeButton;
   private EditBox chunkBox;
   private EditBox scaleBox;
   /** Whether the World Scale box is expressed in metres per block at the area centre (the future spawn) instead of at the equator. */
   private final boolean worldScaleAtSpawn;
   private final boolean centerWorldOnSpawn;
   private double displayedWorldScale;
   private Button roadsButton;
   private Button buildingsButton;
   private Button waterButton;
   private Button structuresButton;
   private Button cavesButton;
   private Button oresButton;
   private Button geologyButton;
   private Button thinShellButton;
   private Button experimentalHeightButton;
   private Button startButton;
   private Button bottomCancelButton;
   private Button pauseButton;
   private Button resumeButton;
   private Button runningCancelButton;
   private Button confirmStartButton;
   private Button confirmCancelButton;
   private Button confirmCancelYesButton;
   private Button confirmCancelNoButton;
   private Button loadNowButton;
   private Button goBackButton;

   public TerrainPreloadScreen(EarthCustomizeScreen parent) {
      super(TITLE);
      this.parent = Objects.requireNonNull(parent, "parent");
      EarthGeneratorSettings settings = parent.currentGeneratorSettings();
      this.overrides = TerrainPreloadSettingsOverrides.from(settings);
      this.worldScaleAtSpawn = settings.worldScaleAtSpawn();
      this.centerWorldOnSpawn = settings.centerWorldOnSpawn();
      this.displayedWorldScale = settings.displayedWorldScale();
      this.area = TerrainPreloadArea.centered(parent.getSpawnLatitude(), parent.getSpawnLongitude(), this.chunksPerSide, settings.projection());
   }

   @Override
   protected void init() {
      MapScreenResources.close(this.mapWidget, this.searchWidget);

      this.mapWidget = new SlippyMapWidget(0, 0, this.width, this.height);
      this.mapWidget.setAttributionBottomPadding(34);
      this.selectionComponent = new TerrainPreloadScreen.SelectionComponent(() -> this.area, this::applyManualCenter, this::applyManualChunks, this.centerWorldOnSpawn);
      this.mapWidget.addComponent(this.selectionComponent);
      this.centerMarker = this.mapWidget.addComponent(new MarkerMapComponent(new SlippyMapPoint(this.area.centerLatitude(), this.area.centerLongitude())));
      this.mapWidget.getMap().focus(this.area.centerLatitude(), this.area.centerLongitude(), 11);
      Geocoder geocoder = new NominatimGeocoder(() -> this.minecraft == null ? null : this.minecraft.getLanguageManager().getSelected());
      this.searchWidget = new PlaceSearchWidget(12, 12, 240, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableOnly(new TerrainPreloadScreen.OverlayLayer());
      this.addRenderableWidget(this.searchWidget);
      this.addTopControls();
      this.addPanelControls();
      this.addBottomControls();
      this.addWidget(this.mapWidget);
      this.refreshWidgets();
   }

   private void addTopControls() {
      int panelWidth = this.panelWidth();
      int x = this.width - panelWidth - PANEL_MARGIN;
      this.pregenerationButton = this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.panel.pregeneration"), button -> this.togglePanel(TerrainPreloadScreen.Panel.PREGENERATION))
            .bounds(x, 12, panelWidth, CONTROL_HEIGHT)
            .build()
      );
      this.worldButton = this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.panel.world"), button -> this.togglePanel(TerrainPreloadScreen.Panel.WORLD))
            .bounds(x, 12 + CONTROL_HEIGHT + CONTROL_GAP, panelWidth, CONTROL_HEIGHT)
            .build()
      );
   }

   private void addPanelControls() {
      int panelWidth = this.panelWidth();
      int x = this.width - panelWidth - PANEL_MARGIN;
      int y = this.panelY();
      int innerX = x + PANEL_PADDING;
      int innerWidth = panelWidth - PANEL_PADDING * 2;
      int columnGap = 6;
      int columnWidth = (innerWidth - columnGap) / 2;
      int rightColumnX = innerX + columnWidth + columnGap;
      this.modeButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.toggleAreaMode()).bounds(innerX, y + 24, innerWidth, CONTROL_HEIGHT).build());
      this.chunkBox = new EditBox(this.font, innerX, y + 46, innerWidth, CONTROL_HEIGHT, Component.translatable("tellus.preload.chunks_per_side"));
      this.chunkBox.setValue(Integer.toString(this.chunksPerSide));
      this.addRenderableWidget(this.chunkBox);
      this.scaleBox = new EditBox(this.font, innerX, y + 36, innerWidth, CONTROL_HEIGHT, Component.translatable("tellus.preload.world_scale"));
      this.scaleBox.setValue(formatScale(this.displayedWorldScale));
      this.addRenderableWidget(this.scaleBox);
      this.roadsButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withEnableRoads(!this.overrides.enableRoads()))).bounds(innerX, y + 60, columnWidth, CONTROL_HEIGHT).build());
      this.buildingsButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withEnableBuildings(!this.overrides.enableBuildings()))).bounds(rightColumnX, y + 60, columnWidth, CONTROL_HEIGHT).build());
      this.waterButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withEnableWater(!this.overrides.enableWater()))).bounds(innerX, y + 82, columnWidth, CONTROL_HEIGHT).build());
      this.structuresButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withAddStructures(!this.overrides.addStructures()))).bounds(rightColumnX, y + 82, columnWidth, CONTROL_HEIGHT).build());
      this.cavesButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withCaveGeneration(!this.overrides.caveGeneration()))).bounds(innerX, y + 104, columnWidth, CONTROL_HEIGHT).build());
      this.oresButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withOreDistribution(!this.overrides.oreDistribution()))).bounds(rightColumnX, y + 104, columnWidth, CONTROL_HEIGHT).build());
      this.geologyButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withGeologicalStonePatches(!this.overrides.geologicalStonePatches()))).bounds(innerX, y + 126, columnWidth, CONTROL_HEIGHT).build());
      this.thinShellButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> this.setOverrides(this.overrides.withThinShellTerrain(!this.overrides.thinShellTerrain()))).bounds(rightColumnX, y + 126, columnWidth, CONTROL_HEIGHT).build());
      this.experimentalHeightButton = this.addRenderableWidget(
         Button.builder(Component.empty(), button -> {
            if (this.experimentalHeightAvailable()) {
               this.setOverrides(this.overrides.withExperimentalIncreaseHeight(!this.overrides.experimentalIncreaseHeight()));
            }
         }).bounds(innerX, y + 148, innerWidth, CONTROL_HEIGHT).build()
      );
      this.experimentalHeightButton.setTooltip(Tooltip.create(experimentalIncreaseHeightTooltip()));
   }

   private void addBottomControls() {
      int buttonY = this.height - 30;
      this.startButton = this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.start").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), button -> this.confirmingStart = true)
            .bounds(this.width / 2 - 154, buttonY, 150, 20)
            .build()
      );
      this.bottomCancelButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.onClose()).bounds(this.width / 2 + 4, buttonY, 150, 20).build()
      );
      this.pauseButton = this.addRenderableWidget(Button.builder(Component.translatable("tellus.preload.pause"), button -> {
         if (this.job != null) {
            this.job.pause();
         }
      }).bounds(12, buttonY, 72, 20).build());
      this.resumeButton = this.addRenderableWidget(Button.builder(Component.translatable("tellus.preload.resume"), button -> {
         if (this.job != null) {
            this.job.resume();
         }
      }).bounds(90, buttonY, 72, 20).build());
      this.runningCancelButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel").withStyle(ChatFormatting.RED), button -> this.confirmingCancel = true).bounds(168, buttonY, 72, 20).build()
      );
      this.loadNowButton = this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.load_now"), button -> this.loadIntoWorld()).bounds(this.width / 2 - 184, buttonY, 180, 20).build()
      );
      this.goBackButton = this.addRenderableWidget(Button.builder(Component.translatable("tellus.preload.go_back"), button -> this.onClose()).bounds(this.width / 2 + 4, buttonY, 180, 20).build());
      this.confirmCancelButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.confirmingStart = false).bounds(this.width / 2 - 154, this.height / 2 + 78, 150, 20).build()
      );
      this.confirmStartButton = this.addRenderableWidget(
         Button.builder(Component.translatable("tellus.preload.confirm").withStyle(ChatFormatting.GREEN), button -> this.startPreload())
            .bounds(this.width / 2 + 4, this.height / 2 + 78, 150, 20)
            .build()
      );
      this.confirmCancelYesButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.yes").withStyle(ChatFormatting.RED), button -> {
            if (this.job != null) {
               this.job.cancel();
            }

            this.confirmingCancel = false;
         }).bounds(this.width / 2 - 154, this.height / 2 + 36, 150, 20).build()
      );
      this.confirmCancelNoButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.no"), button -> this.confirmingCancel = false).bounds(this.width / 2 + 4, this.height / 2 + 36, 150, 20).build()
      );
   }

   @Override
   public void tick() {
      super.tick();
      if (this.searchWidget != null) {
         this.searchWidget.tick();
      }

      this.applyEditableInputs();
      this.refreshWidgets();
   }

   private void applyEditableInputs() {
      if (!this.isEditable()) {
         return;
      }

      if (!this.manualMode) {
         try {
            int nextChunks = TerrainPreloadArea.clampChunksPerSide(Integer.parseInt(this.chunkBox.getValue().trim()));
            if (nextChunks != this.chunksPerSide) {
               this.chunksPerSide = nextChunks;
               this.rebuildArea();
            }
         } catch (NumberFormatException ignored) {
         }
      }

      try {
         double scale = Double.parseDouble(this.scaleBox.getValue().trim());
         if (scale > 0.0 && Math.abs(scale - this.displayedWorldScale) > 0.001) {
            this.displayedWorldScale = scale;
            double equatorialWorldScale = this.equatorialWorldScaleFor(this.area.centerLatitude());
            this.overrides = this.overrides.withWorldScale(equatorialWorldScale);
            this.displayedWorldScale = EarthGeneratorSettings.displayedWorldScale(
               equatorialWorldScale, this.area.centerLatitude(), this.worldScaleAtSpawn
            );
            if (Math.abs(this.displayedWorldScale - scale) > 1.0E-6) {
               this.scaleBox.setValue(formatScale(this.displayedWorldScale));
            }
            this.rebuildArea();
         }
      } catch (NumberFormatException ignored) {
      }
   }

   private void refreshWidgets() {
      TerrainPreloadProgress progress = this.currentProgress();
      boolean running = isRunning(progress.stage());
      boolean complete = progress.stage() == TerrainPreloadStage.COMPLETE;
      boolean editable = this.isEditable() && !this.confirmingStart && !this.confirmingCancel;
      if (this.centerMarker != null) {
         this.centerMarker.moveMarker(this.area.centerLatitude(), this.area.centerLongitude());
         this.centerMarker.setVisible(true);
      }

      this.selectionComponent.setManualMode(this.manualMode);
      this.selectionComponent.setEditable(editable);
      this.selectionComponent.setDownloadProgress(progress.stage() == TerrainPreloadStage.DOWNLOADING ? progress.unitProgress() : complete ? 1.0 : 0.0);
      this.pregenerationButton.active = editable;
      this.worldButton.active = editable;
      this.startButton.visible = editable;
      this.startButton.active = editable;
      this.bottomCancelButton.visible = editable;
      this.pauseButton.visible = running;
      this.resumeButton.visible = running;
      this.runningCancelButton.visible = running;
      this.pauseButton.active = running && !progress.paused();
      this.resumeButton.active = running && progress.paused();
      this.runningCancelButton.active = running;
      this.loadNowButton.visible = complete;
      this.goBackButton.visible = complete;
      this.confirmStartButton.visible = this.confirmingStart;
      this.confirmCancelButton.visible = this.confirmingStart;
      this.confirmCancelYesButton.visible = this.confirmingCancel;
      this.confirmCancelNoButton.visible = this.confirmingCancel;
      boolean showPregen = editable && this.openPanel == TerrainPreloadScreen.Panel.PREGENERATION;
      boolean showWorld = editable && this.openPanel == TerrainPreloadScreen.Panel.WORLD;
      this.modeButton.visible = showPregen;
      this.chunkBox.visible = showPregen;
      this.chunkBox.active = showPregen && !this.manualMode;
      this.modeButton.setMessage(
         Component.translatable(
            "tellus.preload.area_mode",
            Component.translatable(this.manualMode ? "tellus.preload.area_mode.manual" : "tellus.preload.area_mode.chunks")
         )
      );
      this.scaleBox.visible = showWorld;
      this.roadsButton.visible = showWorld;
      this.buildingsButton.visible = showWorld;
      this.waterButton.visible = showWorld;
      this.structuresButton.visible = showWorld;
      this.cavesButton.visible = showWorld;
      this.oresButton.visible = showWorld;
      this.geologyButton.visible = showWorld;
      this.thinShellButton.visible = showWorld;
      this.experimentalHeightButton.visible = showWorld;
      this.experimentalHeightButton.active = showWorld && this.experimentalHeightAvailable();
      this.roadsButton.setMessage(toggleLabel("tellus.preload.toggle.roads", this.overrides.enableRoads()));
      this.buildingsButton.setMessage(toggleLabel("tellus.preload.toggle.buildings", this.overrides.enableBuildings()));
      this.waterButton.setMessage(toggleLabel("tellus.preload.toggle.water", this.overrides.enableWater()));
      this.structuresButton.setMessage(toggleLabel("tellus.preload.toggle.structures", this.overrides.addStructures()));
      this.cavesButton.setMessage(toggleLabel("tellus.preload.toggle.caves", this.overrides.caveGeneration()));
      this.oresButton.setMessage(toggleLabel("tellus.preload.toggle.ores", this.overrides.oreDistribution()));
      this.geologyButton.setMessage(toggleLabel("tellus.preload.toggle.geology", this.overrides.geologicalStonePatches()));
      this.thinShellButton.setMessage(toggleLabel("tellus.preload.toggle.shell", this.overrides.thinShellTerrain()));
      this.experimentalHeightButton.setMessage(toggleLabel("tellus.preload.toggle.experimental_height", this.overrides.experimentalIncreaseHeight()));
   }

   private TerrainPreloadProgress currentProgress() {
      return this.job == null ? TerrainPreloadProgress.idle() : this.job.progress();
   }

   private boolean isEditable() {
      TerrainPreloadStage stage = this.currentProgress().stage();
      return stage == TerrainPreloadStage.IDLE || stage == TerrainPreloadStage.CANCELLED || stage == TerrainPreloadStage.FAILED;
   }

   private static boolean isRunning(TerrainPreloadStage stage) {
      return stage == TerrainPreloadStage.DOWNLOADING;
   }

   private void startPreload() {
      this.applyEditableInputs();
      EarthGeneratorSettings settings;
      try {
         settings = this.selectedAreaSettings();
      } catch (IllegalStateException error) {
         this.validationError = Component.translatable("tellus.preload.invalid_settings.detail").withStyle(ChatFormatting.RED);
         this.confirmingStart = false;
         return;
      }
      if (!this.validateSelection(settings)) {
         this.confirmingStart = false;
         return;
      }

      this.job = TerrainPreloadJobManager.instance().start(this.area, settings);
      this.confirmingStart = false;
      this.openPanel = TerrainPreloadScreen.Panel.NONE;
   }

   private void loadIntoWorld() {
      EarthGeneratorSettings settings = this.job == null ? this.selectedAreaSettings() : this.job.settings();
      if (!this.validateSelection(settings)) {
         return;
      }

      this.parent.applyPreloadSettings(settings);
      this.parent.onClose();
   }

   private boolean validateSelection(EarthGeneratorSettings settings) {
      try {
         ExperimentalHeightSupport.validateOrThrow(settings, EarthGeneratorSettings.resolveHeightLimits(settings));
         ExperimentalHeightSupport.validatePreloadAreaOrThrow(settings, this.area);
         this.validationError = null;
         return true;
      } catch (IllegalStateException error) {
         this.validationError = Component.translatable("tellus.preload.invalid_settings.detail").withStyle(ChatFormatting.RED);
         return false;
      }
   }

   private EarthGeneratorSettings selectedAreaSettings() {
      return this.overrides.apply(this.parent.currentGeneratorSettings(), this.area.centerLatitude(), this.area.centerLongitude());
   }

   private void handleSearch(double latitude, double longitude) {
      this.validationError = null;
      this.moveCenter(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
   }

   private void togglePanel(TerrainPreloadScreen.Panel panel) {
      this.openPanel = this.openPanel == panel ? TerrainPreloadScreen.Panel.NONE : panel;
   }

   private void toggleAreaMode() {
      this.manualMode = !this.manualMode;
   }

   private void setOverrides(TerrainPreloadSettingsOverrides overrides) {
      this.overrides = Objects.requireNonNull(overrides, "overrides");
      this.validationError = null;
      this.rebuildArea();
   }

   private void rebuildArea() {
      this.validationError = null;
      this.area = TerrainPreloadArea.centered(
         this.area.centerLatitude(), this.area.centerLongitude(), this.chunksPerSide, this.projectionFor(this.area.centerLatitude(), this.area.centerLongitude())
      );
   }

   private void applyManualChunks(int chunks) {
      int clamped = TerrainPreloadArea.clampChunksPerSide(chunks);
      if (clamped != this.chunksPerSide) {
         this.chunksPerSide = clamped;
         this.chunkBox.setValue(Integer.toString(clamped));
         this.rebuildArea();
      }
   }

   private void applyManualCenter(double latitude, double longitude) {
      this.validationError = null;
      this.moveCenter(latitude, longitude);
   }

   private void moveCenter(double latitude, double longitude) {
      if (this.worldScaleAtSpawn) {
         // The area centre becomes the world spawn, so keep the entered at-spawn scale and re-derive the stored equatorial value.
         this.overrides = this.overrides.withWorldScale(this.equatorialWorldScaleFor(latitude));
      }

      this.area = TerrainPreloadArea.centered(latitude, longitude, this.chunksPerSide, this.projectionFor(latitude, longitude));
   }

   private double equatorialWorldScaleFor(double latitude) {
      return Math.min(
         EarthGeneratorSettings.MAX_WORLD_SCALE,
         EarthGeneratorSettings.equatorialWorldScale(this.displayedWorldScale, latitude, this.worldScaleAtSpawn)
      );
   }

   private WorldProjection projectionFor(double latitude, double longitude) {
      return this.centerWorldOnSpawn
         ? WorldProjection.centeredOn(this.overrides.worldScale(), latitude, longitude)
         : WorldProjection.global(this.overrides.worldScale());
   }

   private int panelWidth() {
      int available = Math.max(120, this.width - PANEL_MARGIN * 2);
      return Math.max(Math.min(MIN_PANEL_WIDTH, available), Math.min(MAX_PANEL_WIDTH, available));
   }

   private int panelY() {
      return 12 + CONTROL_HEIGHT * 2 + CONTROL_GAP + 6;
   }

   private boolean experimentalHeightAvailable() {
      return ExperimentalHeightSupport.isRuntimeProfileActive();
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean isPrimary) {
      if (this.isSearchOverlayMouseOver(event.x(), event.y())) {
         this.suppressMapRelease = true;
         this.cancelMapInteraction();
         this.setFocused(this.searchWidget);
         this.searchWidget.setFocused(true);
         this.searchWidget.mouseClicked(event, isPrimary);
         return true;
      }

      this.suppressMapRelease = false;
      return super.mouseClicked(event, isPrimary);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (this.suppressMapRelease || this.isSearchOverlayMouseOver(event.x(), event.y())) {
         this.suppressMapRelease = false;
         this.cancelMapInteraction();
         return true;
      }

      return super.mouseReleased(event);
   }

   @Override
   public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, this.title, this.width / 2, 4, TEXT_WHITE);
   }

   private void drawPanel(GuiGraphicsExtractor graphics) {
      if (this.openPanel == TerrainPreloadScreen.Panel.NONE || !this.isEditable()) {
         return;
      }

      int panelWidth = this.panelWidth();
      int x = this.width - panelWidth - PANEL_MARGIN;
      int y = this.panelY();
      int height = this.openPanel == TerrainPreloadScreen.Panel.WORLD ? 176 : 72;
      graphics.fill(x, y, x + panelWidth, y + height, -1442840576);
      graphics.outline(x, y, panelWidth, height, -6250336);
      graphics.text(
         this.font,
         Component.translatable(
            this.openPanel == TerrainPreloadScreen.Panel.WORLD ? "tellus.preload.panel.world" : "tellus.preload.panel.pregeneration"
         ),
         x + PANEL_PADDING,
         y + 8,
         TEXT_WHITE
      );
      if (this.openPanel == TerrainPreloadScreen.Panel.PREGENERATION) {
         graphics.text(this.font, Component.translatable("tellus.preload.chunks_per_side"), x + PANEL_PADDING, y + 58, TEXT_MUTED);
      } else {
         graphics.text(this.font, Component.translatable("tellus.preload.world_scale"), x + PANEL_PADDING, y + 24, TEXT_MUTED);
      }
   }

   private void drawProgress(GuiGraphicsExtractor graphics) {
      TerrainPreloadProgress progress = this.currentProgress();
      if (!isRunning(progress.stage()) && progress.stage() != TerrainPreloadStage.COMPLETE && progress.stage() != TerrainPreloadStage.FAILED) {
         return;
      }

      int boxWidth = Math.min(760, this.width - 40);
      int boxHeight = 78;
      int x = (this.width - boxWidth) / 2;
      int y = Math.max(34, this.height - boxHeight - 42);
      graphics.fill(x, y, x + boxWidth, y + boxHeight, -1442840576);
      graphics.outline(x, y, boxWidth, boxHeight, -6250336);
      graphics.text(this.font, this.fitText(TerrainPreloadText.status(progress.status()).getString(), boxWidth - 20), x + 10, y + 6, TEXT_WHITE);
      String detail = this.fitText(TerrainPreloadText.detail(progress.detail()).getString(), boxWidth - 20);
      graphics.text(this.font, detail, x + 10, y + 18, TEXT_MUTED);
      String sources = this.fitText(
         Component.translatable("tellus.preload.progress.sources", TerrainPreloadText.sources(progress.sourceDetail())).getString(),
         boxWidth - 20
      );
      graphics.text(this.font, sources, x + 10, y + 30, TEXT_MUTED);
      String metrics = this.fitText(this.progressMetrics(progress), boxWidth - 20);
      graphics.text(this.font, metrics, x + 10, y + 42, TEXT_MUTED);
      int barX = x + 10;
      int barY = y + 62;
      int barWidth = boxWidth - 20;
      graphics.fill(barX, barY, barX + barWidth, barY + 6, -16777216);
      graphics.fill(barX, barY, barX + (int)Math.round(barWidth * progress.unitProgress()), barY + 6, 0xAA32C864);
   }

   private void drawStartConfirmation(GuiGraphicsExtractor graphics) {
      int x = this.width / 2 - 180;
      int y = this.height / 2 - 92;
      graphics.fill(x, y, x + 360, y + 160, -1342177280);
      graphics.outline(x, y, 360, 160, -1);
      graphics.centeredText(this.font, Component.translatable("tellus.preload.confirm_title"), this.width / 2, y + 10, TEXT_WHITE);
      int textX = x + 16;
      int textY = y + 32;
      graphics.text(this.font, TerrainPreloadText.areaSummary(this.area), textX, textY, TEXT_SUMMARY);
      graphics.text(
         this.font, Component.translatable("tellus.preload.summary.world_scale", formatScale(this.displayedWorldScale)), textX, textY + 14, TEXT_SUMMARY
      );
      graphics.text(this.font, toggleLabel("tellus.preload.toggle.roads", this.overrides.enableRoads()), textX, textY + 28, TEXT_MUTED);
      graphics.text(this.font, toggleLabel("tellus.preload.toggle.buildings", this.overrides.enableBuildings()), textX, textY + 42, TEXT_MUTED);
      graphics.text(this.font, toggleLabel("tellus.preload.toggle.water", this.overrides.enableWater()), textX, textY + 56, TEXT_MUTED);
      graphics.text(this.font, toggleLabel("tellus.preload.toggle.structures", this.overrides.addStructures()), textX, textY + 70, TEXT_MUTED);
      graphics.text(
         this.font,
         toggleLabel("tellus.preload.toggle.caves", this.overrides.caveGeneration())
            .copy()
            .append("  ")
            .append(toggleLabel("tellus.preload.toggle.ores", this.overrides.oreDistribution()))
            .append("  ")
            .append(toggleLabel("tellus.preload.toggle.geology", this.overrides.geologicalStonePatches())),
         textX,
         textY + 84,
         TEXT_MUTED
      );
   }

   private void drawCancelConfirmation(GuiGraphicsExtractor graphics) {
      int x = this.width / 2 - 170;
      int y = this.height / 2 - 56;
      graphics.fill(x, y, x + 340, y + 84, -1342177280);
      graphics.outline(x, y, 340, 84, -1);
      graphics.centeredText(this.font, Component.translatable("tellus.preload.cancel_question"), this.width / 2, y + 12, TEXT_WHITE);
      graphics.centeredText(this.font, Component.translatable("tellus.preload.cancel_cache_note"), this.width / 2, y + 28, TEXT_MUTED);
   }

   private void drawValidationError(GuiGraphicsExtractor graphics) {
      if (this.validationError == null) {
         return;
      }

      int messageWidth = Math.min(760, Math.max(120, this.width - 24));
      graphics.textWithWordWrap(this.font, this.validationError, (this.width - messageWidth) / 2, 28, messageWidth, 0xFFFF5555, true);
   }

   @Override
   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.gui.setScreen(this.parent);
      }
   }

   @Override
   public void removed() {
      MapScreenResources.close(this.mapWidget, this.searchWidget);
   }

   private boolean isSearchOverlayMouseOver(double mouseX, double mouseY) {
      return this.searchWidget != null && this.searchWidget.isMouseOver(mouseX, mouseY);
   }

   private void cancelMapInteraction() {
      if (this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
   }

   private static Component toggleLabel(String nameKey, boolean enabled) {
      return Component.translatable(
         "tellus.preload.toggle.format",
         Component.translatable(nameKey),
         Component.translatable(enabled ? "options.on" : "options.off")
      );
   }

   private static Component experimentalIncreaseHeightTooltip() {
      return Component.translatable("property.tellus.experimental_increase_height.tooltip")
         .withStyle(ChatFormatting.GRAY)
         .append(Component.literal("\n"))
         .append(Component.translatable("property.tellus.experimental_increase_height.warning").withStyle(ChatFormatting.RED));
   }

   private static String formatScale(double value) {
      return String.format(Locale.ROOT, "%.2f", value);
   }

   private String fitText(String text, int maxWidth) {
      if (this.font.width(text) <= maxWidth) {
         return text;
      }

      String suffix = "...";
      int targetWidth = Math.max(0, maxWidth - this.font.width(suffix));
      String fitted = text;
      while (!fitted.isEmpty() && this.font.width(fitted) > targetWidth) {
         fitted = fitted.substring(0, fitted.length() - 1);
      }

      return fitted + suffix;
   }

   private String progressMetrics(TerrainPreloadProgress progress) {
      String units = Component.translatable(
         "tellus.preload.metrics.tasks", formatCount(progress.completedUnits()), formatCount(progress.totalUnits())
      ).getString();
      String percent = String.format(Locale.ROOT, "%.1f%%", progress.unitProgress() * 100.0);
      String speed = progress.bytesRead() > 0L
         ? formatBytesPerSecond(progress.bytesPerSecond())
         : formatUnitsPerSecond(progress.unitsPerSecond());
      String byteProgress = formatByteProgress(progress);
      String eta = formatDuration(progress.estimatedRemainingMillis());
      String workers = progress.activeWorkers() > 0
         ? Component.translatable("tellus.preload.metrics.active_workers", progress.activeWorkers()).getString()
         : "";
      String paused = progress.paused() ? Component.translatable("tellus.preload.metrics.paused").getString() : "";
      return Component.translatable("tellus.preload.metrics.summary", units, percent, speed, byteProgress, eta, workers, paused).getString();
   }

   private static String formatUnitsPerSecond(double unitsPerSecond) {
      return Component.translatable(
         "tellus.preload.metrics.tasks_per_second", String.format(Locale.ROOT, "%.2f", Math.max(0.0, unitsPerSecond))
      ).getString();
   }

   private static String formatBytesPerSecond(double bytesPerSecond) {
      double value = Math.max(0.0, bytesPerSecond);
      String[] units = new String[]{"B/s", "KB/s", "MB/s", "GB/s"};
      int unit = 0;
      while (value >= 1024.0 && unit < units.length - 1) {
         value /= 1024.0;
         unit++;
      }

      return String.format(Locale.ROOT, value >= 10.0 ? "%.1f %s" : "%.2f %s", value, units[unit]);
   }

   private static String formatByteProgress(TerrainPreloadProgress progress) {
      if (progress.bytesExpected() > progress.bytesRead()) {
         long remaining = Math.max(0L, progress.bytesExpected() - progress.bytesRead());
         return Component.translatable(
            "tellus.preload.metrics.bytes_remaining",
            formatBytes(progress.bytesRead()),
            formatBytes(progress.bytesExpected()),
            formatBytes(remaining)
         ).getString();
      } else if (progress.bytesRead() > 0L) {
         return Component.translatable("tellus.preload.metrics.bytes_downloaded", formatBytes(progress.bytesRead())).getString();
      } else {
         return "";
      }
   }

   private static String formatBytes(long bytes) {
      double value = Math.max(0L, bytes);
      String[] units = new String[]{"B", "KB", "MB", "GB"};
      int unit = 0;
      while (value >= 1024.0 && unit < units.length - 1) {
         value /= 1024.0;
         unit++;
      }

      return String.format(Locale.ROOT, value >= 10.0 ? "%.1f %s" : "%.2f %s", value, units[unit]);
   }

   private static String formatDuration(long millis) {
      if (millis < 0L) {
         return Component.translatable("tellus.preload.duration.calculating").getString();
      }

      long seconds = Math.max(0L, Math.round(millis / 1000.0));
      long days = seconds / 86400L;
      seconds %= 86400L;
      long hours = seconds / 3600L;
      seconds %= 3600L;
      long minutes = seconds / 60L;
      seconds %= 60L;
      if (days > 0L) {
         return Component.translatable("tellus.preload.duration.days_hours", days, hours).getString();
      } else if (hours > 0L) {
         return Component.translatable("tellus.preload.duration.hours_minutes", hours, minutes).getString();
      } else if (minutes > 0L) {
         return Component.translatable("tellus.preload.duration.minutes_seconds", minutes, seconds).getString();
      } else {
         return Component.translatable("tellus.preload.duration.seconds", seconds).getString();
      }
   }

   private static String formatCount(int value) {
      return String.format(Locale.ROOT, "%,d", value);
   }

   private enum Panel {
      NONE,
      PREGENERATION,
      WORLD
   }

   private final class OverlayLayer extends AbstractWidget {
      private OverlayLayer() {
         super(0, 0, TerrainPreloadScreen.this.width, TerrainPreloadScreen.this.height, Component.empty());
      }

      @Override
      protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
         TerrainPreloadScreen.this.drawPanel(graphics);
         TerrainPreloadScreen.this.drawProgress(graphics);
         if (TerrainPreloadScreen.this.confirmingStart) {
            TerrainPreloadScreen.this.drawStartConfirmation(graphics);
         }

         if (TerrainPreloadScreen.this.confirmingCancel) {
            TerrainPreloadScreen.this.drawCancelConfirmation(graphics);
         }

         TerrainPreloadScreen.this.drawValidationError(graphics);
      }

      @Override
      protected void updateWidgetNarration(NarrationElementOutput narration) {
      }
   }

   private static final class SelectionComponent implements MapComponent {
      private static final int HANDLE_RADIUS = 7;
      private final TerrainPreloadScreen.AreaSupplier areaSupplier;
      private final TerrainPreloadScreen.CenterConsumer centerConsumer;
      private final TerrainPreloadScreen.ChunkConsumer chunkConsumer;
      private final boolean centerWorldOnSpawn;
      private boolean manualMode;
      private boolean editable = true;
      private int dragCorner = -1;
      private boolean draggingCenter;
      private double downloadProgress;

      private SelectionComponent(
         TerrainPreloadScreen.AreaSupplier areaSupplier, TerrainPreloadScreen.CenterConsumer centerConsumer,
          TerrainPreloadScreen.ChunkConsumer chunkConsumer, boolean centerWorldOnSpawn
       ) {
         this.areaSupplier = areaSupplier;
         this.centerConsumer = centerConsumer;
         this.chunkConsumer = chunkConsumer;
         this.centerWorldOnSpawn = centerWorldOnSpawn;
      }

      private void setManualMode(boolean manualMode) {
         this.manualMode = manualMode;
      }

      private void setEditable(boolean editable) {
         this.editable = editable;
      }

      private void setDownloadProgress(double progress) {
         this.downloadProgress = Math.max(0.0, Math.min(1.0, progress));
      }

      @Override
      public void onDrawMap(SlippyMap map, GuiGraphicsExtractor graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
         TerrainPreloadArea area = this.areaSupplier.get();
         int[] rect = this.rect(map, area);
         int x = rect[0];
         int y = rect[1];
         int w = Math.max(1, rect[2] - rect[0]);
         int h = Math.max(1, rect[3] - rect[1]);
         if (this.downloadProgress > 0.0) {
            graphics.fill(x, y, x + (int)Math.round(w * this.downloadProgress), y + h, 0x6632C864);
         }

         graphics.outline(x, y, w, h, this.editable ? 0xFFFFFFFF : 0xFF808080);
         if (this.manualMode && this.editable) {
            this.drawHandle(graphics, x, y);
            this.drawHandle(graphics, x + w, y);
            this.drawHandle(graphics, x, y + h);
            this.drawHandle(graphics, x + w, y + h);
         }
      }

      @Override
      public boolean onMouseClicked(SlippyMap map, SlippyMapPoint mouse, int button) {
         if (!this.editable) {
            return false;
         }

         if (button == 1) {
            this.centerConsumer.accept(mouse.getLatitude(), mouse.getLongitude());
            return true;
         }

         if (button != 0) {
            return false;
         }

         if (this.manualMode) {
            this.dragCorner = this.hitCorner(map, mouse);
            if (this.dragCorner >= 0) {
               return true;
            }
         }

         this.draggingCenter = this.hitSelection(map, mouse);
         return this.draggingCenter;
      }

      @Override
      public boolean onMouseDragged(SlippyMap map, SlippyMapPoint mouse, int button, double dragX, double dragY) {
         if (!this.editable) {
            return false;
         }

         if (this.draggingCenter) {
            this.centerConsumer.accept(mouse.getLatitude(), mouse.getLongitude());
            return true;
         }

         if (this.dragCorner < 0 || !this.manualMode) {
            return false;
         }

         TerrainPreloadArea area = this.areaSupplier.get();
         WorldProjection projection = this.centerWorldOnSpawn
            ? WorldProjection.centeredOn(area.worldScale(), area.centerLatitude(), area.centerLongitude())
            : WorldProjection.global(area.worldScale());
         double centerBlockX = projection.lonToBlockX(area.centerLongitude());
         double centerBlockZ = projection.latToBlockZ(area.centerLatitude());
         double mouseBlockX = projection.lonToBlockX(mouse.getLongitude());
         double mouseBlockZ = projection.latToBlockZ(mouse.getLatitude());
         double halfSideBlocks = Math.max(Math.abs(mouseBlockX - centerBlockX), Math.abs(mouseBlockZ - centerBlockZ));
         int chunks = TerrainPreloadArea.clampChunksPerSide((int)Math.ceil(halfSideBlocks * 2.0 / TerrainPreloadArea.CHUNK_SIZE));
         this.chunkConsumer.accept(chunks);
         return true;
      }

      @Override
      public boolean onMouseReleased(SlippyMap map, SlippyMapPoint mouse, int button) {
         boolean handled = this.dragCorner >= 0 || this.draggingCenter;
         this.dragCorner = -1;
         this.draggingCenter = false;
         return handled;
      }

      private void drawHandle(GuiGraphicsExtractor graphics, int x, int y) {
         graphics.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFFFFF);
      }

      private int hitCorner(SlippyMap map, SlippyMapPoint mouse) {
         TerrainPreloadArea area = this.areaSupplier.get();
         int[] rect = this.rect(map, area);
         int zoom = map.getCameraZoom();
         int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
         int mouseX = (mouse.getX(zoom) - map.getCameraX()) / scale;
         int mouseY = (mouse.getY(zoom) - map.getCameraY()) / scale;
         int[][] corners = new int[][]{{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}};
         for (int i = 0; i < corners.length; i++) {
            if (Math.abs(mouseX - corners[i][0]) <= HANDLE_RADIUS && Math.abs(mouseY - corners[i][1]) <= HANDLE_RADIUS) {
               return i;
            }
         }

         return -1;
      }

      private boolean hitSelection(SlippyMap map, SlippyMapPoint mouse) {
         int[] rect = this.rect(map, this.areaSupplier.get());
         int zoom = map.getCameraZoom();
         int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
         int mouseX = (mouse.getX(zoom) - map.getCameraX()) / scale;
         int mouseY = (mouse.getY(zoom) - map.getCameraY()) / scale;
         return mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3];
      }

      private int[] rect(SlippyMap map, TerrainPreloadArea area) {
         int zoom = map.getCameraZoom();
         int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
         SlippyMapPoint nw = new SlippyMapPoint(area.northLatitude(), area.westLongitude());
         SlippyMapPoint se = new SlippyMapPoint(area.southLatitude(), area.eastLongitude());
         int x1 = (nw.getX(zoom) - map.getCameraX()) / scale;
         int y1 = (nw.getY(zoom) - map.getCameraY()) / scale;
         int x2 = (se.getX(zoom) - map.getCameraX()) / scale;
         int y2 = (se.getY(zoom) - map.getCameraY()) / scale;
         return new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
      }
   }

   @FunctionalInterface
   private interface AreaSupplier {
      TerrainPreloadArea get();
   }

   @FunctionalInterface
   private interface CenterConsumer {
      void accept(double latitude, double longitude);
   }

   @FunctionalInterface
   private interface ChunkConsumer {
      void accept(int chunksPerSide);
   }
}
