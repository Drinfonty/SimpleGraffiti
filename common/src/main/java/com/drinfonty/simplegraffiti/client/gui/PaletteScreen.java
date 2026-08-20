package com.drinfonty.simplegraffiti.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.drinfonty.simplegraffiti.GraffitiClient;
import com.drinfonty.simplegraffiti.canvas.Brush;
import com.drinfonty.simplegraffiti.canvas.PaintColor;
import com.drinfonty.simplegraffiti.config.ClientConfig;
import com.drinfonty.simplegraffiti.item.SprayCanItem;
import com.drinfonty.simplegraffiti.net.GraffitiPayloads;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/**
 * The colour picker (SPEC 5.3): RGB sliders, a hex field, the 16 dye colours as presets, the recent
 * colours row, and the three brush sizes.
 *
 * <p>It is built from stock widgets and stays deliberately plain - dye recipes reach any colour at
 * a crafting table too, so this is the convenient route rather than the only one.
 *
 * <p>It also absorbed the eyedropper. Sampling the block under the crosshair used to be its own
 * sneak-use gesture; now sneak-use opens this screen and the sampled colour arrives as the
 * <em>looking at</em> swatch. The colour is a snapshot taken when the screen opened, because once
 * it is open the crosshair is pointing at this panel and there is nothing in the world to sample.
 *
 * <p>Every position comes from one of the {@code …X}/{@code …Y} helpers below, because drawing and
 * hit-testing used to compute the swatch grid separately and could disagree about where a swatch
 * was. Layout is derived from the panel rather than hardcoded, so widths that no longer fit show up
 * as an assertion in {@link #init} rather than as a button drawn underneath another one.
 */
