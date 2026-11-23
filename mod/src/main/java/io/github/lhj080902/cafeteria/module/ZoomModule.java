package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.KeyState;
import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 줌. 키를 누르는 동안 시야각(FOV)을 배율만큼 나눈다.
 * 월드를 그릴 때만 적용되고 손(아이템)의 크기는 바뀌지 않는다. 대신 줌하는 동안 손을 화면 아래로 내릴 수 있다.
 * GameRendererMixin 이 프레임마다 currentFactor() 를 호출하므로 전환 애니메이션은 실제 경과 시간 기준으로 계산한다.
 */
public class ZoomModule extends Module {
	private static final double MIN_SCROLL = 0.25;
	private static final double MAX_SCROLL = 8.0;
	private static final double MAX_FACTOR = 40.0;
	/** 배율이 1 에서 이만큼 커지는 동안 손이 완전히 내려간다 (1.4배면 화면 밖). */
	private static final double HAND_HIDE_RANGE = 0.4;

	public final NumberSetting factor = add(new NumberSetting("zoom_factor", 4.0, 1.5, 10.0, 0.5, "x"));
	public final BooleanSetting smooth = add(new BooleanSetting("zoom_smooth", true));
	public final BooleanSetting scrollAdjust = add(new BooleanSetting("zoom_scroll", true));
	public final BooleanSetting reduceSensitivity = add(new BooleanSetting("zoom_sensitivity", true));
	public final BooleanSetting cinematic = add(new BooleanSetting("zoom_cinematic", false));
	/** 줌하는 동안 1인칭 손(든 아이템)을 화면 아래로 내려서 시야를 가리지 않게 한다 (옵티파인·루나의 줌과 같은 모습). */
	public final BooleanSetting hideHand = add(new BooleanSetting("zoom_hand_hidden", true));

	private boolean zooming;
	private double scroll = 1.0;
	private double current = 1.0;
	private long lastUpdate;
	private boolean cinematicApplied;
	private boolean previousSmoothCamera;

	public ZoomModule() {
		super("zoom", true);
	}

	public boolean isZooming() {
		return zooming;
	}

	/** 마지막 프레임에 화면에 적용된 확대 배율. 상태를 바꾸지 않는 읽기 전용 값이다. */
	public double displayedFactor() {
		return current;
	}

	/** 현재 적용 중인 확대 배율(1.0 = 확대 없음). 프레임마다 한 번 이상 호출된다. */
	public float currentFactor() {
		Minecraft minecraft = Minecraft.getInstance();
		boolean wanted = isEnabled() && minecraft.player != null && KeyState.isHeld(CafeteriaClient.zoomKey);

		if (wanted != zooming) {
			zooming = wanted;

			if (wanted) {
				scroll = 1.0;

				if (cinematic.get()) {
					previousSmoothCamera = minecraft.options.smoothCamera;
					minecraft.options.smoothCamera = true;
					cinematicApplied = true;
				}
			} else if (cinematicApplied) {
				minecraft.options.smoothCamera = previousSmoothCamera;
				cinematicApplied = false;
			}
		}

		double target = zooming ? Mth.clamp(factor.get() * scroll, 1.0, MAX_FACTOR) : 1.0;
		long now = System.nanoTime();
		double seconds = lastUpdate == 0L ? 1.0 : (now - lastUpdate) / 1.0e9;
		lastUpdate = now;

		if (!smooth.get() || seconds >= 0.5) {
			current = target;
		} else {
			current += (target - current) * (1.0 - Math.exp(-seconds * 18.0));

			if (Math.abs(target - current) < 0.005) {
				current = target;
			}
		}

		return (float) current;
	}

	/**
	 * 줌 때문에 1인칭 손을 화면 아래로 얼마나 내릴지 (0 = 평소 위치, 1 = 화면 밖).
	 * 확대가 시작되면 부드럽게 내려가고, 줌을 풀면 다시 올라온다.
	 */
	public float handHiddenAmount() {
		if (!isEnabled() || !hideHand.get()) {
			return 0.0F;
		}

		double progress = Mth.clamp((current - 1.0) / HAND_HIDE_RANGE, 0.0, 1.0);
		return (float) (progress * progress * (3.0 - 2.0 * progress));
	}

	/** 확대한 만큼 마우스 감도를 낮춰서 조준이 튀지 않게 한다. */
	public double sensitivityScale() {
		return reduceSensitivity.get() && current > 1.0 ? 1.0 / current : 1.0;
	}

	/** 줌 중에 휠을 굴리면 배율을 바꾼다. 입력을 소비했으면 true (핫바 슬롯이 바뀌지 않게 한다). */
	public boolean onScroll(double amount) {
		if (!zooming || !scrollAdjust.get() || amount == 0.0) {
			return false;
		}

		scroll = Mth.clamp(scroll * (amount > 0.0 ? 1.15 : 1.0 / 1.15), MIN_SCROLL, MAX_SCROLL);
		return true;
	}
}
