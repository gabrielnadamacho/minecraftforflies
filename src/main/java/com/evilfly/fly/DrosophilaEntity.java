package com.evilfly.fly;

import com.evilfly.MinecraftForFlies;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Drosophila melanogaster.
 * Persistente (nao despawna) e controlada pelo cerebro (converter.py) via statics.
 */
public class DrosophilaEntity extends PathfinderMob {
	private int attackCooldown = 0;
	private int stuckTicks = 0;
	private double lastX, lastZ;
	// Último item pego (para logs/diagnóstico, sem afetar física)
	private String lastPickup = "";

	/** Cérebro (converter.py) está vivo? Usa heartbeat do último /action. */
	private boolean brainAlive() {
		return System.currentTimeMillis() - MinecraftForFlies.lastActionAt < MinecraftForFlies.BRAIN_DEAD_MS;
	}

	public DrosophilaEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
		this.setCanPickUpLoot(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.3)
				.add(Attributes.ATTACK_DAMAGE, 2.0)
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
				if (MinecraftForFlies.targetJumping && this.onGround()) {
					this.jumpFromGround();
				}
				this.maybePickupWeapon();
				this.maybeAttack();
				this.tickStuckDetection();
			} else {
				MinecraftForFlies.targetForward = 0f;
				MinecraftForFlies.targetStrafing = 0f;
				MinecraftForFlies.targetJumping = false;
				MinecraftForFlies.targetAttacking = false;
			}
		}
		super.aiStep();
	}

	/** Tenta pegar uma arma/itens do chão próxima (raio 2 blocos). Equipa no main hand. */
	private void maybePickupWeapon() {
		if (!this.getMainHandItem().isEmpty()) return;
		List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class,
				this.getBoundingBox().inflate(2.0), e -> e.isAlive() && !e.getItem().isEmpty());
		if (items.isEmpty()) return;
		// Prefere espadas/armas, senão o item mais próximo
		ItemEntity best = null;
		for (ItemEntity ie : items) {
			if (ie.getItem().getItem() instanceof SwordItem) { best = ie; break; }
		}
		if (best == null) {
			best = items.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(this))).orElse(null);
		}
		if (best == null) return;
		// Só "aspira" se estiver bem perto; senão o cérebro a guia por forward/yaw
		if (best.distanceToSqr(this) > 2.5) return;
		ItemStack stack = best.getItem().copy();
		stack.setCount(1);
		this.setItemSlot(EquipmentSlot.MAINHAND, stack);
		if (stack.getCount() >= best.getItem().getCount()) best.discard();
		else best.getItem().shrink(1);
		this.lastPickup = stack.getHoverName().getString();
		MinecraftForFlies.LOGGER.info("[Drosophila] pegou item: {}", this.lastPickup);
	}

	@Override
	protected void pickUpItem(ItemEntity itemEntity) {
		// Vanilla pickup é para mobs com loot; deixamos maybePickupWeapon() decidir,
		// mas não rejeitamos — se colidir e a mão estiver vazia, equipa.
		if (!this.getMainHandItem().isEmpty()) return;
		ItemStack s = itemEntity.getItem().copy();
		s.setCount(1);
		this.setItemSlot(EquipmentSlot.MAINHAND, s);
		if (s.getCount() >= itemEntity.getItem().getCount()) itemEntity.discard();
		else itemEntity.getItem().shrink(1);
	}

	private void maybeAttack() {
		if (!MinecraftForFlies.targetAttacking || this.attackCooldown > 0) return;
		// Se houver ameaça por perto, garante que estamos olhando para ela antes de bater
		Monster target = this.level().getEntitiesOfClass(Monster.class,
				this.getBoundingBox().inflate(3.5), e -> !e.equals(this) && e.isAlive())
				.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(this))).orElse(null);
		if (target != null) {
			// Vira levemente na direção do alvo se estiver muito fora de ângulo (> 45°)
			double dx = target.getX() - this.getX();
			double dz = target.getZ() - this.getZ();
			float wantYaw = (float)(Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
			float diff = Mth.wrapDegrees(wantYaw - this.getYRot());
			if (Math.abs(diff) > 45f) {
				this.setYRot(this.getYRot() + Mth.clamp(diff, -30f, 30f));
				this.yHeadRot = this.getYRot();
			}
			this.doHurtTarget(target);
			MinecraftForFlies.targetAttacking = false;
			this.attackCooldown = 12;
		} else {
			// Sem alvo válido: consome o flag para não ficar atacando o ar todo tick
			MinecraftForFlies.targetAttacking = false;
		}
	}

	/** Detecta travamento (sem progresso horizontal com comando forward) e aplica correção autônoma. */
	private void tickStuckDetection() {
		boolean hasCmd = Math.abs(MinecraftForFlies.targetForward) > 0.01f;
		double dx = this.getX() - this.lastX;
		double dz = this.getZ() - this.lastZ;
		double moved2 = dx * dx + dz * dz;
		if (hasCmd && moved2 < 1e-4 && this.onGround()) {
			this.stuckTicks++;
		} else {
			this.stuckTicks = 0;
		}
		this.lastX = this.getX();
		this.lastZ = this.getZ();
		if (this.stuckTicks > 10) {
			// Desencrava: strafing + yaw aleatório + pulo ocasional
			float yawFix = (this.random.nextBoolean() ? 1 : -1) * (55f + this.random.nextFloat() * 55f);
			this.setYRot(this.getYRot() + yawFix);
			this.yHeadRot = this.getYRot();
			MinecraftForFlies.targetStrafing = this.random.nextBoolean() ? 0.7f : -0.7f;
			if (this.random.nextFloat() < 0.55f && this.onGround()) this.jumpFromGround();
			MinecraftForFlies.LOGGER.info("[Drosophila] stuck-escape: yawFix={} strafe={} pos=({},{},{})",
					yawFix, MinecraftForFlies.targetStrafing, this.getX(), this.getY(), this.getZ());
			this.stuckTicks = 0;
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
