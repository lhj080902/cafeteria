package io.github.lhj080902.cafeteria.gui;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.module.Module;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** 모듈 목록 화면 (기본 키: 오른쪽 Shift). 스위치를 누르면 켜고 끄고, 카드를 누르면 세부 설정으로 들어간다. */
public class CafeteriaScreen extends Screen {
	public static final int ACCENT = 0xFFF2A65A;

	private static final int MAX_PANEL_WIDTH = 380;
	private static final int HEADER = 30;
	private static final int FOOTER = 30;
	private static final int PADDING = 8;
	private static final int CARD_HEIGHT = 35;
	private static final int GAP = 4;
	private static final int COLUMNS = 2;
	private static final int SWITCH_WIDTH = 24;
	private static final int SWITCH_HEIGHT = 12;

	private static final int SECTION_TITLE = 13;
	private static final int SECTION_GAP = 8;

	/** 카드 하나의 배치. top 은 목록 내용의 맨 위를 0으로 한 세로 위치. */
	private record Card(Module module, int column, int top) {
	}

	private record SectionTitle(Component title, int top) {
	}

	private final Screen parent;
	private final List<Card> cards = new ArrayList<>();
	private final List<SectionTitle> sectionTitles = new ArrayList<>();
	private int contentHeight;
	private int panelX;
	private int panelY;
	private int panelWidth;
	private int panelHeight;
	private int cardWidth;
	private double scroll;

	public CafeteriaScreen(Screen parent) {
		super(Component.translatable("cafeteria.screen.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout();
		panelWidth = Math.min(MAX_PANEL_WIDTH, width - 8);
		panelHeight = Math.min(height - 12, HEADER + PADDING * 2 + contentHeight + FOOTER);
		panelX = (width - panelWidth) / 2;
		panelY = (height - panelHeight) / 2;
		cardWidth = (panelWidth - PADDING * 2 - GAP * (COLUMNS - 1)) / COLUMNS;
		int buttonY = panelY + panelHeight - FOOTER + 5;
		addRenderableWidget(Button.builder(Component.translatable("cafeteria.screen.edit_hud"), button -> minecraft.setScreen(new HudEditScreen(this)))
			.bounds(panelX + PADDING, buttonY, 120, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(panelX + panelWidth - PADDING - 80, buttonY, 80, 20).build());
		scroll = Mth.clamp(scroll, 0.0, maxScroll());
	}

	/** 묶음 제목과 카드의 세로 위치를 계산한다. 카드 순서는 Modules.ALL 과 같다. */
	private void layout() {
		cards.clear();
		sectionTitles.clear();
		int bottom = addSection(Component.translatable("cafeteria.screen.section.features"), Modules.FEATURES, 0);
		contentHeight = addSection(Component.translatable("cafeteria.screen.section.hud"), Modules.HUD, bottom + SECTION_GAP);
	}

	private int addSection(Component title, List<? extends Module> modules, int top) {
		sectionTitles.add(new SectionTitle(title, top));
		int cardsTop = top + SECTION_TITLE;

		for (int i = 0; i < modules.size(); i++) {
			cards.add(new Card(modules.get(i), i % COLUMNS, cardsTop + i / COLUMNS * (CARD_HEIGHT + GAP)));
		}

		int rows = (modules.size() + COLUMNS - 1) / COLUMNS;
		return cardsTop + rows * (CARD_HEIGHT + GAP) - GAP;
	}

	private int listTop() {
		return panelY + HEADER;
	}

	private int listBottom() {
		return panelY + panelHeight - FOOTER;
	}

	private double maxScroll() {
		return Math.max(0, contentHeight + PADDING * 2 - (listBottom() - listTop()));
	}

	/** 목록 내용 기준 세로 위치를 화면 좌표로 바꾼다. */
	private int contentY(int top) {
		return listTop() + PADDING + top - (int) scroll;
	}

	public int cardX(int index) {
		return panelX + PADDING + cards.get(index).column() * (cardWidth + GAP);
	}

	public int cardY(int index) {
		return contentY(cards.get(index).top());
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE6141414);
		graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, ACCENT);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawString(font, title, panelX + PADDING, panelY + 12, 0xFFFFFFFF);
		Component hint = Component.translatable("cafeteria.screen.hint");
		graphics.drawString(font, hint, panelX + panelWidth - PADDING - font.width(hint), panelY + 12, 0xFF8A8A8A, false);
		graphics.enableScissor(panelX, listTop(), panelX + panelWidth, listBottom());
		boolean mouseInList = mouseY >= listTop() && mouseY < listBottom();

		for (SectionTitle section : sectionTitles) {
			graphics.drawString(font, section.title(), panelX + PADDING + 2, contentY(section.top()) + 2, ACCENT, false);
		}

		for (int i = 0; i < cards.size(); i++) {
			int x = cardX(i);
			int y = cardY(i);
			boolean hovered = mouseInList && mouseX >= x && mouseX < x + cardWidth && mouseY >= y && mouseY < y + CARD_HEIGHT;
			renderCard(graphics, cards.get(i).module(), x, y, hovered);
		}

		graphics.disableScissor();
		renderScrollBar(graphics);
	}

	/** 목록이 화면보다 길 때 오른쪽에 현재 위치를 보여 주는 막대. */
	private void renderScrollBar(GuiGraphics graphics) {
		double max = maxScroll();

		if (max <= 0) {
			return;
		}

		int trackHeight = listBottom() - listTop() - 4;
		int barHeight = Math.max(12, (int) (trackHeight * trackHeight / (trackHeight + max)));
		int barY = listTop() + 2 + (int) ((trackHeight - barHeight) * scroll / max);
		int x = panelX + panelWidth - 4;
		graphics.fill(x, listTop() + 2, x + 2, listTop() + 2 + trackHeight, 0x40FFFFFF);
		graphics.fill(x, barY, x + 2, barY + barHeight, ACCENT);
	}

	private void renderCard(GuiGraphics graphics, Module module, int x, int y, boolean hovered) {
		graphics.fill(x, y, x + cardWidth, y + CARD_HEIGHT, hovered ? 0xFF3A3A3A : 0xFF2A2A2A);

		if (module.isEnabled()) {
			graphics.fill(x, y, x + 2, y + CARD_HEIGHT, ACCENT);
		}

		graphics.drawString(font, module.name(), x + 8, y + 7, 0xFFFFFFFF);
		Component settingsHint = Component.translatable("cafeteria.screen.settings");
		int hintWidth = font.width(settingsHint);
		String description = ellipsize(module.description().getString(), cardWidth - 16 - hintWidth - 6);
		graphics.drawString(font, description, x + 8, y + 22, 0xFF9A9A9A, false);
		graphics.drawString(font, settingsHint, x + cardWidth - 8 - hintWidth, y + 22, hovered ? ACCENT : 0xFF6E6E6E, false);
		int switchX = switchX(x);
		int switchY = y + 6;
		graphics.fill(switchX, switchY, switchX + SWITCH_WIDTH, switchY + SWITCH_HEIGHT, module.isEnabled() ? ACCENT : 0xFF555555);
		int knobX = module.isEnabled() ? switchX + SWITCH_WIDTH - 11 : switchX + 1;
		graphics.fill(knobX, switchY + 1, knobX + 10, switchY + SWITCH_HEIGHT - 1, 0xFFFFFFFF);
	}

	/** 폭을 넘는 글자는 잘라 내고 끝에 말줄임표를 붙인다. */
	private String ellipsize(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}

		return font.plainSubstrByWidth(text, maxWidth - font.width("…")) + "…";
	}

