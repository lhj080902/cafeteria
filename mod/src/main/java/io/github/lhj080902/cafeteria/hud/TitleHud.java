package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 화면 가운데에 크게 뜨는 타이틀과 부제목. 둘을 한 덩어리로 옮기고 크기를 바꾼다. */
public class TitleHud extends VanillaHudElement {
	/** 바닐라가 타이틀과 부제목에 거는 배율. */
	private static final int TITLE_SCALE = 4;
	private static final int SUBTITLE_SCALE = 2;

	public TitleHud() {
		super("title");
	}

	/**
	 * 바닐라(Gui.renderTitle)가 타이틀과 부제목을 그리는 상자 {x, y, 너비, 높이}.
	 * 타이틀은 화면 가운데에서 4배로 (-너비/2, -10) 에, 부제목은 2배로 (-너비/2, 5) 에 그린다. 글자 높이는 9.
	 */
	public static int[] box(Font font, Component title, Component subtitle, int screenWidth, int screenHeight) {
		int centerX = screenWidth / 2;
		int centerY = screenHeight / 2;
		int titleWidth = title == null ? 0 : font.width(title);
		int left = centerX + TITLE_SCALE * (-titleWidth / 2);
		int right = left + TITLE_SCALE * titleWidth;
		int top = centerY + TITLE_SCALE * -10;
		int bottom = top + TITLE_SCALE * 9;
		int subtitleWidth = subtitle == null ? 0 : font.width(subtitle);

		if (subtitleWidth > 0) {
			int subtitleLeft = centerX + SUBTITLE_SCALE * (-subtitleWidth / 2);
			left = Math.min(left, subtitleLeft);
			right = Math.max(right, subtitleLeft + SUBTITLE_SCALE * subtitleWidth);
			bottom = centerY + SUBTITLE_SCALE * (5 + 9);
		}

		return new int[] {left, top, right - left, bottom - top};
	}

	private static Component sampleTitle() {
		return Component.translatable("cafeteria.hud.sample.title");
	}

	private static Component sampleSubtitle() {
		return Component.translatable("cafeteria.hud.sample.subtitle");
	}

	@Override
	protected int[] sampleBox(Minecraft minecraft, int screenWidth, int screenHeight) {
		return box(minecraft.font, sampleTitle(), sampleSubtitle(), screenWidth, screenHeight);
	}

	@Override
	protected void drawSample(GuiGraphics graphics, Minecraft minecraft) {
		Font font = minecraft.font;
		Component title = sampleTitle();
		Component subtitle = sampleSubtitle();
		graphics.pose().pushPose();
		graphics.pose().translate(graphics.guiWidth() / 2, graphics.guiHeight() / 2, 0.0F);
		graphics.pose().pushPose();
		graphics.pose().scale(TITLE_SCALE, TITLE_SCALE, 1.0F);
		graphics.drawString(font, title, -font.width(title) / 2, -10, 0xFFFFFFFF);
		graphics.pose().popPose();
		graphics.pose().pushPose();
		graphics.pose().scale(SUBTITLE_SCALE, SUBTITLE_SCALE, 1.0F);
		graphics.drawString(font, subtitle, -font.width(subtitle) / 2, 5, 0xFFFFFFFF);
		graphics.pose().popPose();
		graphics.pose().popPose();
	}
}
