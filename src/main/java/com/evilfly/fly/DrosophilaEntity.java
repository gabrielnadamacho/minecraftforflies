package com.evilfly.fly;

import com.evilfly.MinecraftForFlies;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;

/**
 * A Drosophila melanogaster. Persistente (nao despawna) e controlada pelo
 * cerebro (converter.py) atraves das statics compartilhadas em MinecraftForFlies.
 */
public class DrosophilaEntity extends PathfinderMob {
	public DrosophilaEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired(); // NBT PersistenceRequired:1b — nunca despawna
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 8.0)
				.add(Attributes.MOVEMENT_SPEED, 0.35);
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
		this.goalSelector.addGoal(0, new BrainControlledGoal(this));
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (!this.isEffectiveAi()) return; // apenas lado servidor

		float yaw = MinecraftForFlies.targetYawDelta;
		if (yaw != 0f) {
			this.setYRot(this.getYRot() + yaw);
			MinecraftForFlies.targetYawDelta = 0f;
		}
		float pitch = MinecraftForFlies.targetPitchDelta;
		if (pitch != 0f) {
			this.setXRot(Mth.clamp(this.getXRot() + pitch, -90f, 90f));
			MinecraftForFlies.targetPitchDelta = 0f;
		}
	}

	/**
	 * Traduz os comandos do cerebro (forward/strafe/jump) em movimento proprio,
	 * espelhando o comportamento original do player: anda no chao, pula quando pedido.
	 */
	private static class BrainControlledGoal extends Goal {
		private final DrosophilaEntity mob;

		BrainControlledGoal(DrosophilaEntity mob) {
			this.mob = mob;
		}

		@Override
		public boolean canUse() {
			return true;
		}

		@Override
		public boolean canContinueToUse() {
			return true;
		}

		@Override
		public boolean isInterruptable() {
			return false;
		}

		@Override
		public void tick() {
			mob.setZza(MinecraftForFlies.targetForward);
			mob.setXxa(MinecraftForFlies.targetStrafing);
			if (MinecraftForFlies.targetJumping && mob.onGround()) {
				mob.jumpFromGround();
			}
		}
	}
}