	private int switchX(int cardX) {
		return cardX + cardWidth - 8 - SWITCH_WIDTH;
	}

	/** index 번째 카드에 있는 켜기/끄기 스위치의 가운데 X 좌표. */
	public int switchCenterX(int index) {
		return switchX(cardX(index)) + SWITCH_WIDTH / 2;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		if ((button != 0 && button != 1) || mouseY < listTop() || mouseY >= listBottom()) {
			return false;
		}

		for (int i = 0; i < cards.size(); i++) {
			int x = cardX(i);
			int y = cardY(i);

			if (mouseX < x || mouseX >= x + cardWidth || mouseY < y || mouseY >= y + CARD_HEIGHT) {
				continue;
			}

			Module module = cards.get(i).module();
			// 스위치 주변(카드 오른쪽 위)을 왼쪽 클릭하면 켜고 끄기, 그 밖의 곳이나 오른쪽 클릭은 세부 설정.
			boolean onSwitch = mouseX >= switchX(x) - 4 && mouseY < y + 6 + SWITCH_HEIGHT + 4;
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));

			if (button == 0 && (onSwitch || module.settings().isEmpty())) {
				module.toggle();
				ConfigManager.save();
			} else if (!module.settings().isEmpty()) {
				minecraft.setScreen(new ModuleSettingsScreen(this, module));
			}

			return true;
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll = Mth.clamp(scroll - scrollY * 16.0, 0.0, maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// 메뉴를 연 키를 다시 누르면 닫는다.
		if (CafeteriaClient.menuKey != null && CafeteriaClient.menuKey.matches(keyCode, scanCode)) {
			onClose();
			return true;
		}

		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
