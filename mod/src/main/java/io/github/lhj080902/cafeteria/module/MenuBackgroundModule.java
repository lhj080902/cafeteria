package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.NumberSetting;

/**
 * 깨끗한 메뉴 배경. 메뉴(ESC, 모드 설정 등)를 열었을 때 뒤 화면을 흐리게 하는 효과를 끄고,
 * 얼마나 어둡게 덮을지를 직접 정한다. 흐림 효과가 검게 나오는 환경에서도 게임 화면이 그대로 보인다.
 */
public class MenuBackgroundModule extends Module {
	public final BooleanSetting blur = add(new BooleanSetting("menu_blur", false));
	/** 바닐라의 메뉴 배경 그림은 검은색 25% 이다. */
	public final NumberSetting dim = add(new NumberSetting("menu_dim", 25.0, 0.0, 80.0, 5.0, "%"));

	public MenuBackgroundModule() {
		super("menu_background", true);
	}

	public boolean skipsBlur() {
		return isEnabled() && !blur.get();
	}

	/** 배경 위에 덮을 색(검은색 + 설정한 불투명도). 0 이면 아무것도 덮지 않는다. */
	public int dimColor() {
		return (int) Math.round(dim.get() * 255.0 / 100.0) << 24;
	}
}
