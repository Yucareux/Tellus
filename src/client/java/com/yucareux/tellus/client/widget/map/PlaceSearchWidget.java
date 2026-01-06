package com.yucareux.tellus.client.widget.map;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import org.lwjgl.glfw.GLFW;

public class PlaceSearchWidget extends EditBox {
	private static final int SUGGESTION_COUNT = 5;
	private static final int SUGGESTION_HEIGHT = 20;
	private static final int SUGGESTION_GAP = 2;
	private static final long SUGGESTION_DEBOUNCE_MS = 250;
	private static final int SEARCH_TEXT_OFFSET_X = 4;
	private static final int SUGGESTION_TEXT_PADDING = 6;
	private static final int SUGGESTION_SCROLL_PADDING = 12;
	private static final float SUGGESTION_SCROLL_SPEED = 28.0F;

	private final Geocoder geocoder;
	private final SearchHandler searchHandler;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
			new ThreadFactoryBuilder().setNameFormat("tellus-geocoder-%d").setDaemon(true).build()
	);

	private final List<Geocoder.Suggestion> suggestions = new ArrayList<>(SUGGESTION_COUNT);
	private final List<Button> suggestionButtons = new ArrayList<>(SUGGESTION_COUNT);
	private ScheduledFuture<?> pendingSuggestTask;
	private long suggestionRequestId;
	private State state = State.OK;
	private boolean pause;
	private String lastInputText = "";

	public PlaceSearchWidget(int x, int y, int width, int height, Geocoder geocoder, SearchHandler searchHandler) {
		super(Minecraft.getInstance().font, x, y, width, height, Component.translatable("gui.earth.search"));
		this.geocoder = geocoder;
		this.searchHandler = searchHandler;
		this.setBordered(false);
		this.setMaxLength(256);
		this.setHint(Component.translatable("gui.earth.search"));
	}

	public void tick() {
		String text = this.getValue().trim();
		if (!text.equals(this.lastInputText)) {
			this.lastInputText = text;
			this.state = State.OK;
			this.pause = false;
			if (!this.pause && !text.isEmpty()) {
				scheduleSuggestions(text);
			}
		}

		if (this.pause || text.isEmpty()) {
			cancelPendingSuggest();
			clearSuggestions();
		}
	}

	@Override
	public void renderWidget(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int x = this.getX();
		int y = this.getY();
		int width = this.getWidth();
		int height = this.getHeight();

		graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFFA0A0A0);
		graphics.fill(x, y, x + width, y + height, this.state.getBackgroundColor());

		graphics.pose().pushMatrix();
		graphics.pose().translate(SEARCH_TEXT_OFFSET_X, (height - 8) / 2.0F - 1.0F);
		super.renderWidget(graphics, mouseX, mouseY, delta);
		graphics.pose().popMatrix();

		if (shouldShowSuggestions()) {
			layoutSuggestionButtons();
			for (Button button : this.suggestionButtons) {
				button.render(graphics, mouseX, mouseY, delta);
			}
		}
	}

	@Override
	public boolean mouseClicked(@NonNull MouseButtonEvent event, boolean isPrimary) {
		if (this.isVisible() && shouldShowSuggestions() && event.button() == 0) {
			for (Button button : this.suggestionButtons) {
				if (button.mouseClicked(event, isPrimary)) {
					return true;
				}
			}
		}

		return super.mouseClicked(event, isPrimary);
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		if (super.isMouseOver(mouseX, mouseY)) {
			return true;
		}

		if (shouldShowSuggestions()) {
			for (Button button : this.suggestionButtons) {
				if (button.isMouseOver(mouseX, mouseY)) {
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public boolean keyPressed(@NonNull KeyEvent event) {
		if (!this.isFocused()) {
			return false;
		}

		this.pause = false;

		int key = event.key();
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			cancelPendingSuggest();
			clearSuggestions();
			this.handleAccept();
			return true;
		}

		if (super.keyPressed(event)) {
			this.state = State.OK;
			return true;
		}

		return false;
	}

	public void close() {
		cancelPendingSuggest();
		this.executor.shutdownNow();
	}

	private void handleAccept() {
		String text = this.getValue().trim();
		if (text.isEmpty()) {
			return;
		}
		this.pause = true;
		cancelPendingSuggest();
		clearSuggestions();
		CompletableFuture.runAsync(() -> {
			try {
				double[] coordinate = this.geocoder.get(text);
				if (coordinate != null) {
					Minecraft.getInstance().execute(() -> {
						this.searchHandler.handle(coordinate[0], coordinate[1]);
						this.state = State.FOUND;
					});
				} else {
					Minecraft.getInstance().execute(() -> this.state = State.NOT_FOUND);
				}
			} catch (IOException e) {
				Tellus.LOGGER.error("Failed to find searched place {}", text, e);
			}
		}, this.executor);
	}

	private void scheduleSuggestions(String text) {
		cancelPendingSuggest();
		clearSuggestions();
		long requestId = ++this.suggestionRequestId;
		this.pendingSuggestTask = this.executor.schedule(() -> {
			Geocoder.Suggestion[] result = fetchSuggestions(text);
			Minecraft.getInstance().execute(() -> applySuggestions(requestId, text, result));
		}, SUGGESTION_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
	}

	private void applySuggestions(long requestId, String text, Geocoder.Suggestion[] result) {
		if (requestId != this.suggestionRequestId) {
			return;
		}
		if (this.pause || !text.equals(this.getValue().trim())) {
			return;
		}
		this.suggestions.clear();
		if (result != null) {
			for (int i = 0; i < result.length && this.suggestions.size() < SUGGESTION_COUNT; i++) {
				this.suggestions.add(result[i]);
			}
		}
		if (this.suggestions.isEmpty()) {
			this.state = State.NOT_FOUND;
			this.suggestionButtons.clear();
			return;
		}
		this.state = State.OK;
		rebuildSuggestionButtons();
	}

	private Geocoder.Suggestion[] fetchSuggestions(String text) {
		try {
			return this.geocoder.suggest(text);
		} catch (Exception e) {
			Tellus.LOGGER.error("Failed to get geocoder suggestions", e);
			return new Geocoder.Suggestion[0];
		}
	}

	private void acceptSuggestion(Geocoder.Suggestion suggestion) {
		this.pause = true;
		cancelPendingSuggest();
		clearSuggestions();
		String displayName = Objects.requireNonNull(suggestion.displayName(), "suggestionDisplayName");
		this.setValue(displayName);
		this.state = State.FOUND;
		this.searchHandler.handle(suggestion.latitude(), suggestion.longitude());
	}

	private void rebuildSuggestionButtons() {
		this.suggestionButtons.clear();
		int x = this.getX();
		int originY = this.getY() + this.getHeight() + SUGGESTION_GAP;
		int width = this.getWidth();
		for (int i = 0; i < this.suggestions.size(); i++) {
			Geocoder.Suggestion suggestion = this.suggestions.get(i);
			String displayName = Objects.requireNonNull(suggestion.displayName(), "suggestionDisplayName");
			Component label = Component.literal(displayName);
			Button button = new SuggestionButton(
					x,
					originY + i * (SUGGESTION_HEIGHT + SUGGESTION_GAP),
					width,
					SUGGESTION_HEIGHT,
					label,
					pressed -> acceptSuggestion(suggestion)
			);
			this.suggestionButtons.add(button);
		}
	}

	private void layoutSuggestionButtons() {
		int x = this.getX();
		int originY = this.getY() + this.getHeight() + SUGGESTION_GAP;
		int width = this.getWidth();
		for (int i = 0; i < this.suggestionButtons.size(); i++) {
			Button button = this.suggestionButtons.get(i);
			button.setX(x);
			button.setY(originY + i * (SUGGESTION_HEIGHT + SUGGESTION_GAP));
			button.setWidth(width);
			button.setHeight(SUGGESTION_HEIGHT);
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

	private static final class SuggestionButton extends Button {
		private long scrollStartMillis = System.currentTimeMillis();
		private boolean wasHovering;

		private SuggestionButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
			super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
		}

		@Override
		protected void renderContents(@NonNull GuiGraphics graphics, int mouseX, int mouseY, float delta) {
			this.renderDefaultSprite(graphics);
			Component message = this.getMessage();
			int textWidth = Minecraft.getInstance().font.width(message);
			int availableWidth = Math.max(0, this.getWidth() - SUGGESTION_TEXT_PADDING * 2);
			int textX = this.getX() + SUGGESTION_TEXT_PADDING;
			int textY = this.getY() + (this.getHeight() - Minecraft.getInstance().font.lineHeight) / 2;
			int color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
			float offset = 0.0F;
			boolean hoveringText = mouseX >= textX && mouseX <= textX + availableWidth
					&& mouseY >= this.getY() && mouseY <= this.getY() + this.getHeight();

			if (hoveringText && !this.wasHovering) {
				this.scrollStartMillis = System.currentTimeMillis();
			}
			this.wasHovering = hoveringText;

			if (textWidth > availableWidth && hoveringText) {
				float overflow = textWidth - availableWidth;
				float span = overflow + SUGGESTION_SCROLL_PADDING;
				float cycle = span * 2.0F;
				double elapsedSeconds = (System.currentTimeMillis() - this.scrollStartMillis) / 1000.0;
				double position = (elapsedSeconds * SUGGESTION_SCROLL_SPEED) % cycle;
				if (position > span) {
					position = cycle - position;
				}
				offset = (float) position;
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

	public interface SearchHandler {
		void handle(double latitude, double longitude);
	}

	public enum State {
		OK(0xFF000000),
		FOUND(0xFF004600),
		NOT_FOUND(0xFF460000);

		private final int backgroundColor;

		State(int backgroundColor) {
			this.backgroundColor = backgroundColor;
		}

		public int getBackgroundColor() {
			return this.backgroundColor;
		}
	}
}
