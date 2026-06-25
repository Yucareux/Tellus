package com.yucareux.tellus.client.screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@OnlyIn(Dist.CLIENT)
public class EarthSpawnpointScreen extends Screen {
   private final EarthCustomizeScreen parent;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;
   private boolean suppressMapRelease;

   public EarthSpawnpointScreen(EarthCustomizeScreen parent) {
      super(Component.translatable("gui.earth.spawnpoint"));
      this.parent = parent;
   }

   protected void init() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      int mapX = 0;
      int mapY = 0;
      int mapWidth = this.width;
      int mapHeight = this.height;
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.mapWidget.setAttributionBottomPadding(28);
      double latitude = this.parent.getSpawnLatitude();
      double longitude = this.parent.getSpawnLongitude();
      this.markerComponent = new MarkerMapComponent(new SlippyMapPoint(latitude, longitude)).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(latitude, longitude, 4);
      Geocoder geocoder = new NominatimGeocoder();
      this.searchWidget = new PlaceSearchWidget(mapX + 12, mapY + 12, 220, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);
      int buttonY = this.height - 28;
      this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null) {
            this.parent.applySpawnpoint(marker.getLatitude(), marker.getLongitude());
         }

         this.minecraft.setScreen(this.parent);
      }).bounds(this.width / 2 - 154, buttonY, 150, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.cancel"), button -> this.minecraft.setScreen(this.parent))
            .bounds(this.width / 2 + 4, buttonY, 150, 20)
            .build()
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
      return super.mouseClicked(mouseX, mouseY, button);
   }

   @Override
   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.suppressMapRelease || this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = false;
         this.cancelMapInteraction();
         return true;
      }

      return super.mouseReleased(mouseX, mouseY, button);
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
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   public void removed() {
      if (this.mapWidget != null) {
         this.mapWidget.close();
      }

      if (this.searchWidget != null) {
         this.searchWidget.close();
      }
   }

   private boolean isSearchOverlayMouseOver(double mouseX, double mouseY) {
      return this.searchWidget != null && this.searchWidget.isMouseOver(mouseX, mouseY);
   }

   private void cancelMapInteraction() {
      if (this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
   }
}
