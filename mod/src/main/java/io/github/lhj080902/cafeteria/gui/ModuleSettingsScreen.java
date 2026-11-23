package io.github.lhj080902.cafeteria.gui;

import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.module.Module;
import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import io.github.lhj080902.cafeteria.module.setting.NumberSetting;
import io.github.lhj080902.cafeteria.module.setting.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 모듈 하나의 세부 설정 화면. 설정 종류에 맞는 바닐라 위젯(버튼·슬라이더)을 두 줄로 배치한다. */
public class ModuleSettingsScreen extends Screen {
	private static final int WIDGET_WIDTH = 150;
	private static final int GAP = 8;
	private static final int ROW = 24;
	private static final int TOP = 52;

	private final Screen parent;
	private final Module module;

	public ModuleSettingsScreen(Screen parent, Module module) {
		super(module.name());
		this.parent = parent;
		this.module = module;
	}

	@Override
	protected void init() {
		List<Setting<?>> settings = module.settings();
		int columns = settings.size() > 1 ? 2 : 1;
		int left = (width - (columns * WIDGET_WIDTH + (columns - 1) * GAP)) / 2;

		for (int i = 0; i < settings.size(); i++) {
			addRenderableWidget(createWidget(settings.get(i), left + i % columns * (WIDGET_WIDTH + GAP), TOP + i / columns * ROW));
		}

		int rows = (settings.size() + columns - 1) / columns;
		int buttonY = Math.min(height - 26, TOP + rows * ROW + 12);
		addRenderableWidget(Button.builder(Component.translatable("cafeteria.screen.reset"), button -> {
			module.resetSettings();
			rebuildWidgets();
		}).bounds(width / 2 - 100 - GAP / 2, buttonY, 100, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(width / 2 + GAP / 2, buttonY, 100, 20).build());
	}

	private AbstractWidget createWidget(Setting<?> setting, int x, int y) {
		if (setting instanceof BooleanSetting toggle) {
			return CycleButton.onOffBuilder(toggle.get()).create(x, y, WIDGET_WIDTH, 20, setting.label(), (button, value) -> toggle.set(value));
		}

		if (setting instanceof ModeSetting mode) {
			return CycleButton.<String>builder(value -> Component.translatable("cafeteria.mode." + value))
				.withValues(mode.options())
				.withInitialValue(mode.get())
				.create(x, y, WIDGET_WIDTH, 20, setting.label(), (button, value) -> mode.set(value));
		}

		if (setting instanceof NumberSetting number) {
			return new NumberSlider(x, y, number);
		}

		if (setting instanceof ColorSetting color) {
			return new ColorButton(x, y, color);
		}

		throw new IllegalStateException("위젯이 없는 설정 종류: " + setting.getClass().getName());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
		graphics.drawCenteredString(font, module.description(), width / 2, 32, 0xFFA0A0A0);
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

	private static class NumberSlider extends AbstractSliderButton {
		private final NumberSetting setting;

		NumberSlider(int x, int y, NumberSetting setting) {
			super(x, y, WIDGET_WIDTH, 20, Component.empty(), (setting.get() - setting.min()) / (setting.max() - setting.min()));
			this.setting = setting;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.empty().append(setting.label()).append(": " + setting.format()));
		}

		@Override
		protected void applyValue() {
			setting.set(setting.min() + value * (setting.max() - setting.min()));
		}
	}

	/** 누를 때마다 색상표의 다음 색으로 넘어가는 버튼. 오른쪽에 현재 색을 네모로 보여 준다. */
	private static class ColorButton extends AbstractButton {
		private final ColorSetting setting;

		ColorButton(int x, int y, ColorSetting setting) {
			super(x, y, WIDGET_WIDTH, 20, setting.label());
			this.setting = setting;
		}

		@Override
		public void onPress() {
			setting.cycle();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			super.renderWidget(graphics, mouseX, mouseY, partialTick);
			int x = getX() + getWidth() - 16;
			int y = getY() + 5;
			graphics.fill(x - 1, y - 1, x + 11, y + 11, 0xFF000000);
			graphics.fill(x, y, x + 10, y + 10, setting.get() | 0xFF000000);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
