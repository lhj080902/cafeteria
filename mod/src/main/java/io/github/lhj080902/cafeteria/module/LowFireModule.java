package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.NumberSetting;

/** 낮은 화염. 불이 붙었을 때 시야를 가리는 불꽃 효과를 화면 아래쪽으로 내린다. */
public class LowFireModule extends Module {
	/** 100% 일 때 불꽃을 내리는 최대 거리 (화면 높이 기준 단위) */
	private static final float MAX_OFFSET = 0.5F;

	public final NumberSetting amount = add(new NumberSetting("low_fire_amount", 60.0, 10.0, 100.0, 10.0, "%"));

	public LowFireModule() {
		super("low_fire", false);
	}

	/** 불꽃을 아래로 내릴 거리. 모듈이 꺼져 있으면 0. */
	public float offset() {
		return isEnabled() ? MAX_OFFSET * amount.asFloat() / 100.0F : 0.0F;
	}
}
