package com.evilfly.fly;

import com.evilfly.MinecraftForFlies;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Drosophila melanogaster.
 * Persistente (nao despawna) e controlada pelo cerebro (converter.py) via statics.
 */
public class DrosophilaEntity extends PathfinderMob {
	public DrosophilaEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0) // player-like
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.FOLLOW_RANGE, 40.0);
	}

	@Override
	public MobType getMobType() {
		return MobType.ARTHROPOD;
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	protected void registerGoals() {
		// movimento vem via aiStep/travel do Mob base
	}

	@Override
	public void aiStep() {
		if (!this.level().isClientSide) {
			// Rotação: aplica antes do super.aiStep() para que o vetor de
			// movimento (com base no yaw) use a orientação já atualizada.
			float yaw = MinecraftForFlies.targetYawDelta;
			if (yaw != 0f) {
				this.setYRot(this.getYRot() + yaw);
				this.yHeadRot = this.getYRot();
				MinecraftForFlies.targetYawDelta = 0f;
			}
			float pitch = MinecraftForFlies.targetPitchDelta;
			if (pitch != 0f) {
				this.setXRot(Mth.clamp(this.getXRot() + pitch, -90f, 90f));
				MinecraftForFlies.targetPitchDelta = 0f;
			}

			// Pulo: pula se no chão.
			if (MinecraftForFlies.targetJumping && this.onGround()) {
				this.jumpFromGround();
			}
		}

		// Física vanilla (gravidade, atrito, colisão).
		super.aiStep();
	}

	/**
	 * Ponto crucial: LivingEntity.aiStep() roda o AI vanilla (goalSelector,
	 * navigation, moveControl) via serverAiStep() ANTES de montar o Vec3 do
	 * travel() — e, sem nenhum goal/path, ele zera zza/xxa. Por isso NÃO
	 * adiantava setar inputs antes do super.aiStep().
	 *
	 * Aqui injetamos os valores do cérebro DIRETO no travel(), contornando
	 * qualquer interferência do AI vanilla. Ordem do Vec3: (strafe, up, forward).
	 */
	@Override
	public void travel(Vec3 travelInput) {
		if (!this.level().isClientSide) {
			// Telemetria: a cada ~2s (40 ticks), confirma no log que o travel()
			// está rodando com os valores do cérebro.
			if (this.tickCount % 40 == 0) {
				MinecraftForFlies.LOGGER.info(
						"[Drosophila] travel: fwd={} strafe={} | pos=({},{},{}) yaw={} chao={}",
						MinecraftForFlies.targetForward, MinecraftForFlies.targetStrafing,
						this.getX(), this.getY(), this.getZ(), this.getYRot(), this.onGround());
			}
			super.travel(new Vec3(
					MinecraftForFlies.targetStrafing,
					travelInput.y,
					MinecraftForFlies.targetForward));
		} else {
			super.travel(travelInput);
		}
	}
}
