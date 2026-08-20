package com.drinfonty.simplegraffiti.item;

import java.util.function.Supplier;

import com.drinfonty.simplegraffiti.SimpleGraffiti;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;

/**
 * The mod's two items, and the one seam where registration differs per loader.
 *
 * <p>Fabric registers eagerly at init; NeoForge registers through a {@code DeferredRegister}. The
 * sink below is handed a <em>factory</em> rather than a finished item for a reason that is not
 * cosmetic: constructing an {@link Item} calls into the item registry to create its intrusive
 * holder, which throws {@code Registry is already frozen} outside a registration window. Building
 * the items eagerly therefore works on Fabric and crashes NeoForge during mod construction. Passing
 * a factory lets each loader decide <em>when</em> construction happens, while the definitions
 * themselves - which is everything that actually matters - stay here, written once.
 */
public final class GraffitiItems {
	public static final Identifier SPRAY_CAN_ID = SimpleGraffiti.id("spray_can");
	public static final Identifier SCRUB_SPONGE_ID = SimpleGraffiti.id("scrub_sponge");

	/** SPEC 3: 128 charges - about 32 seconds of continuous spraying, and SPEC 3.3: 128 scrubs. */
	public static final int DEFAULT_CHARGES = 128;
	public static final int DEFAULT_SPONGE_DURABILITY = 128;

	public static Item sprayCan;
	public static Item scrubSponge;

	private GraffitiItems() {
	}

	/** Receives each item's id and a factory that constructs it when the loader is ready. */
	public interface Sink {
		void accept(Identifier id, Supplier<Item> factory);
	}

	public static void register(Sink sink) {
		sink.accept(SPRAY_CAN_ID, () -> sprayCan = new SprayCanItem(new Item.Properties()
			.setId(ResourceKey.create(Registries.ITEM, SPRAY_CAN_ID))
			// durability() also pins the stack size to 1. Charges ride on vanilla's damage
			// component so the durability bar, the tooltip and the item's own plumbing all work
			// for free - the can simply refuses to paint at zero instead of breaking.
			.durability(DEFAULT_CHARGES)
			// Spraying goes through the item-use path to get the drawn-bow pose, and using an
			// item normally slows the player to a crawl and blocks sprinting. Painting a long
			// wall should not feel like wading, so the can opts out: full speed, sprint allowed.
			.component(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1.0F))));

		sink.accept(SCRUB_SPONGE_ID, () -> scrubSponge = new ScrubSpongeItem(new Item.Properties()
			.setId(ResourceKey.create(Registries.ITEM, SCRUB_SPONGE_ID))
			.durability(DEFAULT_SPONGE_DURABILITY)
			// Same reasoning as the can: the scrubbing pose comes from the item-use path, and
			// the usual use-slowdown would make cleaning a wall feel like wading.
			.component(DataComponents.USE_EFFECTS, new UseEffects(true, false, 1.0F))));
	}

	public static boolean isSprayCan(ItemStack stack) {
		return stack.getItem() instanceof SprayCanItem;
	}

	public static boolean isScrubSponge(ItemStack stack) {
		return stack.getItem() instanceof ScrubSpongeItem;
	}
}
