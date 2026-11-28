package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** 핫바를 뺀 인벤토리 27칸. */
public class InventoryHud extends HudElement {
	public static final int COLUMNS = 9;
	public static final int ROWS = 3;
	public static final int CELL = 18;
	/** 인벤토리에서 핫바(0~8번 칸) 다음의 첫 칸. 여기서부터 27칸을 보여 준다. */
	private static final int FIRST_SLOT = 9;

	public final BooleanSetting background = add(new BooleanSetting("background", true));

	public InventoryHud(double anchorX, double anchorY) {
		super("inventory", false, anchorX, anchorY);
	}

	/** index(0~26) 번째 칸의 아이템이 그려지는 왼쪽 위 좌표 (배율 적용 전). */
	public static int slotX(int index) {
		return index % COLUMNS * CELL + 1;
	}

	public static int slotY(int index) {
		return index / COLUMNS * CELL + 1;
	}

	/** 칸 순서대로의 아이템. 핫바, 갑옷, 왼손은 넣지 않는다. */
	private static List<ItemStack> items(Minecraft minecraft) {
		List<ItemStack> items = new ArrayList<>();

		if (minecraft.player != null) {
			for (int index = 0; index < COLUMNS * ROWS; index++) {
				items.add(minecraft.player.getInventory().getItem(FIRST_SLOT + index));
			}
		}

		// 편집 화면에서는 인벤토리가 비어 있어도 위치를 잡을 수 있게 예시를 앞 칸부터 보여 준다.
		if (isEditing() && items.stream().allMatch(ItemStack::isEmpty)) {
			return List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.GOLDEN_APPLE, 8), new ItemStack(Items.ENDER_PEARL, 16),
				new ItemStack(Items.ARROW, 64), new ItemStack(Items.SHIELD));
		}

		return items;
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		return items(minecraft).stream().anyMatch(stack -> !stack.isEmpty());
	}

	@Override
	protected int contentWidth(Minecraft minecraft) {
		return COLUMNS * CELL;
	}

	@Override
	protected int contentHeight(Minecraft minecraft) {
		return ROWS * CELL;
	}

	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		List<ItemStack> items = items(minecraft);

		for (int index = 0; index < COLUMNS * ROWS; index++) {
			int x = slotX(index);
			int y = slotY(index);

			if (background.get()) {
				graphics.fill(x, y, x + 16, y + 16, 0x80000000);
			}

			if (index < items.size()) {
				graphics.renderItem(items.get(index), x, y);
				graphics.renderItemDecorations(minecraft.font, items.get(index), x, y);
			}
		}
	}
}
