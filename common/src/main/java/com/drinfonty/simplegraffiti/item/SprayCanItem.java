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
