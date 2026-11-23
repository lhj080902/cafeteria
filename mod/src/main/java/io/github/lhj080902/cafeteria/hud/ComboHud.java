package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/** 연속 적중 횟수. 콤보가 이어지는 동안에만 보인다. */
public class ComboHud extends TextHudElement {
	private static final int SAMPLE = 5;

	public ComboHud(double anchorX, double anchorY) {
		super("combo", false, anchorX, anchorY);
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return CombatTracker.combo() > 0;
	}

	@Override
	protected String text(Minecraft minecraft) {
		int combo = CombatTracker.combo();
		return I18n.get("cafeteria.hud.combo", combo > 0 ? combo : SAMPLE);
	}

	@Override
	protected String widestText() {
		return I18n.get("cafeteria.hud.combo", 10);
	}
}
