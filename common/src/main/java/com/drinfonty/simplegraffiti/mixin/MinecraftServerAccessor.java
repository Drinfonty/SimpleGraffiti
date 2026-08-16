package com.drinfonty.simplegraffiti.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads {@code MinecraftServer.storageSource}, which is {@code protected} and has no public getter.
 *
 * <p>Graffiti needs exactly one thing from it: the on-disk path of a dimension, so its region files
 * can sit beside vanilla's. An accessor rather than an injection, so there is no method body to
 * re-derive when a point release moves code around.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	@Accessor("storageSource")
	LevelStorageSource.LevelStorageAccess simpleGraffiti$storageSource();
}
