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
			// 1) Input do cérebro → campos do Mob ANTES do super.aiStep()
			//    para que LivingEntity.travel() os consuma neste tick.
			float fwd = MinecraftForFlies.targetForward;
			float strafe = MinecraftForFlies.targetStrafing;
			this.setZza(fwd);
			this.setXxa(strafe);

			// Rotação: aplica antes do travel para que o vetor de movimento
			// use o yaw já atualizado.
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

			// Pulo: consome o flag e pula se no chão.
			if (MinecraftForFlies.targetJumping && this.onGround()) {
				this.jumpFromGround();
			}
		}

		// 2) Física vanilla (gravidade, atrito, colisão, travel com nossos zza/xxa).
		super.aiStep();
	}
}
