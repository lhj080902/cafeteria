package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class HudRenderer {
	/** HUD 를 그린 횟수. 바닐라 요소(타이틀 등)가 "방금 프레임에 그려졌는지"를 가리는 기준으로 쓴다. */
	private static long frame;

	private HudRenderer() {
	}

	/** 지금까지 HUD 를 그린 프레임 수. 바닐라의 스코어보드·타이틀·보스바는 같은 프레임에서 이 숫자가 오르기 전에 그려진다. */
	public static long frame() {
		return frame;
	}

	public static void render(GuiGraphics graphics) {
		frame++;
		Minecraft minecraft = Minecraft.getInstance();

		// F1(HUD 숨김), F3 디버그 화면, HUD 위치 편집 화면에서는 그리지 않는다.
		if (minecraft.player == null || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen() || minecraft.screen instanceof HudEditScreen) {
			return;
		}

		for (HudElement element : Modules.HUD) {
			if (element.isEnabled() && element.hasContent(minecraft)) {
				element.render(graphics, minecraft);
			}
		}
	}
}