public class PaletteScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int SWATCH = 14;
	private static final int SWATCH_GAP = 1;
	private static final int ROW_HEIGHT = 20;

	private static final int HEX_WIDTH = 76;
	private static final int PREVIEW_WIDTH = 28;
	private static final int BRUSH_WIDTH = 46;
	private static final int CONTROL_GAP = 6;
	private static final int BRUSH_GAP = 2;
	private static final int BRUSH_COUNT = Brush.MAX_SIZE - Brush.MIN_SIZE + 1;

	private static final int SWATCH_ROW_WIDTH =
		DyeColor.values().length * (SWATCH + SWATCH_GAP) - SWATCH_GAP;
	private static final int CONTROL_ROW_WIDTH = HEX_WIDTH + CONTROL_GAP + PREVIEW_WIDTH + CONTROL_GAP
		+ BRUSH_COUNT * BRUSH_WIDTH + (BRUSH_COUNT - 1) * BRUSH_GAP;

	/**
	 * Wide enough for whichever row needs the most - the swatches or the controls.
	 *
	 * <p>Sizing it from the swatch row alone was not enough: the control row is wider, so the last
	 * brush button hung off the edge of the panel. Taking the maximum is the only version that
	 * cannot be wrong when either row changes.
	 */
	private static final int PANEL_WIDTH =
		MARGIN * 2 + Math.max(SWATCH_ROW_WIDTH, CONTROL_ROW_WIDTH);
	private static final int PANEL_HEIGHT = 232;

	/** Wider than a dye swatch, so the sampled colour does not read as a seventeenth preset. */
	private static final int VIEWED_WIDTH = 28;

	/** Passed for {@code viewedRgb} when the player was not looking at a block. */
	public static final int NO_VIEWED_COLOR = -1;

	private int red;
	private int green;
	private int blue;

	private EditBox hexField;
	private Button confirm;
	private final List<ChannelSlider> sliders = new ArrayList<>();
	private final List<Button> brushButtons = new ArrayList<>();

	/** Suppresses the responder while the field is being rewritten from the sliders. */
	private boolean updatingHex;

	/** The colour under the crosshair when the screen opened, or {@link #NO_VIEWED_COLOR}. */
	private final int viewedRgb;

	public PaletteScreen() {
		this(NO_VIEWED_COLOR);
	}

	public PaletteScreen(int viewedRgb) {
		super(Component.translatable("screen.simple_graffiti.palette"));
		this.viewedRgb = viewedRgb;
	}

	private int left() {
		return (width - PANEL_WIDTH) / 2;
	}

	private int top() {
		return (height - PANEL_HEIGHT) / 2;
	}

	private int contentWidth() {
		return PANEL_WIDTH - MARGIN * 2;
	}

	private int controlRowY() {
		return top() + 100;
	}

	private int swatchRowY() {
		return top() + 132;
	}

	private int recentRowY() {
		return swatchRowY() + SWATCH + 6;
	}

	private int viewedRowY() {
		return recentRowY() + SWATCH + 8;
	}

	/** The sampled swatch sits after its label, far enough right to clear the widest wording. */
	private int viewedX() {
		return left() + MARGIN + 72;
	}

	private boolean hasViewedColor() {
		return viewedRgb != NO_VIEWED_COLOR;
	}

	/** The x of swatch {@code index} in either swatch row. */
	private int swatchX(int index) {
		return left() + MARGIN + index * (SWATCH + SWATCH_GAP);
	}

	/** Which swatch a mouse x falls on, or -1 when it is in a gap or outside the row. */
	private int swatchAt(double mouseX, int count) {
		for (int i = 0; i < count; i++) {
			int x = swatchX(i);

			if (mouseX >= x && mouseX < x + SWATCH) {
				return i;
			}
		}

		return -1;
	}

	@Override
	protected void init() {
		GraffitiClient client = GraffitiClient.get();
		int rgb = PaintColor.DEFAULT_RGB;

		if (minecraft != null && minecraft.player != null) {
			ItemStack held = minecraft.player.getMainHandItem();
			rgb = SprayCanItem.colorOf(held);
		}

		red = (rgb >> 16) & 0xFF;
		green = (rgb >> 8) & 0xFF;
		blue = rgb & 0xFF;

		sliders.clear();
		brushButtons.clear();

		int left = left();
		int top = top();

		sliders.add(addRenderableWidget(new ChannelSlider(left + MARGIN, top + 24, contentWidth(), ROW_HEIGHT,
			"screen.simple_graffiti.red", red, value -> {
				red = value;
				onChannelDragged();
			})));
		sliders.add(addRenderableWidget(new ChannelSlider(left + MARGIN, top + 48, contentWidth(), ROW_HEIGHT,
			"screen.simple_graffiti.green", green, value -> {
				green = value;
				onChannelDragged();
			})));
		sliders.add(addRenderableWidget(new ChannelSlider(left + MARGIN, top + 72, contentWidth(), ROW_HEIGHT,
			"screen.simple_graffiti.blue", blue, value -> {
				blue = value;
				onChannelDragged();
			})));

		// The control row runs left to right: hex field, live preview, one button per brush size.
		hexField = new EditBox(font, left + MARGIN, controlRowY(), HEX_WIDTH, ROW_HEIGHT,
			Component.translatable("screen.simple_graffiti.hex"));
		hexField.setMaxLength(7);
		hexField.setValue(PaintColor.toHex(rgb()));
		hexField.setResponder(this::onHexTyped);
		addRenderableWidget(hexField);

		int brushLeft = left + MARGIN + HEX_WIDTH + CONTROL_GAP + PREVIEW_WIDTH + CONTROL_GAP;

		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int brush = size;
			Button button = Button.builder(
					Component.translatable("screen.simple_graffiti.brush." + size),
					b -> {
						if (client != null) {
							client.setBrushSize(brush);
							markSelectedBrush(brush);
						}
					})
				.bounds(brushLeft + (size - Brush.MIN_SIZE) * (BRUSH_WIDTH + BRUSH_GAP), controlRowY(),
					BRUSH_WIDTH, ROW_HEIGHT)
				.build();
			brushButtons.add(addRenderableWidget(button));
		}

		if (brushLeft + BRUSH_COUNT * BRUSH_WIDTH + (BRUSH_COUNT - 1) * BRUSH_GAP
			> left + PANEL_WIDTH - MARGIN) {
			// Not an exception: a cramped row is a cosmetic problem, and throwing here would take
			// the whole screen down. It is worth a log line so it is not discovered in a
			// screenshot months later, which is exactly how the old overlap was found.
			com.drinfonty.simplegraffiti.SimpleGraffiti.LOGGER.warn(
				"Palette control row does not fit the panel; buttons may overlap");
		}

		confirm = Button.builder(Component.translatable("screen.simple_graffiti.apply"), button -> apply())
			.bounds(left + PANEL_WIDTH - MARGIN - 80, top + PANEL_HEIGHT - 30, 80, ROW_HEIGHT)
			.build();
		addRenderableWidget(confirm);

		markSelectedBrush(client == null ? Brush.SIZE_MEDIUM : client.brushSize());
	}

	/** The current size is shown by being the one button you cannot press. */
	private void markSelectedBrush(int size) {
		for (int i = 0; i < brushButtons.size(); i++) {
			brushButtons.get(i).active = (i + Brush.MIN_SIZE) != size;
		}
	}

	private int rgb() {
		return (red << 16) | (green << 8) | blue;
	}

	/** A slider moved: the hex field follows, but the sliders are already where the user put them. */
	private void onChannelDragged() {
		syncHexField();
	}

	private void syncHexField() {
		if (hexField == null) {
			return;
		}

		updatingHex = true;
		hexField.setValue(PaintColor.toHex(rgb()));
		updatingHex = false;
		confirm.active = true;
	}

	/** Moves the sliders to match a colour chosen elsewhere - a swatch, or the hex field. */
	private void syncSliders() {
		if (sliders.size() == 3) {
			sliders.get(0).setChannel(red);
			sliders.get(1).setChannel(green);
			sliders.get(2).setChannel(blue);
		}
	}

	private void onHexTyped(String text) {
		if (updatingHex) {
			return;
		}

		int parsed = PaintColor.parseHex(text);

		if (parsed < 0) {
			// Disabled rather than silently ignored: a player typing "#12345" deserves to see
			// that the button will not work, not to press it and wonder.
			confirm.active = false;
			return;
		}

		confirm.active = true;
		red = (parsed >> 16) & 0xFF;
		green = (parsed >> 8) & 0xFF;
		blue = parsed & 0xFF;

		// Without this the sliders keep showing whatever they last read, and the panel
		// contradicts itself: hex saying one colour, sliders another, preview a third.
		syncSliders();
	}

	private void selectPreset(int rgb) {
		red = (rgb >> 16) & 0xFF;
		green = (rgb >> 8) & 0xFF;
		blue = rgb & 0xFF;
		syncSliders();
		syncHexField();
	}

	private void apply() {
		GraffitiClient client = GraffitiClient.get();

		if (client == null || !client.canPaint()) {
			onClose();
			return;
		}

		int rgb = rgb();

		// Nothing is applied locally: the server owns the item's component, exactly as it owns
		// every canvas. It echoes the change back through the normal inventory sync.
		// Always the main hand: the screen only opens while a can is held there.
		client.sender().sendIfPossible(new GraffitiPayloads.SetColorC2S(rgb, 0));

		rememberRecent(client.config(), rgb);
		client.saveConfig();
		onClose();
	}

	private static void rememberRecent(ClientConfig config, int rgb) {
		String hex = PaintColor.toHex(rgb);
		config.recentColors.remove(hex);
		config.recentColors.addFirst(hex);

		while (config.recentColors.size() > ClientConfig.MAX_RECENT_COLORS) {
			config.recentColors.removeLast();
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
		double mouseX = event.x();
		double mouseY = event.y();

		if (mouseY >= swatchRowY() && mouseY < swatchRowY() + SWATCH) {
			int index = swatchAt(mouseX, DyeColor.values().length);

			if (index >= 0) {
				selectPreset(DyeColor.values()[index].getTextureDiffuseColor() & 0xFFFFFF);
				return true;
			}
		}

		if (hasViewedColor()
			&& mouseY >= viewedRowY() && mouseY < viewedRowY() + SWATCH
			&& mouseX >= viewedX() && mouseX < viewedX() + VIEWED_WIDTH) {
			selectPreset(viewedRgb);
			return true;
		}

		GraffitiClient client = GraffitiClient.get();

		if (client != null && mouseY >= recentRowY() && mouseY < recentRowY() + SWATCH) {
			List<String> recent = client.config().recentColors;
			int index = swatchAt(mouseX, recent.size());

			if (index >= 0) {
				int parsed = PaintColor.parseHex(recent.get(index));

				if (parsed >= 0) {
					selectPreset(parsed);
					return true;
				}
			}
		}

		return super.mouseClicked(event, doubled);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = left();
		int top = top();

		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
		graphics.text(font, title, left + MARGIN, top + MARGIN, 0xFFFFFFFF);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// The preview sits between the hex field and the brush buttons, in the gap left for it.
		int previewX = left + MARGIN + HEX_WIDTH + CONTROL_GAP;
		graphics.fill(previewX, controlRowY(), previewX + PREVIEW_WIDTH, controlRowY() + ROW_HEIGHT,
			PaintColor.opaque(rgb()));

		DyeColor[] dyes = DyeColor.values();

		for (int i = 0; i < dyes.length; i++) {
			graphics.fill(swatchX(i), swatchRowY(), swatchX(i) + SWATCH, swatchRowY() + SWATCH,
				PaintColor.opaque(dyes[i].getTextureDiffuseColor()));
		}

		GraffitiClient client = GraffitiClient.get();

		if (client != null) {
			List<String> recent = client.config().recentColors;

			for (int i = 0; i < recent.size(); i++) {
				int parsed = PaintColor.parseHex(recent.get(i));

				if (parsed < 0) {
					continue;
				}

				graphics.fill(swatchX(i), recentRowY(), swatchX(i) + SWATCH, recentRowY() + SWATCH,
					PaintColor.opaque(parsed));
			}
		}

		graphics.text(font, Component.translatable("screen.simple_graffiti.viewed"),
			left + MARGIN, viewedRowY() + 3, 0xFFAAAAAA);

		if (hasViewedColor()) {
			graphics.fill(viewedX(), viewedRowY(), viewedX() + VIEWED_WIDTH, viewedRowY() + SWATCH,
				PaintColor.opaque(viewedRgb));
		} else {
			// An empty well rather than nothing at all: the row keeps its place, and "you were not
			// looking at anything" is a different message from "this feature is missing".
			graphics.fill(viewedX(), viewedRowY(), viewedX() + VIEWED_WIDTH, viewedRowY() + SWATCH,
				0xFF303030);
			graphics.text(font, Component.translatable("screen.simple_graffiti.viewed.none"),
				viewedX() + VIEWED_WIDTH + CONTROL_GAP, viewedRowY() + 3, 0xFF808080);
		}

		if (minecraft != null && minecraft.player != null) {
			ItemStack held = minecraft.player.getMainHandItem();
			graphics.text(font, Component.translatable("tooltip.simple_graffiti.charges",
					SprayCanItem.remainingCharges(held), held.getMaxDamage()),
				left + MARGIN, top + PANEL_HEIGHT - 24, 0xFFAAAAAA);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** A 0..255 slider for one colour channel. */
	private static final class ChannelSlider extends AbstractSliderButton {
		private final String labelKey;
		private final java.util.function.IntConsumer onChange;

		private ChannelSlider(int x, int y, int width, int height, String labelKey, int initial,
			java.util.function.IntConsumer onChange) {
			super(x, y, width, height, Component.empty(), initial / 255.0);
			this.labelKey = labelKey;
			this.onChange = onChange;
			updateMessage();
		}

		private int channelValue() {
			return (int) Math.round(value * 255.0);
		}

		/**
		 * Moves the handle to match a colour set elsewhere.
		 *
		 * <p>Deliberately does not call {@code applyValue}: this is the panel telling the slider
		 * where the colour already is, not the slider announcing a change, and routing it back
		 * would loop.
		 */
		private void setChannel(int channel) {
			value = Math.clamp(channel, 0, 255) / 255.0;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(labelKey, channelValue()));
		}

		@Override
		protected void applyValue() {
			onChange.accept(channelValue());
			updateMessage();
		}
	}
}
