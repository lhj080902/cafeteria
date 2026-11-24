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
	/** 바닐라(GuiGraphics.drawStringWithBackdrop)가 글자 둘레로 넓혀 까는 배경의 폭 (배율을 걸기 전 기준). */
	private static final int BACKDROP_MARGIN = 2;

	public TitleHud() {
		super("title");
	}

	/** 믹스인이 바닐라가 타이틀을 그리기 직전에 부른다. 돌려주는 값의 뜻은 {@link VanillaHudElement#begin} 과 같다. */
	public boolean begin(GuiGraphics graphics, Component title, Component subtitle) {
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		return begin(graphics, () -> {
			Minecraft minecraft = Minecraft.getInstance();
			return box(minecraft.font, title, subtitle, screenWidth, screenHeight, hasBackdrop(minecraft));
		});
	}

	/** 바닐라가 타이틀 뒤에 어두운 배경을 까는지: 접근성 설정의 "글자 배경"이 "모든 곳"이고 투명하지 않을 때다. */
	private static boolean hasBackdrop(Minecraft minecraft) {
		return minecraft.options.getBackgroundColor(0.0F) != 0;
	}

	/**
	 * 바닐라(Gui.renderTitle)가 타이틀과 부제목을 그리는 상자 {x, y, 너비, 높이}.
	 * 타이틀은 화면 가운데에서 4배로 (-너비/2, -10) 에, 부제목은 2배로 (-너비/2, 5) 에 그린다. 글자 높이는 9.
	 * backdrop 이 true 면 글자 뒤에 까는 배경(글자 둘레로 2씩 넓다)까지 넣는다. 이때는 부제목이 빈 글자여도 배경은 그려진다.
	 */
	public static int[] box(Font font, Component title, Component subtitle, int screenWidth, int screenHeight, boolean backdrop) {
		int centerX = screenWidth / 2;
		int centerY = screenHeight / 2;
		int margin = backdrop ? BACKDROP_MARGIN : 0;
		int titleWidth = title == null ? 0 : font.width(title);
		int left = centerX + TITLE_SCALE * (-titleWidth / 2 - margin);
		int right = centerX + TITLE_SCALE * (-titleWidth / 2 + titleWidth + margin);
		int top = centerY + TITLE_SCALE * (-10 - margin);
		int bottom = centerY + TITLE_SCALE * (-10 + 9 + margin);
		int subtitleWidth = subtitle == null ? 0 : font.width(subtitle);

		if (subtitleWidth > 0 || subtitle != null && backdrop) {
			left = Math.min(left, centerX + SUBTITLE_SCALE * (-subtitleWidth / 2 - margin));
			right = Math.max(right, centerX + SUBTITLE_SCALE * (-subtitleWidth / 2 + subtitleWidth + margin));
			bottom = Math.max(bottom, centerY + SUBTITLE_SCALE * (5 + 9 + margin));
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
		return box(minecraft.font, sampleTitle(), sampleSubtitle(), screenWidth, screenHeight, hasBackdrop(minecraft));
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
		graphics.drawStringWithBackdrop(font, title, -font.width(title) / 2, -10, font.width(title), 0xFFFFFFFF);
		graphics.pose().popPose();
		graphics.pose().pushPose();
		graphics.pose().scale(SUBTITLE_SCALE, SUBTITLE_SCALE, 1.0F);
		graphics.drawStringWithBackdrop(font, subtitle, -font.width(subtitle) / 2, 5, font.width(subtitle), 0xFFFFFFFF);
		graphics.pose().popPose();
		graphics.pose().popPose();
	}
}
