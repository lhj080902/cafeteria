package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class HudRenderer {
	private HudRenderer() {
	}

	public static void render(GuiGraphics graphics) {
		Minecraft minecraft = Minecraft.getInstance();

		// F1(HUD 숨김), F3 디버그 화면, HUD 위치 편집 화면에서는 그리지 않는다.
		if (minecraft.player == null || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen() || minecraft.screen instanceof HudEditScreen) {
			return;
		}

		for (HudElement element : Modules.HUD) {
			if (element.isEnabled()) {
				element.render(graphics, minecraft);
			}
		}
	}
}
