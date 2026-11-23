package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** 한 줄짜리 글자 HUD (FPS, CPS, 핑). */
public abstract class TextHudElement extends HudElement {
	private static final int PADDING = 3;

	public final BooleanSetting background = add(new BooleanSetting("background", true));
	public final BooleanSetting shadow = add(new BooleanSetting("text_shadow", true));
	public final ColorSetting textColor = add(new ColorSetting("text_color", 0xFFFFFFFF));

	protected TextHudElement(String id, boolean defaultEnabled, double anchorX, double anchorY) {
		super(id, defaultEnabled, anchorX, anchorY);
	}

	protected abstract String text(Minecraft minecraft);

	/** 숫자 자릿수가 바뀔 때 상자 폭이 덜컹거리지 않도록 잡아 두는 최소 폭 기준 문자열. */
	protected abstract String widestText();

	@Override
	protected int contentWidth(Minecraft minecraft) {
		return Math.max(minecraft.font.width(text(minecraft)), minecraft.font.width(widestText())) + PADDING * 2;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		return minecraft.font.lineHeight + PADDING * 2 - 1;
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		String text = text(minecraft);
		int width = contentWidth(minecraft);

		if (background.get()) {
			graphics.fill(0, 0, width, contentHeight(minecraft), 0x80000000);
		}

		graphics.drawString(minecraft.font, text, (width - minecraft.font.width(text)) / 2, PADDING, textColor.get() | 0xFF000000, shadow.get());
	}
}
