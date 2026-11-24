package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.util.Collection;

/** 화면 위쪽의 보스바. 여러 개가 떠 있으면 전체를 한 덩어리로 옮기고 크기를 바꾼다. */
public class BossbarHud extends VanillaHudElement {
	/** 바닐라(BossHealthOverlay.render)의 배치: 첫 막대의 y, 막대 크기, 막대 사이 간격, 이름 줄 높이. */
	private static final int FIRST_BAR_Y = 12;
	private static final int BAR_WIDTH = 182;
	private static final int BAR_HEIGHT = 5;
	private static final int SPACING = 19;
	private static final int NAME_HEIGHT = 9;

	public BossbarHud() {
		super("bossbar");
	}

	/** 믹스인이 바닐라가 보스바를 그리기 직전에 부른다. 돌려주는 값의 뜻은 {@link VanillaHudElement#begin} 과 같다. */
	public boolean begin(GuiGraphics graphics, Collection<? extends BossEvent> events) {
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		return begin(graphics, () -> box(Minecraft.getInstance().font, events, screenWidth, screenHeight));
	}

	/**
	 * 바닐라가 보스바들을 그리는 상자 {x, y, 너비, 높이}.
	 * 막대는 화면 가운데에 너비 182로, 이름은 그 위 9픽셀에 가운데 정렬로 그린다. 막대를 하나 그릴 때마다 19씩 내려가고,
	 * 내려간 자리가 화면 높이의 1/3 이상이면 그만 그린다.
	 */
	public static int[] box(Font font, Collection<? extends BossEvent> events, int screenWidth, int screenHeight) {
		int count = 0;
		int nameWidth = 0;
		int y = FIRST_BAR_Y;

		for (BossEvent event : events) {
			nameWidth = Math.max(nameWidth, font.width(event.getName()));
			count++;
			y += SPACING;

			if (y >= screenHeight / 3) {
				break;
			}
		}

		return layout(nameWidth, count, screenWidth);
	}

	private static int[] layout(int nameWidth, int count, int screenWidth) {
		int center = screenWidth / 2;
		int left = Math.min(center - BAR_WIDTH / 2, center - nameWidth / 2);
		int right = Math.max(center - BAR_WIDTH / 2 + BAR_WIDTH, center - nameWidth / 2 + nameWidth);
		return new int[] {left, FIRST_BAR_Y - NAME_HEIGHT, right - left, SPACING * (count - 1) + NAME_HEIGHT + BAR_HEIGHT};
	}

	private static Component sampleName() {
		return Component.translatable("cafeteria.hud.sample.bossbar");
	}

	@Override
	protected int[] sampleBox(Minecraft minecraft, int screenWidth, int screenHeight) {
		return layout(minecraft.font.width(sampleName()), 1, screenWidth);
	}

	@Override
	protected void drawSample(GuiGraphics graphics, Minecraft minecraft) {
		Component name = sampleName();
		int center = graphics.guiWidth() / 2;
		int barX = center - BAR_WIDTH / 2;
		graphics.fill(barX, FIRST_BAR_Y, barX + BAR_WIDTH, FIRST_BAR_Y + BAR_HEIGHT, 0xFF4A0A3C);
		graphics.fill(barX, FIRST_BAR_Y, barX + BAR_WIDTH * 3 / 5, FIRST_BAR_Y + BAR_HEIGHT, 0xFFEC00B8);
		graphics.drawString(minecraft.font, name, center - minecraft.font.width(name) / 2, FIRST_BAR_Y - NAME_HEIGHT, 0xFFFFFFFF);
	}
}
