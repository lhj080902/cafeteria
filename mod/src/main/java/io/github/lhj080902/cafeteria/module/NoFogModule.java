package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import net.minecraft.world.level.material.FogType;

/** 안개 제거. 먼 곳에 끼는 안개와 물·용암 속 안개를 없애서 맑게 보이게 한다. */
public class NoFogModule extends Module {
	/**
	 * 안개를 없앨 때 쓰는 시작·끝 거리.
	 * 안개가 끝나는 거리까지만 그리는 코드(다른 렌더링 모드 등)가 있어도 지형이 사라지지 않게 둘 다 아주 멀리 둔다.
	 */
	public static final float FAR_START = 1.0e9F;
	public static final float FAR_END = 2.0e9F;

	public final BooleanSetting distance = add(new BooleanSetting("no_fog_distance", true));
	public final BooleanSetting water = add(new BooleanSetting("no_fog_water", true));
	public final BooleanSetting lava = add(new BooleanSetting("no_fog_lava", true));

	public NoFogModule() {
		super("no_fog", false);
	}

	/** 카메라가 이런 곳에 있을 때의 안개를 없앨지. 가루눈 속과 모르는 종류(다른 모드가 추가한 것)는 그대로 둔다. */
	public boolean clears(FogType type) {
		if (!isEnabled()) {
			return false;
		}

		return switch (type) {
			case NONE -> distance.get();
			case WATER -> water.get();
			case LAVA -> lava.get();
			default -> false;
		};
	}
}
