package io.github.lhj080902.cafeteria.gui;

import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.hud.HudElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** 켜져 있는 HUD 요소를 마우스로 끌어서 위치를 바꾸는 화면. */
public class HudEditScreen extends Screen {
	private final Screen parent;
	private HudElement dragging;
	private double grabOffsetX;
	private double grabOffsetY;

	public HudEditScreen(Screen parent) {
		super(Component.translatable("cafeteria.hud_edit.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("cafeteria.hud_edit.reset"), button -> Modules.HUD.forEach(HudElement::resetPosition))
			.bounds(width / 2 - 104, y, 100, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(width / 2 + 4, y, 100, 20).build());
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 게임 화면 위에 놓일 위치를 그대로 볼 수 있도록 배경을 흐리거나 어둡게 하지 않는다.
		if (minecraft.level == null) {
			super.renderBackground(graphics, mouseX, mouseY, partialTick);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		Component hint = Component.translatable("cafeteria.hud_edit.hint");
		int hintWidth = font.width(hint);
		graphics.fill(width / 2 - hintWidth / 2 - 6, 6, width / 2 + hintWidth / 2 + 6, 22, 0xA0000000);
		graphics.drawCenteredString(font, hint, width / 2, 10, 0xFFFFFFFF);

		for (HudElement element : Modules.HUD) {
			if (!element.isEnabled()) {
				continue;
			}

			element.render(graphics, minecraft);
			boolean highlighted = element == dragging || (dragging == null && contains(element, mouseX, mouseY));
			graphics.renderOutline(element.x(minecraft, width) - 1, element.y(minecraft, height) - 1, element.width(minecraft) + 2, element.height(minecraft) + 2,
				highlighted ? CafeteriaScreen.ACCENT : 0x80FFFFFF);
		}
	}

	private boolean contains(HudElement element, double mouseX, double mouseY) {
		int x = element.x(minecraft, width);
		int y = element.y(minecraft, height);
		return mouseX >= x && mouseX < x + element.width(minecraft) && mouseY >= y && mouseY < y + element.height(minecraft);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		if (button != 0) {
			return false;
		}

		// 나중에 그려진(위에 보이는) 요소부터 잡는다.
		for (int i = Modules.HUD.size() - 1; i >= 0; i--) {
			HudElement element = Modules.HUD.get(i);

			if (element.isEnabled() && contains(element, mouseX, mouseY)) {
				dragging = element;
				grabOffsetX = mouseX - element.x(minecraft, width);
				grabOffsetY = mouseY - element.y(minecraft, height);
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (dragging != null && button == 0) {
			dragging.setPosition(minecraft, mouseX - grabOffsetX, mouseY - grabOffsetY, width, height);
			return true;
		}

		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging != null && button == 0) {
			dragging = null;
			return true;
		}

		return super.mouseReleased(mouseX, mouseY, button);
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
