package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;

public class CpsHud extends TextHudElement {
	public final BooleanSetting rightClick = add(new BooleanSetting("cps_right", true));

	public CpsHud() {
		super("cps", true, 0.005, 0.075);
	}

	@Override
	protected String text(Minecraft minecraft) {
		int left = ClickTracker.cps(ClickTracker.LEFT);
		return rightClick.get() ? left + " | " + ClickTracker.cps(ClickTracker.RIGHT) + " CPS" : left + " CPS";
	}

	@Override
	protected String widestText() {
		return rightClick.get() ? "00 | 00 CPS" : "00 CPS";
	}
}
