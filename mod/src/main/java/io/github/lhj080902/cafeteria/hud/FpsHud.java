package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;

public class FpsHud extends TextHudElement {
	public FpsHud(double anchorX, double anchorY) {
		super("fps", true, anchorX, anchorY);
	}

	@Override
	protected String text(Minecraft minecraft) {
		return minecraft.getFps() + " FPS";
	}

	@Override
	protected String widestText() {
		return "999 FPS";
	}
}
