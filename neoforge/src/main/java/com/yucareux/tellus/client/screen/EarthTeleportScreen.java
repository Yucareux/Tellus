package com.yucareux.tellus.client.screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.network.GeoTpTeleportPayload;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@OnlyIn(Dist.CLIENT)
public class EarthTeleportScreen extends Screen {
   private static final int DEFAULT_ZOOM = 6;
   
   private final Screen parent;
   private final double initialLatitude;
   private final double initialLongitude;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;

   public EarthTeleportScreen( Screen parent, double latitude, double longitude) {
      super(Component.translatable("gui.earth.teleport_map"));
      this.parent = parent;
      this.initialLatitude = latitude;
      this.initialLongitude = longitude;
   }

   protected void init() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      int mapX = 20;
      int mapY = 20;
      int mapWidth = this.width - 40;
      int mapHeight = this.height - 60;
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.markerComponent = new MarkerMapComponent(new SlippyMapPoint(this.initialLatitude, this.initialLongitude)).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(this.initialLatitude, this.initialLongitude, DEFAULT_ZOOM);
      Geocoder geocoder = new NominatimGeocoder();
      this.searchWidget = new PlaceSearchWidget(mapX + 5, mapY + 5, 200, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);
      int buttonY = this.height - 28;
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.earth.teleport"), button -> this.sendTeleport()).bounds(this.width / 2 - 154, buttonY, 150, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.closeScreen()).bounds(this.width / 2 + 4, buttonY, 150, 20).build()
      );
      this.addWidget(this.mapWidget);
   }

   protected void setInitialFocus() {
      if (this.searchWidget != null) {
         this.setInitialFocus(this.searchWidget);
      }
   }

   private void handleSearch(double latitude, double longitude) {
      this.markerComponent.moveMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, 12);
   }

   private void sendTeleport() {
      if (this.markerComponent != null) {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null && this.minecraft != null) {
            if (!true) {
               if (this.minecraft.player != null) {
                  this.minecraft.player.displayClientMessage(Component.literal("Tellus: Server does not accept GeoTP requests."), true);
               }

               this.closeScreen();
            } else {
               PacketDistributor.sendToServer(new GeoTpTeleportPayload(marker.getLatitude(), marker.getLongitude()));
               this.closeScreen();
            }
         }
      }
   }

   private void closeScreen() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   public void render( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 16777215);
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
}
