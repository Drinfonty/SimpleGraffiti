package com.drinfonty.simplegraffiti.item;

import java.util.function.Consumer;

import com.drinfonty.simplegraffiti.ClientHooks;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The spray can (SPEC 3).
 *
 * <p>Two verbs, and deliberately no third: <strong>use</strong> paints, <strong>sneak-use</strong>
 * picks a colour. Erasing belongs to the scrub sponge, which is what frees sneak-use for the
 * eyedropper and leaves no interaction ambiguous.
 *
 * <p>Painting itself does not happen here. The item only reports the interaction; the client turns
 * it into a paint request and the server decides. That indirection exists because holding use has
 * to repeat at a fixed rate, which vanilla's one-shot {@code useOn} cannot express, and because the
 * server must be the only thing that ever writes a canvas.
 */
public class SprayCanItem extends Item {
	public SprayCanItem(Properties properties) {
		super(properties);
	}

	public static int colorOf(ItemStack stack) {
		return DyedItemColor.getOrDefault(stack, PaintColor.DEFAULT_RGB) & 0xFFFFFF;
	}

	public static void setColor(ItemStack stack, int rgb) {
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb & 0xFFFFFF));
	}

	public static int remainingCharges(ItemStack stack) {
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	public static boolean hasCharge(ItemStack stack) {
		return remainingCharges(stack) > 0;
	}

	/**
	 * Spends one charge.
	 *
	 * <p>Deliberately not {@code hurtAndBreak}: an exhausted can must not be destroyed (SPEC 3). An
	 * item that vanished mid-mural would be infuriating, and the can is refillable, so it stops
	 * painting instead and stays in the hotbar.
	 */
	public static void consumeCharge(ItemStack stack, boolean creative) {
		if (creative) {
			return;
		}

		stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + 1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();

		if (context.getPlayer() == null) {
			return InteractionResult.PASS;
		}

		if (context.isSecondaryUseActive()) {
			return eyedropper(context, level);
		}

		if (level.isClientSide()) {
			ClientHooks.PaintTrigger trigger = ClientHooks.trigger();

			if (trigger != null) {
				trigger.onUseOnFace(context.getClickedPos(), context.getClickedFace(),
					context.getClickLocation(), context.getHand(), false, false);
			}
		}

		// CONSUME on both sides: the interaction is ours, so vanilla must not also try to place a
		// block or activate the target, but nothing is painted from here.
		return InteractionResult.CONSUME;
	}

	private InteractionResult eyedropper(UseOnContext context, Level level) {
		if (!level.isClientSide()) {
			setColor(context.getItemInHand(), ColorSampler.sample(level, context.getClickedPos()));
		}

		level.playSound(context.getPlayer(), context.getClickedPos(),
			SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.5F, 1.0F);

		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
		Consumer<Component> lines, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, lines, flag);

		lines.accept(Component.translatable("tooltip.simple_graffiti.charges",
				remainingCharges(stack), stack.getMaxDamage())
			.withStyle(ChatFormatting.GRAY));
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		// Always show the charge bar, not only once it is partly spent: charges are the point of
		// the item, and a full can looking identical to an empty one is the whole complaint.
		return true;
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return colorOf(stack);
	}
}
