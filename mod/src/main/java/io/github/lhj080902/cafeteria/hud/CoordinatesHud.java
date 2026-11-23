package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

/** 현재 좌표와 바라보는 방향. */
public class CoordinatesHud extends TextHudElement {
	public final BooleanSetting direction = add(new BooleanSetting("coords_direction", true));

	public CoordinatesHud(double anchorX, double anchorY) {
		super("coordinates", false, anchorX, anchorY);
	}

	@Override
	protected String text(Minecraft minecraft) {
		if (minecraft.player == null) {
			return widestText();
		}

		String text = "XYZ: " + Mth.floor(minecraft.player.getX()) + " / " + Mth.floor(minecraft.player.getY()) + " / " + Mth.floor(minecraft.player.getZ());
		return direction.get() ? text + "  " + I18n.get("cafeteria.direction." + minecraft.player.getDirection().getName()) : text;
	}

	@Override
	protected String widestText() {
		return direction.get() ? "XYZ: -1000 / 100 / -1000  " + I18n.get("cafeteria.direction.north") : "XYZ: -1000 / 100 / -1000";
	}
}
