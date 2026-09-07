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
		// Primeiro a física vanilla (gravidade, atrito, colisão). Depois o cérebro
		// impõe a velocidade desejada por cima, sem depender do motor de input do Mob.
		super.aiStep();
		if (this.level().isClientSide) return;

		applyLook();

		if (MinecraftForFlies.targetJumping && this.onGround()) {
			this.jumpFromGround();
		}

		drive();
	}

	private void applyLook() {
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
	}

	/**
	 * Traduz (forward, strafe) do cérebro num vetor de velocidade horizontal,
	 * no referencial da rotação atual, e acelera o corpo naquela direção.
	 */
	private void drive() {
		float fwd = MinecraftForFlies.targetForward;
		float strafe = MinecraftForFlies.targetStrafing;
		if (fwd == 0f && strafe == 0f) return;

		double yaw = Math.toRadians(this.getYRot());
		double cos = Math.cos(yaw);
		double sin = Math.sin(yaw);
		double speed = this.isInWater() ? 0.10 : 0.25;
		float tx = (float) ((-sin * fwd + cos * strafe) * speed);
		float tz = (float) ((cos * fwd + sin * strafe) * speed);

		Vec3 d = this.getDeltaMovement();
		this.setDeltaMovement(
				Mth.approach((float) d.x, tx, 0.08f),
				d.y,
				Mth.approach((float) d.z, tz, 0.08f));
	}
}
