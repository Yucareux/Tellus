package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.teleport.TeleportWaypoint;
import com.yucareux.tellus.client.teleport.TeleportWaypointStore;
import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.client.widget.map.component.WaypointMapComponent;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class EarthTeleportScreen extends Screen {
   private static final int DEFAULT_ZOOM = 6;
   private static final int WAYPOINT_ROW_HEIGHT = 22;
   private static final int PANEL_PADDING = 10;
   private static final int PANEL_MIN_WIDTH = 196;
   private static final int PANEL_MAX_WIDTH = 232;
   private static final int SEARCH_WIDTH = 220;

   private final Screen parent;
   private final double spawnLatitude;
   private final double spawnLongitude;
   private final TeleportWaypointStore waypointStore;
   private final List<TeleportWaypoint> waypoints;
   private double markerLatitude;
   private double markerLongitude;
   private String selectedWaypointId;
   private String noteDraft = "";
   private int waypointScrollOffset;
   private int panelX;
   private int panelWidth;
   private int noteLabelY;
   private int coordinateY;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;
   private EditBox noteBox;
   private Button deleteButton;
   private Button teleportButton;
   private boolean suppressMapRelease;
   private boolean pendingWaypointSelectionClick;

   public EarthTeleportScreen(Screen parent, double latitude, double longitude, double spawnLatitude, double spawnLongitude) {
      super(Component.translatable("gui.earth.teleport_map"));
      this.parent = parent;
      this.markerLatitude = latitude;
      this.markerLongitude = longitude;
      this.spawnLatitude = spawnLatitude;
      this.spawnLongitude = spawnLongitude;
      this.waypointStore = TeleportWaypointStore.create(Minecraft.getInstance());
      this.waypoints = new ArrayList<>(this.waypointStore.load(spawnLatitude, spawnLongitude));
   }

   protected void init() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      if (this.searchWidget != null) {
         this.searchWidget.close();
      }

      this.panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(PANEL_MIN_WIDTH, this.width / 3));
      if (this.width - this.panelWidth < 180) {
         this.panelWidth = Math.max(180, this.width - 180);
      }

      this.panelX = this.width - this.panelWidth;
      int mapX = 8;
      int mapY = 8;
      int mapWidth = Math.max(64, this.panelX - mapX - 8);
      int mapHeight = Math.max(64, this.height - 16);
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.mapWidget.setAttributionBottomPadding(0);
      this.mapWidget.addComponent(new WaypointMapComponent(() -> this.waypoints, () -> this.selectedWaypointId, this::selectWaypointFromMap));
      this.markerComponent = new MarkerMapComponent(new SlippyMapPoint(this.markerLatitude, this.markerLongitude)).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(this.markerLatitude, this.markerLongitude, DEFAULT_ZOOM);

      Geocoder geocoder = new NominatimGeocoder();
      int searchWidth = Math.min(SEARCH_WIDTH, Math.max(96, mapWidth - 12));
      this.searchWidget = new PlaceSearchWidget(mapX + 6, mapY + 6, searchWidth, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);
      this.addWidget(this.mapWidget);

      this.layoutSidePanel();
      this.updateActionState();
   }

   private void layoutSidePanel() {
      int controlX = this.panelX + PANEL_PADDING;
      int controlWidth = Math.max(96, this.panelWidth - PANEL_PADDING * 2);
      int listY = 34;
      int actionY = Math.max(130, this.height - 80);
      int noteBoxY = Math.max(listY + 46, actionY - 34);
      int listBottom = noteBoxY - 12;
      int waypointRows = Math.max(2, (listBottom - listY) / WAYPOINT_ROW_HEIGHT);
      int maxOffset = Math.max(0, this.waypoints.size() - waypointRows);
      this.waypointScrollOffset = Math.min(this.waypointScrollOffset, maxOffset);

      int visibleRows = Math.min(waypointRows, Math.max(0, this.waypoints.size() - this.waypointScrollOffset));
      for (int i = 0; i < visibleRows; i++) {
         TeleportWaypoint waypoint = this.waypoints.get(this.waypointScrollOffset + i);
         int buttonY = listY + i * WAYPOINT_ROW_HEIGHT;
         this.addRenderableWidget(
            Button.builder(this.waypointButtonLabel(waypoint, controlWidth - 8), button -> this.selectWaypoint(waypoint.id(), true))
               .bounds(controlX, buttonY, controlWidth, 20)
               .build()
         );
      }

      if (maxOffset > 0) {
         int pageY = listY + waypointRows * WAYPOINT_ROW_HEIGHT + 2;
         int halfWidth = (controlWidth - 4) / 2;
         Button previous = Button.builder(Component.translatable("gui.earth.waypoints.previous"), button -> {
            this.captureCurrentInputs();
            this.waypointScrollOffset = Math.max(0, this.waypointScrollOffset - waypointRows);
            this.rebuildScreen();
         }).bounds(controlX, pageY, halfWidth, 20).build();
         previous.active = this.waypointScrollOffset > 0;
         this.addRenderableWidget(previous);
         Button next = Button.builder(Component.translatable("gui.earth.waypoints.next"), button -> {
            this.captureCurrentInputs();
            this.waypointScrollOffset = Math.min(maxOffset, this.waypointScrollOffset + waypointRows);
            this.rebuildScreen();
         }).bounds(controlX + halfWidth + 4, pageY, halfWidth, 20).build();
         next.active = this.waypointScrollOffset < maxOffset;
         this.addRenderableWidget(next);
      }

      this.noteLabelY = noteBoxY - 11;
      this.noteBox = new EditBox(this.font, controlX, noteBoxY, controlWidth, 20, Component.translatable("gui.earth.waypoint_note"));
      this.noteBox.setMaxLength(64);
      this.noteBox.setValue(this.noteDraft);
      this.noteBox.setHint(Component.translatable("gui.earth.waypoint_note"));
      this.addRenderableWidget(this.noteBox);
      this.coordinateY = noteBoxY + 24;
      int smallWidth = (controlWidth - 4) / 2;
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.earth.waypoint_save"), button -> this.saveWaypoint())
            .bounds(controlX, actionY, smallWidth, 20)
            .build()
      );
      this.deleteButton = Button.builder(Component.translatable("gui.earth.waypoint_delete"), button -> this.deleteWaypoint())
         .bounds(controlX + smallWidth + 4, actionY, smallWidth, 20)
         .build();
      this.addRenderableWidget(this.deleteButton);
      this.teleportButton = Button.builder(Component.translatable("gui.earth.teleport"), button -> this.sendTeleport())
         .bounds(controlX, actionY + 24, controlWidth, 20)
         .build();
      this.addRenderableWidget(this.teleportButton);
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.closeScreen()).bounds(controlX, actionY + 48, controlWidth, 20).build()
      );
   }

   protected void setInitialFocus() {
      if (this.searchWidget != null) {
         this.setInitialFocus(this.searchWidget);
      }
   }

   private void handleSearch(double latitude, double longitude) {
      this.markerLatitude = latitude;
      this.markerLongitude = longitude;
      this.selectedWaypointId = null;
      this.noteDraft = "";
      if (this.noteBox != null) {
         this.noteBox.setValue("");
      }

      this.markerComponent.moveMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
      this.updateActionState();
   }

   private void selectWaypointFromMap(String waypointId) {
      this.pendingWaypointSelectionClick = true;
      this.selectWaypoint(waypointId, false);
   }

   private void selectWaypoint(String waypointId, boolean focusMap) {
      TeleportWaypoint waypoint = this.findWaypoint(waypointId);
      if (waypoint != null) {
         this.selectedWaypointId = waypoint.id();
         this.noteDraft = waypoint.label();
         this.markerLatitude = waypoint.latitude();
         this.markerLongitude = waypoint.longitude();
         if (this.noteBox != null) {
            this.noteBox.setValue(this.noteDraft);
         }

         if (this.markerComponent != null) {
            this.markerComponent.moveMarker(this.markerLatitude, this.markerLongitude);
         }

         if (focusMap && this.mapWidget != null) {
            this.mapWidget.getMap().focus(this.markerLatitude, this.markerLongitude, 12);
         }

         this.updateActionState();
      }
   }

   private void saveWaypoint() {
      this.captureCurrentInputs();
      String label = this.noteDraft.trim();
      if (label.isEmpty()) {
         label = String.format(Locale.ROOT, "Point %d", Math.max(1, this.waypoints.size()));
      }

      TeleportWaypoint selected = this.selectedWaypoint();
      if (selected == null) {
         selected = new TeleportWaypoint(UUID.randomUUID().toString(), label, this.markerLatitude, this.markerLongitude, false);
         this.waypoints.add(selected);
         this.selectedWaypointId = selected.id();
      } else {
         selected.setLabel(label);
         if (!selected.initialSpawn()) {
            selected.setLatitude(this.markerLatitude);
            selected.setLongitude(this.markerLongitude);
         }
      }

      this.noteDraft = selected.label();
      this.waypointStore.save(this.waypoints);
      this.rebuildScreen();
   }

   private void deleteWaypoint() {
      TeleportWaypoint selected = this.selectedWaypoint();
      if (selected != null && !selected.initialSpawn()) {
         this.waypoints.remove(selected);
         this.selectedWaypointId = null;
         this.noteDraft = "";
         this.waypointStore.save(this.waypoints);
         this.rebuildScreen();
      }
   }

   private void sendTeleport() {
      this.captureCurrentMarker();
      if (this.minecraft != null) {
         if (!ClientPlayNetworking.canSend(GeoTpTeleportPayload.TYPE)) {
            if (this.minecraft.player != null) {
               this.minecraft.player.displayClientMessage(Component.literal("Tellus: Server does not accept GeoTP requests."), true);
            }

            this.closeScreen();
         } else {
            ClientPlayNetworking.send(new GeoTpTeleportPayload(this.markerLatitude, this.markerLongitude));
            this.closeScreen();
         }
      }
   }

   private void captureCurrentInputs() {
      this.captureCurrentMarker();
      if (this.noteBox != null) {
         this.noteDraft = this.noteBox.getValue();
      }
   }

   private void captureCurrentMarker() {
      if (this.markerComponent != null) {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null) {
            this.markerLatitude = marker.getLatitude();
            this.markerLongitude = marker.getLongitude();
         }
      }
   }

   private void rebuildScreen() {
      this.clearWidgets();
      this.init();
   }

   private TeleportWaypoint selectedWaypoint() {
      return this.selectedWaypointId == null ? null : this.findWaypoint(this.selectedWaypointId);
   }

   private TeleportWaypoint findWaypoint(String waypointId) {
      for (TeleportWaypoint waypoint : this.waypoints) {
         if (waypoint.id().equals(waypointId)) {
            return waypoint;
         }
      }

      return null;
   }

   private Component waypointButtonLabel(TeleportWaypoint waypoint, int maxWidth) {
      String prefix = waypoint.initialSpawn() ? "* " : "";
      String label = prefix + waypoint.label();
      if (this.font.width(label) > maxWidth) {
         label = this.font.plainSubstrByWidth(label, Math.max(8, maxWidth - this.font.width("..."))) + "...";
      }

      return Component.literal(label);
   }

   private void updateActionState() {
      TeleportWaypoint selected = this.selectedWaypoint();
      if (this.deleteButton != null) {
         this.deleteButton.active = selected != null && !selected.initialSpawn();
      }

      if (this.teleportButton != null) {
         this.teleportButton.active = this.markerComponent != null && this.markerComponent.getMarker() != null;
      }
   }

   private void closeScreen() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = true;
         this.cancelMapInteraction();
         this.setFocused(this.searchWidget);
         this.searchWidget.setFocused(true);
         this.searchWidget.mouseClicked(mouseX, mouseY, button);
         return true;
      }

      this.suppressMapRelease = false;
      this.pendingWaypointSelectionClick = false;
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.suppressMapRelease || this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = false;
         this.cancelMapInteraction();
         return true;
      }

      SlippyMapPoint before = this.markerComponent == null ? null : this.markerComponent.getMarker();
      boolean waypointClick = this.pendingWaypointSelectionClick;
      boolean handled = super.mouseReleased(mouseX, mouseY, button);
      SlippyMapPoint after = this.markerComponent == null ? null : this.markerComponent.getMarker();
      if (waypointClick && this.selectedWaypointId != null) {
         this.selectWaypoint(this.selectedWaypointId, false);
      } else if (button == 0 && this.mapWidget != null && this.mapWidget.isMouseOver(mouseX, mouseY) && markerMoved(before, after)) {
         this.captureCurrentMarker();
         if (!this.pendingWaypointSelectionClick) {
            this.selectedWaypointId = null;
            this.noteDraft = "";
            if (this.noteBox != null) {
               this.noteBox.setValue("");
            }
         }

         this.updateActionState();
      }

      this.pendingWaypointSelectionClick = false;
      return handled;
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
      graphics.fill(this.panelX, 0, this.width, this.height, -15066598);
      graphics.drawString(this.font, this.title, this.panelX + PANEL_PADDING, 12, 16777215, false);
      graphics.drawString(this.font, Component.translatable("gui.earth.waypoints"), this.panelX + PANEL_PADDING, 24, 13421772, false);
      graphics.drawString(this.font, Component.translatable("gui.earth.waypoint_note"), this.panelX + PANEL_PADDING, this.noteLabelY, 13421772, false);
      graphics.drawString(this.font, Component.literal(this.formatMarkerCoordinates()), this.panelX + PANEL_PADDING, this.coordinateY, 11184810, false);
      super.render(graphics, mouseX, mouseY, delta);
   }

   public void tick() {
      super.tick();
      if (this.searchWidget != null) {
         this.searchWidget.tick();
      }
   }

   public void onClose() {
      this.closeScreen();
   }

   public void removed() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      if (this.searchWidget != null) {
         this.searchWidget.close();
      }
   }

   private String formatMarkerCoordinates() {
      this.captureCurrentMarker();
      return String.format(Locale.ROOT, "%.5f, %.5f", this.markerLatitude, this.markerLongitude);
   }

   private boolean isSearchOverlayMouseOver(double mouseX, double mouseY) {
      return this.searchWidget != null && this.searchWidget.isMouseOver(mouseX, mouseY);
   }

   private void cancelMapInteraction() {
      if (this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
   }

   private static boolean markerMoved(SlippyMapPoint before, SlippyMapPoint after) {
      if (before == null || after == null) {
         return before != after;
      }

      return Double.compare(before.getLatitude(), after.getLatitude()) != 0 || Double.compare(before.getLongitude(), after.getLongitude()) != 0;
   }
}
