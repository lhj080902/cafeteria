package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** 입고 있는 갑옷과 손에 든 아이템의 남은 내구도. */
public class ArmorHud extends HudElement {
	private static final int ICON = 16;
	private static final int ROW = 17;
	private static final int TEXT_X = ICON + 3;
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	public final BooleanSetting mainHand = add(new BooleanSetting("armor_hand", true));
	public final ModeSetting display = add(new ModeSetting("armor_display", "value", "value", "percent"));
	public final BooleanSetting background = add(new BooleanSetting("background", false));
	public final ColorSetting textColor = add(new ColorSetting("text_color", 0xFFFFFFFF));

	public ArmorHud(double anchorX, double anchorY) {
		super("armor", true, anchorX, anchorY);
	}

	private List<ItemStack> items(Minecraft minecraft) {
		List<ItemStack> items = new ArrayList<>();

		if (minecraft.player != null) {
			for (EquipmentSlot slot : ARMOR_SLOTS) {
				ItemStack stack = minecraft.player.getItemBySlot(slot);

				if (!stack.isEmpty()) {
					items.add(stack);
				}
			}

			if (mainHand.get() && !minecraft.player.getMainHandItem().isEmpty()) {
				items.add(minecraft.player.getMainHandItem());
			}
		}

		// 편집 화면에서는 아무것도 입지 않았어도 위치를 잡을 수 있게 예시를 보여 준다.
		if (items.isEmpty() && isEditing()) {
			items.add(new ItemStack(Items.IRON_HELMET));
			items.add(new ItemStack(Items.IRON_CHESTPLATE));
			items.add(new ItemStack(Items.IRON_LEGGINGS));
			items.add(new ItemStack(Items.IRON_BOOTS));
		}

		return items;
	}

	/** 내구도가 있는 아이템은 남은 내구도, 여러 개 겹친 아이템은 개수, 그 밖에는 빈 문자열. */
	private String label(ItemStack stack) {
		if (stack.isDamageableItem()) {
			int remaining = stack.getMaxDamage() - stack.getDamageValue();
			return display.is("percent") ? Math.round(remaining * 100.0F / stack.getMaxDamage()) + "%" : String.valueOf(remaining);
		}

		return stack.getCount() > 1 ? "x" + stack.getCount() : "";
	}

	/** 내구도가 절반 이하면 노랑, 20% 이하면 빨강으로 경고한다. */
	private int color(ItemStack stack) {
		if (stack.isDamageableItem()) {
			float ratio = (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();

			if (ratio <= 0.2F) {
				return 0xFFFF5555;
			}

			if (ratio <= 0.5F) {
				return 0xFFFFFF55;
			}
		}

		return textColor.get() | 0xFF000000;
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return !items(minecraft).isEmpty();
	}

	@Override
	protected int contentWidth(Minecraft minecraft) {
		int widest = minecraft.font.width("1000");

		for (ItemStack stack : items(minecraft)) {
			widest = Math.max(widest, minecraft.font.width(label(stack)));
		}

		return TEXT_X + widest + 2;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		return Math.max(1, items(minecraft).size()) * ROW - 1;
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		List<ItemStack> items = items(minecraft);

		if (background.get() && !items.isEmpty()) {
			graphics.fill(-2, -2, contentWidth(minecraft), contentHeight(minecraft) + 2, 0x80000000);
		}

		int y = 0;

		for (ItemStack stack : items) {
			graphics.renderItem(stack, 0, y);
			graphics.drawString(minecraft.font, label(stack), TEXT_X, y + 4, color(stack));
			y += ROW;
		}
	}
}
