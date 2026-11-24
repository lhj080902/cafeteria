package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Comparator;
import java.util.List;

/** 화면 오른쪽의 스코어보드(사이드바). 제목과 점수 줄 전체를 한 덩어리로 옮기고 크기를 바꾼다. */
public class ScoreboardHud extends VanillaHudElement {
	/** 바닐라가 한 번에 보여 주는 줄 수와 줄 높이. */
	private static final int MAX_LINES = 15;
	private static final int LINE_HEIGHT = 9;
	/** 바닐라(Gui.SCORE_DISPLAY_ORDER)와 같은 순서: 점수가 높은 것부터, 같으면 이름순. */
	private static final Comparator<PlayerScoreEntry> ORDER = Comparator.comparing(PlayerScoreEntry::value).reversed()
		.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);
	private static final int SAMPLE_LINES = 3;

	public ScoreboardHud() {
		super("scoreboard");
	}

	/**
	 * 바닐라(Gui.displayScoreboardSidebar)가 사이드바를 그리는 상자 {x, y, 너비, 높이}.
	 * 숨기지 않은 점수 중 15개까지를 보여 주고, 너비는 제목과 "이름: 점수" 중 가장 긴 것을 따른다.
	 */
	public static int[] box(Font font, Objective objective, int screenWidth, int screenHeight) {
		Scoreboard scoreboard = objective.getScoreboard();
		NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		List<PlayerScoreEntry> entries = scoreboard.listPlayerScores(objective).stream()
			.filter(entry -> !entry.isHidden()).sorted(ORDER).limit(MAX_LINES).toList();
		int width = font.width(objective.getDisplayName());
		int separator = font.width(": ");

		for (PlayerScoreEntry entry : entries) {
			Component name = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName());
			int scoreWidth = font.width(entry.formatValue(format));
			width = Math.max(width, font.width(name) + (scoreWidth > 0 ? separator + scoreWidth : 0));
		}

		return layout(width, entries.size(), screenWidth, screenHeight);
	}

	/** 내용 너비와 줄 수로 상자를 구한다. 오른쪽 끝에서 1픽셀 떨어져 있고, 세로는 화면 가운데보다 조금 아래에 맞춰진다. */
	private static int[] layout(int width, int lines, int screenWidth, int screenHeight) {
		int bottom = screenHeight / 2 + lines * LINE_HEIGHT / 3;
		return new int[] {screenWidth - width - 5, bottom - lines * LINE_HEIGHT - LINE_HEIGHT - 1, width + 4, lines * LINE_HEIGHT + LINE_HEIGHT + 1};
	}

	private static Component sampleTitle() {
		return Component.translatable("cafeteria.hud.sample.scoreboard");
	}

	private static Component sampleName(int line) {
		return Component.translatable("cafeteria.hud.sample.scoreboard_line", line + 1);
	}

	private static String sampleScore(int line) {
		return String.valueOf(SAMPLE_LINES - line);
	}

	private static int sampleWidth(Font font) {
		int width = font.width(sampleTitle());

		for (int line = 0; line < SAMPLE_LINES; line++) {
			width = Math.max(width, font.width(sampleName(line)) + font.width(": ") + font.width(sampleScore(line)));
		}

		return width;
	}

	@Override
	protected int[] sampleBox(Minecraft minecraft, int screenWidth, int screenHeight) {
		return layout(sampleWidth(minecraft.font), SAMPLE_LINES, screenWidth, screenHeight);
	}

	@Override
	protected void drawSample(GuiGraphics graphics, Minecraft minecraft) {
		Font font = minecraft.font;
		int width = sampleWidth(font);
		int[] box = layout(width, SAMPLE_LINES, graphics.guiWidth(), graphics.guiHeight());
		int left = box[0];
		int right = box[0] + box[2];
		int top = box[1];
		int bottom = box[1] + box[3];
		int textLeft = left + 2;
		Component title = sampleTitle();
		graphics.fill(left, top, right, top + LINE_HEIGHT, minecraft.options.getBackgroundColor(0.4F));
		graphics.fill(left, top + LINE_HEIGHT, right, bottom, minecraft.options.getBackgroundColor(0.3F));
		graphics.drawString(font, title, textLeft + width / 2 - font.width(title) / 2, top + 1, 0xFFFFFFFF, false);

		for (int line = 0; line < SAMPLE_LINES; line++) {
			int y = bottom - (SAMPLE_LINES - line) * LINE_HEIGHT;
			String score = sampleScore(line);
			graphics.drawString(font, sampleName(line), textLeft, y, 0xFFFFFFFF, false);
			graphics.drawString(font, score, right - font.width(score), y, 0xFFFF5555, false);
		}
	}
}
