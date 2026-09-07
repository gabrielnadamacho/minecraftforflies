package com.evilfly;

import com.evilfly.fly.DrosophilaEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

public class MinecraftForFlies implements ModInitializer {
	public static final String MOD_ID = "minecraft-for-flies";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// --- Ponte cérebro<->corpo -------------------------------------------------
	// Statics compartilhadas entre o HTTP server (client) e a entidade (servidor).
	// Vivem no source set main para que ambos os lados possam enxergá-las.
	public static volatile float targetForward = 0f;
	public static volatile float targetStrafing = 0f;
	public static volatile boolean targetJumping = false;
	public static volatile boolean targetAttacking = false;
	public static volatile float targetYawDelta = 0f;
	public static volatile float targetPitchDelta = 0f;
	public static volatile String lastThoughts = "Drosophila melanogaster ativa no substrato.";
	public static volatile UUID activeFlyUuid = null;

	public static final EntityType<DrosophilaEntity> DROSOPHILA = Registry.register(
			BuiltInRegistries.ENTITY_TYPE, id("drosophila"),
			EntityType.Builder.of(DrosophilaEntity::new, MobCategory.CREATURE)
					.sized(0.4F, 0.4F)
					.build("drosophila"));

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(DROSOPHILA, DrosophilaEntity.createAttributes());
		registerCommands();
		LOGGER.info("Minecraft for Flies inicializado.");
	}

	// ---------------------------------------------------------------------------
	// Comandos: /fly spawn | kill | tp <x y z> | info
	// ---------------------------------------------------------------------------

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("fly")
				.then(Commands.literal("spawn").executes(ctx -> spawnFly(ctx.getSource())))
				.then(Commands.literal("kill").executes(ctx -> killFlies(ctx.getSource())))
				.then(Commands.literal("tp")
					.then(Commands.argument("pos", Vec3Argument.vec3())
						.executes(ctx -> teleportFly(ctx.getSource(), Vec3Argument.getVec3(ctx, "pos")))))
				.then(Commands.literal("info").executes(ctx -> flyInfo(ctx.getSource())))));
	}

	private static int spawnFly(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		DrosophilaEntity found = findFly(level);
		if (found == null) {
			found = new DrosophilaEntity(DROSOPHILA, level);
		}
		Vec3 pos = source.getPosition();
		found.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, source.getRotation().x);
		found.setCustomName(Component.literal("Drosophila"));
		found.setCustomNameVisible(true);
		found.setHealth(found.getMaxHealth());
		if (level.addFreshEntity(found)) {
			activeFlyUuid = found.getUUID();
			source.sendSuccess(() -> Component.literal(
					"Drosophila spawnada em " + posToString(pos) + " (persistente)."), true);
		} else {
			source.sendFailure(Component.literal("Não foi possível spawnar a Drosophila."));
		}
		return 1;
	}

	private static int killFlies(CommandSourceStack source) {
		List<DrosophilaEntity> flies = getFlies(source.getLevel());
		for (DrosophilaEntity fly : flies) {
			fly.discard();
		}
		source.sendSuccess(() -> Component.literal(flies.size() + " Drosophila(s) removida(s)."), true);
		activeFlyUuid = null;
		return 1;
	}

	private static int teleportFly(CommandSourceStack source, Vec3 target) {
		DrosophilaEntity fly = findFly(source.getLevel());
		if (fly == null) {
			source.sendFailure(Component.literal("Nenhuma Drosophila spawnada. Use /fly spawn."));
			return 0;
		}
		fly.teleportTo(target.x, target.y, target.z);
		source.sendSuccess(() -> Component.literal("Drosophila teleportada para " + posToString(target) + "."), true);
		return 1;
	}

	private static int flyInfo(CommandSourceStack source) {
		List<DrosophilaEntity> flies = getFlies(source.getLevel());
		if (flies.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nenhuma Drosophila ativa neste mundo. Use /fly spawn."), false);
			return 1;
		}
		for (DrosophilaEntity fly : flies) {
			source.sendSuccess(() -> Component.literal(String.format(
					"Drosophila [UUID: %s] em (%s) | Vida: %s/%s | Persistente: sim",
					fly.getUUID().toString().substring(0, 8),
					posToString(fly.position()),
					(int) fly.getHealth(), (int) fly.getMaxHealth())), false);
		}
		return 1;
	}

	private static DrosophilaEntity findFly(ServerLevel level) {
		UUID uuid = activeFlyUuid;
		if (uuid != null) {
			Entity e = level.getEntity(uuid);
			if (e instanceof DrosophilaEntity fly) return fly;
		}
		List<DrosophilaEntity> flies = getFlies(level);
		return flies.isEmpty() ? null : flies.get(0);
	}

	private static List<DrosophilaEntity> getFlies(ServerLevel level) {
		return level.getEntitiesOfClass(DrosophilaEntity.class,
				new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
						Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
	}

	private static String posToString(Vec3 pos) {
		return String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}