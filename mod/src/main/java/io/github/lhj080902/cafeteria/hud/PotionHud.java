package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 걸려 있는 포션 효과. 효과 아이콘 옆에 이름(단계)과 남은 시간을 보여 준다.
 * 아이콘 표시를 끄면 한 줄에 "이름  남은 시간"만 적는다.
 */
public class PotionHud extends HudElement {
	private static final int PADDING = 3;
	/** 아이콘 없이 글자만 보여 줄 때의 한 줄 높이. */
	private static final int TEXT_ROW = 10;
	/** 바닐라 효과 아이콘의 크기. */
	private static final int ICON = 18;
	private static final int ICON_ROW = ICON + 2;
	private static final int ICON_GAP = 4;
	/** 남은 시간이 이보다 적으면(10초) 시간을 빨갛게 보여 준다. */
	private static final int ENDING_SOON_TICKS = 200;
	private static final int ENDING_SOON_COLOR = 0xFFFF5555;
	private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	public final BooleanSetting icons = add(new BooleanSetting("potion_icons", true));
	public final BooleanSetting hideVanilla = add(new BooleanSetting("potion_hide_vanilla", false));
	public final BooleanSetting background = add(new BooleanSetting("background", true));
	public final BooleanSetting shadow = add(new BooleanSetting("text_shadow", true));
	public final ColorSetting textColor = add(new ColorSetting("text_color", 0xFFFFFFFF));

	public PotionHud(double anchorX, double anchorY) {
		super("potions", true, anchorX, anchorY);
	}

	/** 화면 오른쪽 위에 뜨는 바닐라 효과 아이콘을 숨길지. 이 HUD 가 켜져 있을 때만 숨긴다. */
	public boolean hidesVanillaIcons() {
		return isEnabled() && hideVanilla.get();
	}

	/** 보여 줄 효과들. 곧 끝나는 효과가 위로 오고, 무한 지속 효과는 맨 아래에 온다. */
	private List<MobEffectInstance> effects(Minecraft minecraft) {
		List<MobEffectInstance> effects = new ArrayList<>();

		if (minecraft.player != null) {
			effects.addAll(minecraft.player.getActiveEffects());
			effects.sort(Comparator.comparingInt(effect -> effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration()));
		}

		// 위치 편집 화면에서는 효과가 없어도 예시를 보여 줘서 자리를 잡을 수 있게 한다.
		if (effects.isEmpty() && isEditing()) {
			effects.add(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 45 * 20, 0));
			effects.add(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 90 * 20, 1));
		}

		return effects;
	}

	private static String name(MobEffectInstance effect) {
		String name = effect.getEffect().value().getDisplayName().getString();
		int level = effect.getAmplifier() + 1;

		if (level > 1) {
			name += " " + (level < ROMAN.length ? ROMAN[level] : String.valueOf(level));
		}

		return name;
	}

	private static String time(MobEffectInstance effect) {
		if (effect.isInfiniteDuration()) {
			return "∞";
		}

		int seconds = effect.getDuration() / 20;
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	private int rowHeight() {
		return icons.get() ? ICON_ROW : TEXT_ROW;
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return !effects(minecraft).isEmpty();
	}

	@Override
	protected int contentWidth(Minecraft minecraft) {
		int widest = 0;

		for (MobEffectInstance effect : effects(minecraft)) {
			int width = icons.get()
				? ICON + ICON_GAP + Math.max(minecraft.font.width(name(effect)), minecraft.font.width(time(effect)))
				: minecraft.font.width(name(effect) + "  " + time(effect));
			widest = Math.max(widest, width);
		}

		return widest + PADDING * 2;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		// 마지막 줄 아래의 줄 간격(아이콘 줄은 2, 글자 줄은 1)은 빼서 위아래 여백을 맞춘다.
		return Math.max(1, effects(minecraft).size()) * rowHeight() + PADDING * 2 - (icons.get() ? ICON_ROW - ICON : 1);
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		List<MobEffectInstance> effects = effects(minecraft);

		if (effects.isEmpty()) {
			return;
		}

		if (background.get()) {
			graphics.fill(0, 0, contentWidth(minecraft), contentHeight(minecraft), 0x80000000);
		}

		int color = textColor.get() | 0xFF000000;
		int y = PADDING;

		for (MobEffectInstance effect : effects) {
			boolean endingSoon = !effect.isInfiniteDuration() && effect.getDuration() <= ENDING_SOON_TICKS;

			if (icons.get()) {
				graphics.blitSprite(RenderType::guiTextured, minecraft.getMobEffectTextures().get(effect.getEffect()), PADDING, y, ICON, ICON);
				int textX = PADDING + ICON + ICON_GAP;
				graphics.drawString(minecraft.font, name(effect), textX, y + 1, color, shadow.get());
				graphics.drawString(minecraft.font, time(effect), textX, y + 10, endingSoon ? ENDING_SOON_COLOR : 0xFFC0C0C0, shadow.get());
			} else {
				String name = name(effect) + "  ";
				graphics.drawString(minecraft.font, name, PADDING, y, color, shadow.get());
				graphics.drawString(minecraft.font, time(effect), PADDING + minecraft.font.width(name), y, endingSoon ? ENDING_SOON_COLOR : color, shadow.get());
			}

			y += rowHeight();
		}
	}
}
