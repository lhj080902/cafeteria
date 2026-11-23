package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 콤보와 리치(공격 거리) 표시를 위한 전투 기록.
 * 모두 내 클라이언트가 이미 알고 있는 정보(내가 누구를 때렸는지, 상대가 맞는 동작을 했는지)만 사용한다.
 */
public final class CombatTracker {
	/** 공격 후 이 시간 안에 상대가 맞는 동작을 하면 적중으로 본다 (핑이 높아도 인정되도록 1초). */
	private static final int CONFIRM_TICKS = 20;
	/** 마지막 적중 뒤 이 시간이 지나면 콤보가 끊긴다. */
	private static final int COMBO_TIMEOUT_TICKS = 60;
	/** 리치 표시가 유지되는 시간. */
	private static final int REACH_DISPLAY_TICKS = 60;

	private static int tick;
	private static int targetId = -1;
	private static int attackTick = -CONFIRM_TICKS - 1;
	/** 지난 틱에 본 상대의 hurtTime. 이 값보다 커지면 상대가 "새로 맞은" 것이다. */
	private static int targetHurtTime;
	private static int combo;
	private static int lastHitTick;
	private static double reach = -1.0;
	private static int reachTick;
	private static int ownHurtTime;

	private CombatTracker() {
	}

	/** 내가 엔티티를 공격했을 때 호출된다. */
	public static void onAttack(Minecraft minecraft, Entity target) {
		if (minecraft.player == null || !(target instanceof LivingEntity living)) {
			return;
		}

		Vec3 eye = minecraft.player.getEyePosition();

		if (minecraft.hitResult instanceof EntityHitResult hit && hit.getEntity() == target) {
			// 조준선이 실제로 닿은 지점까지의 거리
			reach = eye.distanceTo(hit.getLocation());
		} else {
			AABB box = target.getBoundingBox();
			reach = eye.distanceTo(new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ)));
		}

		reachTick = tick;

		// 상대가 바뀌면 그 상대의 현재 상태부터 다시 지켜본다. 같은 상대를 연타하는 동안에는 기록을 이어 간다.
		if (targetId != target.getId()) {
			targetId = target.getId();
			targetHurtTime = living.hurtTime;
		}

		attackTick = tick;
	}

	public static void tick(Minecraft minecraft) {
		tick++;

		if (minecraft.player == null || minecraft.level == null) {
			reset();
			return;
		}

		// 내가 새로 맞으면 콤보가 끊긴다.
		int hurtTime = minecraft.player.hurtTime;

		if (hurtTime > ownHurtTime) {
			combo = 0;
		}

		ownHurtTime = hurtTime;

		if (minecraft.level.getEntity(targetId) instanceof LivingEntity living) {
			// hurtTime 은 맞는 순간 최댓값이 되고 매 틱 줄어든다. 값이 커졌다면 방금 새로 맞은 것이고,
			// 내가 공격한 직후라면 내 공격이 들어간 것으로 본다.
			if (living.hurtTime > targetHurtTime && tick - attackTick <= CONFIRM_TICKS) {
				combo++;
				lastHitTick = tick;
			}

			targetHurtTime = living.hurtTime;
		} else {
			targetId = -1;
		}

		if (combo > 0 && tick - lastHitTick > COMBO_TIMEOUT_TICKS) {
			combo = 0;
		}
	}

	private static void reset() {
		targetId = -1;
		combo = 0;
		reach = -1.0;
		ownHurtTime = 0;
	}

	/** 연속으로 적중시킨 횟수. */
	public static int combo() {
		return combo;
	}

	/** 마지막 공격의 거리(블록). 최근에 공격한 적이 없으면 음수. */
	public static double reach() {
		return reach >= 0.0 && tick - reachTick <= REACH_DISPLAY_TICKS ? reach : -1.0;
	}
}
