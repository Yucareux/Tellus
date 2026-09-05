package com.yucareux.tellus.client.widget.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.client.compat.AbstractTellusButton;
import com.yucareux.tellus.client.compat.AbstractTellusEditBox;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.world.data.source.Geocoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
public class PlaceSearchWidget extends AbstractTellusEditBox {
   private static final int SUGGESTION_COUNT = 5;
   private static final int SUGGESTION_HEIGHT = 20;
   private static final int SUGGESTION_GAP = 2;
   private static final long SUGGESTION_DEBOUNCE_MS = 250L;
   private static final int SEARCH_TEXT_OFFSET_X = 4;
   private static final int SUGGESTION_TEXT_PADDING = 6;
   private static final int SUGGESTION_SCROLL_PADDING = 12;
   private static final float SUGGESTION_SCROLL_SPEED = 28.0F;
   private final Geocoder geocoder;
   private final PlaceSearchWidget.SearchHandler searchHandler;
   private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
      new ThreadFactoryBuilder().setNameFormat("tellus-geocoder-%d").setDaemon(true).build()
   );
   private final List<Geocoder.Suggestion> suggestions = new ArrayList<>(SUGGESTION_COUNT);
   private final List<Button> suggestionButtons = new ArrayList<>(SUGGESTION_COUNT);
   private ScheduledFuture<?> pendingSuggestTask;
   private long suggestionRequestId;
   private PlaceSearchWidget.State state = PlaceSearchWidget.State.OK;
   private boolean pause;
   private volatile boolean closed;
   private String lastInputText = "";

   public PlaceSearchWidget(int x, int y, int width, int height, Geocoder geocoder, PlaceSearchWidget.SearchHandler searchHandler) {
      super(Minecraft.getInstance().font, x, y, width, height, Component.translatable("gui.earth.search"));
      this.geocoder = geocoder;
      this.searchHandler = searchHandler;
      this.setBordered(false);
      this.setMaxLength(256);
      this.setHint(Component.translatable("gui.earth.search"));
   }

   public void tick() {
      if (this.closed) {
         return;
      }

      String text = this.getValue().trim();
      if (!text.equals(this.lastInputText)) {
         this.lastInputText = text;
         this.state = PlaceSearchWidget.State.OK;
         this.pause = false;
         if (!text.isEmpty()) {
            this.scheduleSuggestions(text);
         }
      }

      if (this.pause || text.isEmpty()) {
         this.cancelPendingSuggest();
         this.clearSuggestions();
      }
   }

   public void renderWidget( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      int x = this.getX();
      int y = this.getY();
      int width = this.getWidth();
      int height = this.getHeight();
      graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, -6250336);
      graphics.fill(x, y, x + width, y + height, this.state.getBackgroundColor());
      ClientMinecraftCompat.pushPose(graphics);
      ClientMinecraftCompat.translatePose(graphics, (float)SEARCH_TEXT_OFFSET_X, (height - 8) / 2.0F - 1.0F);
      super.renderWidget(graphics, mouseX, mouseY, delta);
      ClientMinecraftCompat.popPose(graphics);
      if (this.shouldShowSuggestions()) {
         this.layoutSuggestionButtons();

         for (Button button : this.suggestionButtons) {
            button.render(graphics, mouseX, mouseY, delta);
         }
      }
   }

   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      if (this.isVisible() && this.shouldShowSuggestions() && button == 0) {
         for (Button suggestionButton : this.suggestionButtons) {
            if (ClientMinecraftCompat.mouseClicked(suggestionButton, mouseX, mouseY, button)) {
               return true;
            }
         }
      }

      return super.tellusMouseClicked(mouseX, mouseY, button);
   }

   public boolean isMouseOver(double mouseX, double mouseY) {
      if (super.isMouseOver(mouseX, mouseY)) {
         return true;
      } else {
         if (this.shouldShowSuggestions()) {
            for (Button button : this.suggestionButtons) {
               if (button.isMouseOver(mouseX, mouseY)) {
                  return true;
               }
            }
         }

         return false;
      }
   }

   protected boolean tellusKeyPressed(int keyCode, int scanCode, int modifiers) {
      if (!this.isFocused()) {
         return false;
      } else {
         this.pause = false;
         if (keyCode == 257 || keyCode == 335) {
            this.cancelPendingSuggest();
            this.clearSuggestions();
            this.handleAccept();
            return true;
         } else if (super.tellusKeyPressed(keyCode, scanCode, modifiers)) {
            this.state = PlaceSearchWidget.State.OK;
            return true;
         } else {
            return false;
         }
      }
   }

   public void close() {
      this.closed = true;
      this.suggestionRequestId++;
      this.cancelPendingSuggest();
      this.clearSuggestions();
      this.executor.shutdownNow();
   }

   private void handleAccept() {
      if (this.closed) {
         return;
      }

      String text = this.getValue().trim();
      if (!text.isEmpty()) {
         this.pause = true;
         this.cancelPendingSuggest();
         this.clearSuggestions();
         long requestId = ++this.suggestionRequestId;
         try {
            this.executor.execute(() -> {
               try {
                  double[] coordinate = this.geocoder.get(text);
                  Minecraft.getInstance().execute(() -> {
                     if (this.isActiveRequest(requestId)) {
                        if (coordinate == null || coordinate.length < 2 || !isValidCoordinate(coordinate[0], coordinate[1])) {
                           this.state = PlaceSearchWidget.State.NOT_FOUND;
                        } else {
                           this.searchHandler.handle(coordinate[0], coordinate[1]);
                           this.state = PlaceSearchWidget.State.FOUND;
                        }
                     }
                  });
               } catch (Exception error) {
                  Tellus.LOGGER.error("Failed to find searched place {}", text, error);
                  Minecraft.getInstance().execute(() -> {
                     if (this.isActiveRequest(requestId)) {
                        this.state = PlaceSearchWidget.State.NOT_FOUND;
                     }
                  });
               }
            });
         } catch (RejectedExecutionException error) {
            if (!this.closed) {
               Tellus.LOGGER.error("Failed to schedule place search {}", text, error);
               this.state = PlaceSearchWidget.State.NOT_FOUND;
            }
         }
      }
   }

   private void scheduleSuggestions(String text) {
      if (this.closed) {
         return;
      }

      this.cancelPendingSuggest();
      this.clearSuggestions();
      long requestId = ++this.suggestionRequestId;
      try {
         this.pendingSuggestTask = this.executor.schedule(() -> {
            Geocoder.Suggestion[] result = this.fetchSuggestions(text);
            Minecraft.getInstance().execute(() -> this.applySuggestions(requestId, text, result));
         }, SUGGESTION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException error) {
         if (!this.closed) {
            Tellus.LOGGER.error("Failed to schedule geocoder suggestions", error);
            this.state = PlaceSearchWidget.State.NOT_FOUND;
         }
      }
   }

   private void applySuggestions(long requestId, String text, Geocoder.Suggestion[] result) {
      if (this.isActiveRequest(requestId)) {
         if (!this.pause && text.equals(this.getValue().trim())) {
            this.suggestions.clear();
            if (result != null) {
               for (int i = 0; i < result.length && this.suggestions.size() < SUGGESTION_COUNT; i++) {
                  if (isValidSuggestion(result[i])) {
                     this.suggestions.add(result[i]);
                  }
               }
            }

            if (this.suggestions.isEmpty()) {
               this.state = PlaceSearchWidget.State.NOT_FOUND;
               this.suggestionButtons.clear();
            } else {
               this.state = PlaceSearchWidget.State.OK;
               this.rebuildSuggestionButtons();
            }
         }
      }
   }

   private Geocoder.Suggestion[] fetchSuggestions(String text) {
      try {
         return this.geocoder.suggest(text);
      } catch (Exception var3) {
         Tellus.LOGGER.error("Failed to get geocoder suggestions", var3);
         return new Geocoder.Suggestion[0];
      }
   }

   private void acceptSuggestion(Geocoder.Suggestion suggestion) {
      if (this.closed) {
         return;
      }
      if (!isValidSuggestion(suggestion)) {
         this.state = PlaceSearchWidget.State.NOT_FOUND;
         return;
      }

      this.pause = true;
      this.cancelPendingSuggest();
      this.suggestionRequestId++;
      this.clearSuggestions();
      String displayName = Objects.requireNonNull(suggestion.displayName(), "suggestionDisplayName");
      this.setValue(displayName);
      this.state = PlaceSearchWidget.State.FOUND;
      this.searchHandler.handle(suggestion.latitude(), suggestion.longitude());
   }

   private static boolean isValidSuggestion(Geocoder.Suggestion suggestion) {
      return suggestion != null
         && suggestion.displayName() != null
         && !suggestion.displayName().isBlank()
         && isValidCoordinate(suggestion.latitude(), suggestion.longitude());
   }

   private static boolean isValidCoordinate(double latitude, double longitude) {
      return Double.isFinite(latitude)
         && Double.isFinite(longitude)
         && latitude >= -90.0
         && latitude <= 90.0
         && longitude >= -180.0
         && longitude <= 180.0;
   }

   private boolean isActiveRequest(long requestId) {
      return !this.closed && requestId == this.suggestionRequestId;
   }

   private void rebuildSuggestionButtons() {
      this.suggestionButtons.clear();
      int x = this.getX();
      int originY = this.getY() + this.getHeight() + SUGGESTION_GAP;
      int width = this.getWidth();
      int suggestionStride = SUGGESTION_HEIGHT + SUGGESTION_GAP;

      for (int i = 0; i < this.suggestions.size(); i++) {
         Geocoder.Suggestion suggestion = this.suggestions.get(i);
         String displayName = Objects.requireNonNull(suggestion.displayName(), "suggestionDisplayName");
         Component label = Component.literal(displayName);
         Button button = new PlaceSearchWidget.SuggestionButton(
            x,
            originY + i * suggestionStride,
            width,
            SUGGESTION_HEIGHT,
            label,
            pressed -> this.acceptSuggestion(suggestion)
         );
         this.suggestionButtons.add(button);
      }
   }

   private void layoutSuggestionButtons() {
      int x = this.getX();
      int originY = this.getY() + this.getHeight() + SUGGESTION_GAP;
      int width = this.getWidth();
      int suggestionStride = SUGGESTION_HEIGHT + SUGGESTION_GAP;

      for (int i = 0; i < this.suggestionButtons.size(); i++) {
         Button button = this.suggestionButtons.get(i);
         button.setX(x);
         button.setY(originY + i * suggestionStride);
         button.setWidth(width);
         ClientMinecraftCompat.setWidgetHeight(button, SUGGESTION_HEIGHT);
      }
   }

   private void clearSuggestions() {
      this.suggestions.clear();
      this.suggestionButtons.clear();
   }

   private void cancelPendingSuggest() {
      if (this.pendingSuggestTask != null) {
         this.pendingSuggestTask.cancel(false);
         this.pendingSuggestTask = null;
      }
   }

   private boolean shouldShowSuggestions() {
      return this.isFocused() && !this.suggestions.isEmpty();
   }
   public interface SearchHandler {
      void handle(double var1, double var3);
   }
   public static enum State {
      OK(-16777216),
      FOUND(-16759296),
      NOT_FOUND(-12189696);

      private final int backgroundColor;

      private State(int backgroundColor) {
         this.backgroundColor = backgroundColor;
      }

      public int getBackgroundColor() {
         return this.backgroundColor;
      }
   }
   private static final class SuggestionButton extends AbstractTellusButton {
      private long scrollStartMillis = System.currentTimeMillis();
      private boolean wasHovering;

      private SuggestionButton(int x, int y, int width, int height, Component message, OnPress onPress) {
         super(x, y, width, height, message, onPress);
      }

      protected void renderTellusContents(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
         int background = this.isHoveredOrFocused() ? -6710887 : -8355712;
         graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), background);
         graphics.renderOutline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), -16777216);
         Component message = this.getMessage();
         int textWidth = Minecraft.getInstance().font.width(message);
         int availableWidth = Math.max(0, this.getWidth() - SUGGESTION_SCROLL_PADDING);
         int textX = this.getX() + SUGGESTION_TEXT_PADDING;
         int textY = this.getY() + (this.getHeight() - 9) / 2;
         int color = this.active ? -1 : -6250336;
         float offset = 0.0F;
         boolean hoveringText = mouseX >= textX && mouseX <= textX + availableWidth && mouseY >= this.getY() && mouseY <= this.getY() + this.getHeight();
         if (hoveringText && !this.wasHovering) {
            this.scrollStartMillis = System.currentTimeMillis();
         }

         this.wasHovering = hoveringText;
         if (textWidth > availableWidth && hoveringText) {
            float overflow = textWidth - availableWidth;
            float span = overflow + (float)SUGGESTION_SCROLL_PADDING;
            float cycle = span * 2.0F;
            double elapsedSeconds = (System.currentTimeMillis() - this.scrollStartMillis) / 1000.0;
            double position = elapsedSeconds * SUGGESTION_SCROLL_SPEED % cycle;
            if (position > span) {
               position = cycle - position;
            }

            offset = (float)position;
         }

         int scissorLeft = this.getX() + 2;
         int scissorTop = this.getY() + 2;
         int scissorRight = this.getX() + this.getWidth() - 2;
         int scissorBottom = this.getY() + this.getHeight() - 2;
         graphics.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);
         graphics.drawString(Minecraft.getInstance().font, message, textX - Math.round(offset), textY, color, false);
         graphics.disableScissor();
      }
   }
}
