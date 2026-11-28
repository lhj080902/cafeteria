package io.github.lhj080902.cafeteria.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * 컨테이너 미리보기가 툴팁에 넣는 그림의 자료: 내용물을 9칸 너비 격자로 보여 준다.
 * slots 는 0번 칸부터 rows * 9 칸이고, 빈 칸은 ItemStack.EMPTY 다.
 */
public record ContainerPreviewTooltip(List<ItemStack> slots, int rows) implements TooltipComponent {
	/** 격자에 그릴 수 있는 칸 수 (큰 상자 크기). */
	private static final int MAX_SLOTS = 54;
	private static final int COLUMNS = 9;
	private static final int MIN_ROWS = 3;
	private static final int CELL = 18;

	/** 마지막으로 그린 격자의 왼쪽 위 GUI 좌표. 게임 테스트가 스크린샷에서 격자를 찾을 때 쓴다. */
	private static int lastX = -1;
	private static int lastY = -1;

	/**
	 * 내용물을 칸 순서 그대로 담는다. 줄 수는 마지막으로 찬 칸까지 보이는 만큼이고, 셜커 상자 크기(3줄)보다 줄이지 않는다.
	 * 54번 칸 이후에도 내용물이 있어서 격자에 다 못 그리면 빈 값을 준다.
	 * 칸 번호는 내용물을 모두 복사해야만 알 수 있으므로, 여기서 한 번 복사한 것으로 칸도 세고 자료도 만든다.
	 */
	public static Optional<ContainerPreviewTooltip> of(ItemContainerContents contents) {
		List<ItemStack> items = contents.stream().toList();
		int lastFilled = items.size() - 1;

		while (lastFilled >= 0 && items.get(lastFilled).isEmpty()) {
			lastFilled--;
		}

		if (lastFilled >= MAX_SLOTS) {
			return Optional.empty();
		}

		int rows = Mth.clamp(Mth.positiveCeilDiv(lastFilled + 1, COLUMNS), MIN_ROWS, MAX_SLOTS / COLUMNS);
		List<ItemStack> slots = IntStream.range(0, rows * COLUMNS).mapToObj(slot -> slot < items.size() ? items.get(slot) : ItemStack.EMPTY).toList();
		return Optional.of(new ContainerPreviewTooltip(slots, rows));
	}

	/** 마지막으로 그린 격자의 왼쪽 좌표. 아직 그린 적이 없으면 -1. */
	public static int lastX() {
		return lastX;
	}

	/** 마지막으로 그린 격자의 위쪽 좌표. 아직 그린 적이 없으면 -1. */
	public static int lastY() {
		return lastY;
	}

	/** 툴팁 안에 격자를 그린다. 칸마다 옅은 배경을 깔고 그 위에 아이템과 개수를 그린다. */
	public record Renderer(ContainerPreviewTooltip preview) implements ClientTooltipComponent {
		@Override
		public int getWidth(Font font) {
			return COLUMNS * CELL;
		}

		@Override
		public int getHeight(Font font) {
			// 아래 글자 줄과 붙지 않게 2픽셀 띄운다.
			return preview.rows() * CELL + 2;
		}

		@Override
		public void renderImage(Font font, int x, int y, int width, int height, GuiGraphics graphics) {
			lastX = x;
			lastY = y;

			for (int slot = 0; slot < preview.slots().size(); slot++) {
				int cellX = x + slot % COLUMNS * CELL;
				int cellY = y + slot / COLUMNS * CELL;
				ItemStack stack = preview.slots().get(slot);
				graphics.fill(cellX + 1, cellY + 1, cellX + 17, cellY + 17, 0x30FFFFFF);
				graphics.renderItem(stack, cellX + 1, cellY + 1);
				graphics.renderItemDecorations(font, stack, cellX + 1, cellY + 1);
			}
		}
	}
}
