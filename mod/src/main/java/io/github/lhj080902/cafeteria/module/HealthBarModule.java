package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import io.github.lhj080902.cafeteria.module.setting.NumberSetting;
import io.github.lhj080902.cafeteria.render.HealthBarRenderer;
import net.minecraft.client.Minecraft;

/**
 * 체력바. 다른 플레이어(설정에 따라 모든 생물)의 머리 위에 이름, 체력, 방어도를 보여 준다.
 * 여기에는 설정만 있고, 대상을 고르고 그리는 일은 HealthBarRenderer 가 한다.
 * 서버에 따라 금지하는 기능이라 기본으로 꺼져 있다.
 */
public class HealthBarModule extends Module {
	public final ModeSetting target = add(new ModeSetting("health_bar_target", "players", "players", "living"));
	public final BooleanSetting showName = add(new BooleanSetting("health_bar_name", true));
	public final BooleanSetting showNumber = add(new BooleanSetting("health_bar_number", true));
	public final BooleanSetting showArmor = add(new BooleanSetting("health_bar_armor", true));
	public final NumberSetting scale = add(new NumberSetting("health_bar_scale", 1.0, 0.5, 3.0, 0.1, "x"));
	/** 기준점을 위아래로 옮기는 거리(블록). 이름 아래에 점수를 띄우는 서버에서는 바닐라 이름이 더 위에 있어서 이것으로 맞춘다. */
	public final NumberSetting offset = add(new NumberSetting("health_bar_offset", 0.0, -0.5, 1.5, 0.05, ""));
	public final NumberSetting distance = add(new NumberSetting("health_bar_distance", 32.0, 8.0, 64.0, 1.0, ""));
	public final BooleanSetting background = add(new BooleanSetting("background", true));

	public HealthBarModule() {
		super("health_bar", false);
	}

	@Override
	public void tick(Minecraft minecraft) {
		if (isEnabled()) {
			HealthBarRenderer.updateTargets(minecraft);
		}
	}
}
