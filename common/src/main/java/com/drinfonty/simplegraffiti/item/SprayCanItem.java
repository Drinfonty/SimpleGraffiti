package com.drinfonty.simplegraffiti.item;

import java.util.function.Consumer;

import com.drinfonty.simplegraffiti.ClientHooks;
import com.drinfonty.simplegraffiti.canvas.PaintColor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The spray can (SPEC 3).
 *
 * <p>Two verbs, and deliberately no third: <strong>use</strong> paints, <strong>sneak-use</strong>
 * opens the colour picker. Erasing belongs to the scrub sponge, which is what frees sneak-use and
 * leaves no interaction ambiguous.
 *
 * <p>Sneak-use used to be the eyedropper, sampling whatever block was clicked. That reached a
 * colour in one action but only if the player knew the gesture existed, and the picker itself was
 * behind a keybind nobody had reason to discover. The picker now owns both: it opens on sneak-use
 * and carries the colour under the crosshair as one of its swatches.
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

	/**
	 * Both verbs are item <em>uses</em>, not block interactions.
	 *
	 * <p>{@code useOn} is deliberately not overridden: leaving it at vanilla's PASS lets both
	 * gestures fall through to here whether or not the player is aiming at a block. That is what
	 * puts the drawn-bow pose on the arm while spraying, and what stops sneak-use silently doing
	 * nothing when aimed at the sky.
	 */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive()) {
			if (level.isClientSide()) {
				ClientHooks.PaintTrigger trigger = ClientHooks.trigger();

				if (trigger != null) {
					trigger.openPalette();
				}
			}

			// CONSUME rather than SUCCESS: opening a screen is not an action worth swinging the
			// arm for, and the server has nothing to do here at all - the picker sends its own
			// packet when the player presses Apply.
			return InteractionResult.CONSUME;
		}

		player.startUsingItem(hand);

		if (level.isClientSide()) {
			ClientHooks.PaintTrigger trigger = ClientHooks.trigger();

			if (trigger != null) {
				trigger.startSpraying(hand);
			}
		}

		return InteractionResult.CONSUME;
	}

	/** Holding the can forward, like drawing a bow. */
	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.BOW;
	}

	/** As long as a bow's: the spray stops when the player lets go, not on a timer. */
	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
		if (level.isClientSide()) {
			ClientHooks.PaintTrigger trigger = ClientHooks.trigger();

			if (trigger != null) {
				trigger.stopSpraying();
			}
		}

		return false;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
		Consumer<Component> lines, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, lines, flag);

		lines.accept(Component.translatable("tooltip.simple_graffiti.charges",
				remainingCharges(stack), stack.getMaxDamage())
			.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * Refills the can when a magma cream is left-clicked onto it in an inventory, the way a bundle
	 * takes an item.
	 *
	 * <p>This is called on the stack sitting in the slot, with the carried stack as {@code other} -
	 * so it fires for "hold magma cream, left-click the can". A full can deliberately declines, so
	 * the click falls through to the normal swap rather than silently eating a magma cream for
	 * nothing.
	 */
	@Override
	public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot,
		ClickAction action, Player player, SlotAccess carried) {
		if (action != ClickAction.PRIMARY || !other.is(Items.MAGMA_CREAM)) {
			return false;
		}

		return refill(stack, other, player);
	}

	/**
	 * The same refill the other way round: carrying the can and left-clicking a stack of magma
	 * cream. Supported because which item a player picks up first is arbitrary, and a mechanic that
	 * works in only one direction reads as a bug.
	 */
	@Override
	public boolean overrideStackedOnOther(ItemStack stack, Slot slot, ClickAction action, Player player) {
		if (action != ClickAction.PRIMARY || !slot.getItem().is(Items.MAGMA_CREAM)) {
			return false;
		}

		return refill(stack, slot.getItem(), player);
	}

	/**
	 * @return true when a charge was actually restored, which is also what tells the menu the click
	 *         was consumed
	 */
	private static boolean refill(ItemStack can, ItemStack magmaCream, Player player) {
		if (can.getDamageValue() == 0 || magmaCream.isEmpty()) {
			return false;
		}

		can.setDamageValue(0);
		magmaCream.shrink(1);

		// Pressurised, to match the crafting recipe's fiction of a can charged with magma cream.
		player.playSound(SoundEvents.FIRECHARGE_USE, 0.6F, 1.4F);
		return true;
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
