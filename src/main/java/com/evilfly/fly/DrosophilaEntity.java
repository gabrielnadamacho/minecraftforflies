package com.evilfly.fly;

import com.evilfly.MinecraftForFlies;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * Drosophila melanogaster.
 * Persistente (nao despawna) e controlada pelo cerebro (converter.py) via statics.
 */
public class DrosophilaEntity extends PathfinderMob {
	private int attackCooldown = 0;

	/** Cérebro (converter.py) está vivo? Usa heartbeat do último /action. */
	private boolean brainAlive() {
		return System.currentTimeMillis() - MinecraftForFlies.lastActionAt < MinecraftForFlies.BRAIN_DEAD_MS;
	}

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
			if (this.attackCooldown > 0) this.attackCooldown--;
			boolean alive = this.brainAlive();
			if (alive) {
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

				// Ataque: se o cérebro mandou atacar e há um mob hostil por perto.
				this.maybeAttack();
			} else {
				// Cérebro morto/desconectado: zera o motor para a física vanilla não
				// usar comandos velhos. travel() também faz gate por brainAlive().
				MinecraftForFlies.targetForward = 0f;
				MinecraftForFlies.targetStrafing = 0f;
				MinecraftForFlies.targetJumping = false;
				MinecraftForFlies.targetAttacking = false;
			}
		}

		// Física vanilla (gravidade, atrito, colisão).
		super.aiStep();
	}

	/** Conecta targetAttacking do cérebro a um ataque corpo-a-corpo real. */
	private void maybeAttack() {
		if (!MinecraftForFlies.targetAttacking || this.attackCooldown > 0) return;
		Monster target = this.level().getEntitiesOfClass(Monster.class,
				this.getBoundingBox().inflate(3.0), e -> !e.equals(this) && e.isAlive())
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(this))).orElse(null);
		if (target != null) {
			this.doHurtTarget(target);
			MinecraftForFlies.targetAttacking = false; // consome até o cérebro re-enviar
			this.attackCooldown = 15;
		}
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
			// ROOT CAUSE do congelamento: LivingEntity.travel() (no chão) usa
			// getFrictionInfluencedSpeed() -> getSpeed(), que retorna o CAMPO
			// `speed` (float), NÃO o atributo MOVEMENT_SPEED. No AI vanilla esse
			// campo é preenchido por MoveControl/navigation via setSpeed() a cada
			// tick. Como esta mosca é 100% dirigida pelo cérebro (sem goals), nada
			// chamava setSpeed() -> o campo ficava 0.0 e o impulso horizontal = 0.
			// Sincronizamos o campo com o atributo a cada tick, pois é o que o
			// travel() consome para mover no chão.
			var speedAttr = this.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speedAttr != null) {
				this.setSpeed((float) speedAttr.getValue());
			}

			// Cérebro morto => comando zero (a mosca para em vez de andar no último input).
			boolean alive = this.brainAlive();
			float fwd = alive ? MinecraftForFlies.targetForward : 0f;
			float strafe = alive ? MinecraftForFlies.targetStrafing : 0f;

			if (this.tickCount % 40 == 0) {
				// Diagnóstico: agora speed deve == MOVEMENT_SPEED (0.3) e a posição muda.
				net.minecraft.world.phys.Vec3 vel = this.getDeltaMovement();
				MinecraftForFlies.LOGGER.info(
						"[Drosophila] travel: fwd={} strafe={} pos=({},{},{}) yaw={} chao={} brain={}"
						+ " | effAi={} noAi={} dead={} removed={} speed={}"
						+ " | velMag={} velY={} inputY={}",
						fwd, strafe,
						this.getX(), this.getY(), this.getZ(), this.getYRot(), this.onGround(), alive,
						this.isEffectiveAi(), this.isNoAi(), this.isDeadOrDying(), this.isRemoved(),
						this.getSpeed(),
						vel.horizontalDistanceSqr(), vel.y, travelInput.y);
			}
			super.travel(new Vec3(strafe, travelInput.y, fwd));
		} else {
			super.travel(travelInput);
		}
	}
}
