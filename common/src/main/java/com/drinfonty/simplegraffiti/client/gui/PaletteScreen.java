package com.drinfonty.simplegraffiti.client.gui;

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
 * <p>It is a convenience rather than a necessity - dye recipes reach any colour at a crafting table
 * and the eyedropper reaches any block's colour in the world - so it is built from stock widgets
 * and stays deliberately plain.
 *
 * <p>An invalid hex entry disables the confirm button with a visible reason instead of throwing,
 * which is why {@link PaintColor#parseHex} returns -1 rather than raising.
 */
public class PaletteScreen extends Screen {
	private static final int PANEL_WIDTH = 240;
	private static final int PANEL_HEIGHT = 190;
	private static final int SWATCH = 14;

	private int red;
	private int green;
	private int blue;

	private EditBox hexField;
	private Button confirm;

	/** Suppresses the responder while the field is being rewritten from the sliders. */
	private boolean updatingHex;

	public PaletteScreen() {
		super(Component.translatable("screen.simple_graffiti.palette"));
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

		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		addRenderableWidget(new ChannelSlider(left + 8, top + 24, PANEL_WIDTH - 16, 20,
			"screen.simple_graffiti.red", red, value -> {
				red = value;
				syncHexField();
			}));
		addRenderableWidget(new ChannelSlider(left + 8, top + 48, PANEL_WIDTH - 16, 20,
			"screen.simple_graffiti.green", green, value -> {
				green = value;
				syncHexField();
			}));
		addRenderableWidget(new ChannelSlider(left + 8, top + 72, PANEL_WIDTH - 16, 20,
			"screen.simple_graffiti.blue", blue, value -> {
				blue = value;
				syncHexField();
			}));

		hexField = new EditBox(font, left + 8, top + 100, 80, 20,
			Component.translatable("screen.simple_graffiti.hex"));
		hexField.setMaxLength(7);
		hexField.setValue(PaintColor.toHex(rgb()));
		hexField.setResponder(this::onHexTyped);
		addRenderableWidget(hexField);

		for (int size = Brush.MIN_SIZE; size <= Brush.MAX_SIZE; size++) {
			int brush = size;
			addRenderableWidget(Button.builder(
					Component.translatable("screen.simple_graffiti.brush." + size),
					button -> {
						if (client != null) {
							client.setBrushSize(brush);
						}
					})
				.bounds(left + 96 + size * 46, top + 100, 44, 20)
				.build());
		}

		confirm = Button.builder(Component.translatable("screen.simple_graffiti.apply"), button -> apply())
			.bounds(left + PANEL_WIDTH - 88, top + PANEL_HEIGHT - 28, 80, 20)
			.build();
		addRenderableWidget(confirm);
	}

	private int rgb() {
		return (red << 16) | (green << 8) | blue;
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
	}

	private void selectPreset(int rgb) {
		red = (rgb >> 16) & 0xFF;
		green = (rgb >> 8) & 0xFF;
		blue = rgb & 0xFF;
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
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;
		int swatchTop = top + 128;

		double mouseX = event.x();
		double mouseY = event.y();

		if (mouseY >= swatchTop && mouseY < swatchTop + SWATCH) {
			int index = (int) ((mouseX - (left + 8)) / (SWATCH + 1));

			if (index >= 0 && index < DyeColor.values().length) {
				selectPreset(DyeColor.values()[index].getTextureDiffuseColor() & 0xFFFFFF);
				return true;
			}
		}

		GraffitiClient client = GraffitiClient.get();

		if (client != null && mouseY >= swatchTop + SWATCH + 6 && mouseY < swatchTop + SWATCH * 2 + 6) {
			int index = (int) ((mouseX - (left + 8)) / (SWATCH + 1));

			if (index >= 0 && index < client.config().recentColors.size()) {
				int parsed = PaintColor.parseHex(client.config().recentColors.get(index));

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
		int left = (width - PANEL_WIDTH) / 2;
		int top = (height - PANEL_HEIGHT) / 2;

		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xC0101010);
		graphics.text(font, title, left + 8, top + 8, 0xFFFFFFFF);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// The preview swatch sits next to the hex field, so what is typed and what will be
		// sprayed are visible in the same glance.
		graphics.fill(left + PANEL_WIDTH - 40, top + 100, left + PANEL_WIDTH - 8, top + 120,
			PaintColor.opaque(rgb()));

		int swatchTop = top + 128;
		DyeColor[] dyes = DyeColor.values();

		for (int i = 0; i < dyes.length; i++) {
			int x = left + 8 + i * (SWATCH + 1);
			graphics.fill(x, swatchTop, x + SWATCH, swatchTop + SWATCH,
				PaintColor.opaque(dyes[i].getTextureDiffuseColor()));
		}

		GraffitiClient client = GraffitiClient.get();

		if (client != null) {
			int recentTop = swatchTop + SWATCH + 6;

			for (int i = 0; i < client.config().recentColors.size(); i++) {
				int parsed = PaintColor.parseHex(client.config().recentColors.get(i));

				if (parsed < 0) {
					continue;
				}

				int x = left + 8 + i * (SWATCH + 1);
				graphics.fill(x, recentTop, x + SWATCH, recentTop + SWATCH, PaintColor.opaque(parsed));
			}
		}

		if (minecraft != null && minecraft.player != null) {
			ItemStack held = minecraft.player.getMainHandItem();
			graphics.text(font, Component.translatable("tooltip.simple_graffiti.charges",
					SprayCanItem.remainingCharges(held), held.getMaxDamage()),
				left + 8, top + PANEL_HEIGHT - 22, 0xFFAAAAAA);
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
