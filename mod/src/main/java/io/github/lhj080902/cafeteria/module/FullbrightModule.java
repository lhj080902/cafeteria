package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.NumberSetting;

/** 풀브라이트. 라이트맵을 만들 때 쓰는 밝기(감마) 값을 높여서 어두운 곳도 밝게 보이게 한다. */
public class FullbrightModule extends Module {
	public final NumberSetting level = add(new NumberSetting("fullbright_level", 10.0, 1.0, 15.0, 1.0, ""));

	public FullbrightModule() {
		super("fullbright", false);
	}
}
