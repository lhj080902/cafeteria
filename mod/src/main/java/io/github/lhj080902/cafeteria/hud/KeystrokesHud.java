package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** 이동 키(WASD)와 마우스 버튼, 스페이스바가 눌렸는지 보여 주는 키스트로크 HUD. */
public class KeystrokesHud extends HudElement {
	private static final int KEY = 20;
	private static final int GAP = 2;
	private static final int WIDTH = KEY * 3 + GAP * 2;
	private static final int MOUSE_WIDTH = (WIDTH - GAP) / 2;
	private static final int SPACE_HEIGHT = 12;

	public final BooleanSetting mouseButtons = add(new BooleanSetting("keys_mouse", true));
	public final BooleanSetting spaceBar = add(new BooleanSetting("keys_space", true));
	public final ColorSetting textColor = add(new ColorSetting("text_color", 0xFFFFFFFF));
	public final ColorSetting pressedColor = add(new ColorSetting("keys_pressed_color", 0xFFFFFFFF));

	public KeystrokesHud() {
		super("keystrokes", true, 0.005, 0.45);
	}

	@Override
	protected int contentWidth(Minecraft minecraft) {
		return WIDTH;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		int height = KEY * 2 + GAP;

		if (mouseButtons.get()) {
			height += GAP + KEY;
		}

		if (spaceBar.get()) {
			height += GAP + SPACE_HEIGHT;
		}

		return height;
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		Options options = minecraft.options;
		Font font = minecraft.font;
		int row = KEY + GAP;
		key(graphics, font, KEY + GAP, 0, KEY, KEY, label(options.keyUp), options.keyUp.isDown());
		key(graphics, font, 0, row, KEY, KEY, label(options.keyLeft), options.keyLeft.isDown());
		key(graphics, font, KEY + GAP, row, KEY, KEY, label(options.keyDown), options.keyDown.isDown());
		key(graphics, font, (KEY + GAP) * 2, row, KEY, KEY, label(options.keyRight), options.keyRight.isDown());
		int y = row * 2;

		if (mouseButtons.get()) {
			key(graphics, font, 0, y, MOUSE_WIDTH, KEY, "LMB", options.keyAttack.isDown());
			key(graphics, font, MOUSE_WIDTH + GAP, y, MOUSE_WIDTH, KEY, "RMB", options.keyUse.isDown());
			y += row;
		}

		if (spaceBar.get()) {
			boolean down = options.keyJump.isDown();
			graphics.fill(0, y, WIDTH, y + SPACE_HEIGHT, background(down));
			graphics.fill(WIDTH / 2 - 14, y + SPACE_HEIGHT / 2 - 1, WIDTH / 2 + 14, y + SPACE_HEIGHT / 2 + 1, foreground(down));
		}
	}

	private void key(GuiGraphics graphics, Font font, int x, int y, int width, int height, String label, boolean down) {
		graphics.fill(x, y, x + width, y + height, background(down));
		graphics.drawString(font, label, x + (width - font.width(label)) / 2 + 1, y + (height - font.lineHeight) / 2 + 1, foreground(down), false);
	}

	private int background(boolean down) {
		return down ? 0xC0000000 | pressedColor.get() & 0xFFFFFF : 0x80000000;
	}

	/** 눌린 키는 밝은 배경 위에 그려지므로, 배경 밝기에 따라 검정/글자색을 고른다. */
	private int foreground(boolean down) {
		if (!down) {
			return textColor.get() | 0xFF000000;
		}

		int color = pressedColor.get();
		int brightness = ((color >> 16 & 0xFF) * 299 + (color >> 8 & 0xFF) * 587 + (color & 0xFF) * 114) / 1000;
		return brightness > 140 ? 0xFF000000 : 0xFFFFFFFF;
	}

	/** 키 설정에서 바꾼 키 이름을 그대로 보여 준다. 너무 긴 이름은 앞 세 글자만 쓴다. */
	private static String label(KeyMapping key) {
		String name = key.getTranslatedKeyMessage().getString().toUpperCase(java.util.Locale.ROOT);
		return name.length() > 3 ? name.substring(0, 3) : name;
	}
}
