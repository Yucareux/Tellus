package com.yucareux.tellus.client.widget.map.component;

import com.yucareux.tellus.client.teleport.TeleportWaypoint;
import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

@Environment(EnvType.CLIENT)
public final class WaypointMapComponent implements MapComponent {
   private static final int PIN_RADIUS = 4;
   private static final int PICK_RADIUS = 10;
   private static final int COLOR_SPAWN = -12001301;
   private static final int COLOR_WAYPOINT = -13382401;
   private static final int COLOR_SELECTED = -29218;
   private final Supplier<List<TeleportWaypoint>> waypointSupplier;
   private final Supplier<String> selectedWaypointSupplier;
   private final Consumer<String> waypointClickHandler;

   public WaypointMapComponent(
      Supplier<List<TeleportWaypoint>> waypointSupplier,
      Supplier<String> selectedWaypointSupplier,
      Consumer<String> waypointClickHandler
   ) {
      this.waypointSupplier = Objects.requireNonNull(waypointSupplier, "waypointSupplier");
      this.selectedWaypointSupplier = Objects.requireNonNull(selectedWaypointSupplier, "selectedWaypointSupplier");
      this.waypointClickHandler = Objects.requireNonNull(waypointClickHandler, "waypointClickHandler");
   }

   @Override
   public void onDrawMap(SlippyMap map, GuiGraphics graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
      int zoom = map.getCameraZoom();
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      String selectedId = this.selectedWaypointSupplier.get();

      for (TeleportWaypoint waypoint : this.waypointSupplier.get()) {
         int markerX = waypointX(waypoint, zoom) - map.getCameraX();
         int markerY = waypointY(waypoint, zoom) - map.getCameraY();
         int guiMarkerX = markerX / scale;
         int guiMarkerY = markerY / scale;
         boolean selected = waypoint.id().equals(selectedId);
         int color = selected ? COLOR_SELECTED : waypoint.initialSpawn() ? COLOR_SPAWN : COLOR_WAYPOINT;
         graphics.fill(guiMarkerX - PIN_RADIUS - 1, guiMarkerY - PIN_RADIUS - 1, guiMarkerX + PIN_RADIUS + 2, guiMarkerY + PIN_RADIUS + 2, -16777216);
         graphics.fill(guiMarkerX - PIN_RADIUS, guiMarkerY - PIN_RADIUS, guiMarkerX + PIN_RADIUS + 1, guiMarkerY + PIN_RADIUS + 1, color);
         if (selected || waypoint.initialSpawn()) {
            drawLabel(graphics, guiMarkerX + 7, guiMarkerY - 12, waypoint.label());
         }
      }
   }

   @Override
   public boolean onMouseClicked(SlippyMap map, SlippyMapPoint mouse, int button) {
      if (button != 0) {
         return false;
      }

      int zoom = map.getCameraZoom();
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      int mouseX = mouse.getX(zoom);
      int mouseY = mouse.getY(zoom);
      int pickRadius = PICK_RADIUS * scale;
      int bestDistance = pickRadius * pickRadius + 1;
      String bestWaypointId = null;

      for (TeleportWaypoint waypoint : this.waypointSupplier.get()) {
         int deltaX = waypointX(waypoint, zoom) - mouseX;
         int deltaY = waypointY(waypoint, zoom) - mouseY;
         int distance = deltaX * deltaX + deltaY * deltaY;
         if (distance < bestDistance) {
            bestDistance = distance;
            bestWaypointId = waypoint.id();
         }
      }

      if (bestWaypointId != null) {
         this.waypointClickHandler.accept(bestWaypointId);
         return true;
      }

      return false;
   }

   private static int waypointX(TeleportWaypoint waypoint, int zoom) {
      return new SlippyMapPoint(waypoint.latitude(), waypoint.longitude()).getX(zoom);
   }

   private static int waypointY(TeleportWaypoint waypoint, int zoom) {
      return new SlippyMapPoint(waypoint.latitude(), waypoint.longitude()).getY(zoom);
   }

   private static void drawLabel(GuiGraphics graphics, int x, int y, String label) {
      Font font = Minecraft.getInstance().font;
      String clipped = font.width(label) <= 112 ? label : font.plainSubstrByWidth(label, 101) + "...";
      int width = font.width(clipped);
      graphics.fill(x - 2, y - 2, x + width + 3, y + 10, -1442840576);
      graphics.drawString(font, clipped, x, y, -1, false);
   }
}
