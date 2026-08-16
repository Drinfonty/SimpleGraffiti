package com.drinfonty.simplegraffiti.item;

import java.util.function.BiConsumer;

import com.drinfonty.simplegraffiti.SimpleGraffiti;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * The mod's two items, and the one seam where registration differs per loader.
 *
 * <p>Fabric registers eagerly at init; NeoForge registers through a {@code DeferredRegister}. Both
 * end up calling {@link #register} with a sink that does the loader-specific part, so the item
 * definitions themselves - which is everything that actually matters - live here once.
 */
public final class GraffitiItems {
	public static final Identifier SPRAY_CAN_ID = SimpleGraffiti.id("spray_can");
	public static final Identifier SCRUB_SPONGE_ID = SimpleGraffiti.id("scrub_sponge");

	/** SPEC 3: 64 charges, and SPEC 3.3: 128 scrubs. */
	public static final int DEFAULT_CHARGES = 64;
	public static final int DEFAULT_SPONGE_DURABILITY = 128;

	public static Item sprayCan;
	public static Item scrubSponge;

	private GraffitiItems() {
	}

	public static void register(BiConsumer<Identifier, Item> sink) {
		sprayCan = new SprayCanItem(new Item.Properties()
			.setId(ResourceKey.create(Registries.ITEM, SPRAY_CAN_ID))
			// durability() also pins the stack size to 1. Charges ride on vanilla's damage
			// component so the durability bar, the tooltip and the item's own plumbing all work
			// for free - the can simply refuses to paint at zero instead of breaking.
			.durability(DEFAULT_CHARGES));

		scrubSponge = new ScrubSpongeItem(new Item.Properties()
			.setId(ResourceKey.create(Registries.ITEM, SCRUB_SPONGE_ID))
			.durability(DEFAULT_SPONGE_DURABILITY));

		sink.accept(SPRAY_CAN_ID, sprayCan);
		sink.accept(SCRUB_SPONGE_ID, scrubSponge);
	}

	public static boolean isSprayCan(net.minecraft.world.item.ItemStack stack) {
		return stack.getItem() instanceof SprayCanItem;
	}

	public static boolean isScrubSponge(net.minecraft.world.item.ItemStack stack) {
		return stack.getItem() instanceof ScrubSpongeItem;
	}
}
