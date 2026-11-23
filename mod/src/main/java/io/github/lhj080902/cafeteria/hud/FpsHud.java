package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;

public class FpsHud extends TextHudElement {
	public FpsHud() {
		super("fps", true, 0.005, 0.01);
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
