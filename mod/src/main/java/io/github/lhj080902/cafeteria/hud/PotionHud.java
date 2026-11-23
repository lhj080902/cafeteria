package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 걸려 있는 포션 효과와 남은 시간. */
public class PotionHud extends HudElement {
	private static final int PADDING = 3;
	private static final int LINE = 10;
	private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	public final BooleanSetting background = add(new BooleanSetting("background", true));
	public final BooleanSetting shadow = add(new BooleanSetting("text_shadow", true));
	public final ColorSetting textColor = add(new ColorSetting("text_color", 0xFFFFFFFF));

	public PotionHud(double anchorX, double anchorY) {
		super("potions", true, anchorX, anchorY);
	}

	private List<String> lines(Minecraft minecraft) {
		List<String> lines = new ArrayList<>();

		if (minecraft.player != null) {
			List<MobEffectInstance> effects = new ArrayList<>(minecraft.player.getActiveEffects());
			// 곧 끝나는 효과가 위로 오게 한다. 무한 지속 효과는 맨 아래.
			effects.sort(Comparator.comparingInt(effect -> effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration()));

			for (MobEffectInstance effect : effects) {
				lines.add(describe(effect));
			}
		}

		if (lines.isEmpty() && isEditing()) {
			lines.add(I18n.get("effect.minecraft.speed") + " II  1:30");
			lines.add(I18n.get("effect.minecraft.strength") + "  0:45");
		}

		return lines;
	}

	private static String describe(MobEffectInstance effect) {
		String name = effect.getEffect().value().getDisplayName().getString();
		int level = effect.getAmplifier() + 1;

		if (level > 1) {
			name += " " + (level < ROMAN.length ? ROMAN[level] : String.valueOf(level));
		}

		if (effect.isInfiniteDuration()) {
			return name + "  ∞";
		}

		int seconds = effect.getDuration() / 20;
		return name + "  " + seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return !lines(minecraft).isEmpty();
	}

	@Override
	protected int contentWidth(Minecraft minecraft) {
		int widest = 0;

		for (String line : lines(minecraft)) {
			widest = Math.max(widest, minecraft.font.width(line));
		}

		return widest + PADDING * 2;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		return Math.max(1, lines(minecraft).size()) * LINE + PADDING * 2 - 1;
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		List<String> lines = lines(minecraft);

		if (lines.isEmpty()) {
			return;
		}

		if (background.get()) {
			graphics.fill(0, 0, contentWidth(minecraft), contentHeight(minecraft), 0x80000000);
		}

		int y = PADDING;

		for (String line : lines) {
			graphics.drawString(minecraft.font, line, PADDING, y, textColor.get() | 0xFF000000, shadow.get());
			y += LINE;
		}
	}
}
