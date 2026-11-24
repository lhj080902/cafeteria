package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.UUID;

@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
	@Shadow
	@Final
	Map<UUID, LerpingBossEvent> events;

	/**
	 * 보스바 위치·크기: 바닐라가 보스바를 그리는 동안만 이동·배율 변환을 건다.
	 * 옮기지 않았고 100%면 아무것도 걸지 않는다. 다른 모드와 겹쳐 주입에 실패해도 게임은 켜지고 보스바만 바닐라 그대로다.
	 */
	@WrapMethod(method = "render", require = 0)
	private void cafeteria$placeBossbar(GuiGraphics graphics, Operation<Void> original) {
		boolean transformed = !events.isEmpty() && Modules.BOSSBAR.begin(graphics, events.values());

		try {
			original.call(graphics);
		} finally {
			if (transformed) {
				graphics.pose().popPose();
			}
		}
	}
}
