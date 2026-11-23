package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.Locale;

/** 마지막 공격이 닿은 거리. 공격한 직후 잠깐 보인다. */
public class ReachHud extends TextHudElement {
	private static final double SAMPLE = 2.87;

	public ReachHud(double anchorX, double anchorY) {
		super("reach", false, anchorX, anchorY);
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return CombatTracker.reach() >= 0.0;
	}

	@Override
	protected String text(Minecraft minecraft) {
		double reach = CombatTracker.reach();
		return I18n.get("cafeteria.hud.reach", String.format(Locale.ROOT, "%.2f", reach >= 0.0 ? reach : SAMPLE));
	}

	@Override
	protected String widestText() {
		return I18n.get("cafeteria.hud.reach", "0.00");
	}
}
