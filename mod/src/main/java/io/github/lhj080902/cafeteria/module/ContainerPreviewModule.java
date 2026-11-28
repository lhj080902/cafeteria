package io.github.lhj080902.cafeteria.module;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.lhj080902.cafeteria.gui.ContainerPreviewTooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.Optional;

/** 컨테이너 미리보기. Shift 를 누른 채 셜커 상자처럼 내용물이 든 아이템에 커서를 올리면 툴팁에 내용물을 격자로 보여 준다. */
public class ContainerPreviewModule extends Module {
	public ContainerPreviewModule() {
		super("container_preview", true);
	}

	/**
	 * 이 아이템의 툴팁에 넣을 미리보기. 보여 줄 수 없거나 보여 주면 안 되는 때에는 빈 값.
	 * 툴팁 글자 줄은 검색 색인을 만드는 스레드에서도 만들어진다. 그래서 어느 스레드에서나 볼 수 있는 싼 조건을 먼저 보고,
	 * 키 상태는 렌더 스레드에서만 읽으며, 내용물을 모두 복사해야 하는 칸 세기는 Shift 를 누른 렌더 스레드에서만 맨 마지막에 한다.
	 */
	public Optional<ContainerPreviewTooltip> preview(ItemStack stack) {
		if (!isEnabled()) {
			return Optional.empty();
		}

		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);

		if (contents == null || !contents.nonEmptyItems().iterator().hasNext()) {
			return Optional.empty();
		}

		// 바닐라 꾸러미도 숨김 표시가 있으면 그림을 주지 않는다. 서버가 내용을 숨기려고 붙인 아이템을 드러내지 않는다.
		if (stack.has(DataComponents.HIDE_TOOLTIP) || stack.has(DataComponents.HIDE_ADDITIONAL_TOOLTIP)) {
			return Optional.empty();
		}

		// 아직 열지 않은 전리품 상자는 바닐라의 "???????" 줄을 그대로 둔다.
		if (stack.has(DataComponents.CONTAINER_LOOT)) {
			return Optional.empty();
		}

		if (!RenderSystem.isOnRenderThread() || !Screen.hasShiftDown()) {
			return Optional.empty();
		}

		// 격자에 다 못 그리는 내용물이 있으면 빈 값이 온다. 바닐라 글자 툴팁을 그대로 둬서 정보가 사라지지 않게 한다.
		return ContainerPreviewTooltip.of(contents);
	}

	/** 격자가 내용물을 보여 주는 동안에는 같은 내용을 적은 셜커 상자의 글자 줄을 숨긴다. 판단은 preview 한 곳에서만 한다. */
	public boolean replacesText(ItemStack stack) {
		return preview(stack).isPresent();
	}
}
