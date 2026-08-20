package com.drinfonty.simplegraffiti.item;

import com.drinfonty.simplegraffiti.ClientHooks;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The scrub sponge (SPEC 3.3): erases graffiti, and does nothing else at all.
 *
 * <p>It exists rather than reusing the vanilla wet sponge because of a rule this mod set itself -
 * <em>add no behaviour to vanilla items</em>. A wet sponge that silently gained a new right-click
 * action is exactly the kind of surprise that makes mods hard to reason about, and it would collide
 * with any other mod doing the same. A dedicated item also gets a name in the creative tab and an
 * entry in the recipe book, so the feature is discoverable instead of folklore.
 *
 * <p>Erasing runs through the same {@code PaintService} path as painting, so it inherits the same
 * permission check, reach check and rate limit. Wiping someone's mural is a build action too, and
 * must not be a back door around protection.
 */
public class ScrubSpongeItem extends Item {
	public ScrubSpongeItem(Properties properties) {
		super(properties);
	}

	public static boolean hasDurability(ItemStack stack) {
		return stack.getDamageValue() < stack.getMaxDamage();
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();

		if (context.getPlayer() == null) {
			return InteractionResult.PASS;
		}

		if (level.isClientSide()) {
			ClientHooks.PaintTrigger trigger = ClientHooks.trigger();

			if (trigger != null) {
				trigger.onUseOnFace(context.getClickedPos(), context.getClickedFace(),
					context.getClickLocation(), context.getHand(), true,
					context.isSecondaryUseActive());
			}
		}

		// A held scrub gets the brushing pose, the way the archaeology brush does - started from
		// useOn rather than use(), which is how vanilla's brush does it and keeps sneak-use as the
		// discrete whole-face wipe it already is.
		//
		// A whole-face wipe is one action, not a sustained one, so it gets no animation.
		if (!context.isSecondaryUseActive() && !context.getPlayer().isUsingItem()) {
			// Guarded: vanilla repeats useOn while the button is held, and starting the use again
			// would restart the brush cycle every few ticks and make it stutter.
			context.getPlayer().startUsingItem(context.getHand());
		}

		return InteractionResult.CONSUME;
	}

	/** The archaeology brush's back-and-forth scrubbing motion, which loops every 10 ticks. */
	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.BRUSH;
	}

	/** Long, like the spray can: scrubbing stops when the player lets go, not on a timer. */
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
}
