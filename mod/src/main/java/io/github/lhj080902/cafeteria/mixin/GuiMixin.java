package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	@Shadow
	private Component title;

	@Shadow
	private Component subtitle;

	@Shadow
	private int titleTime;

	/**
	 * 타이틀 위치·크기: 바닐라가 타이틀을 그리는 동안만 이동·배율 변환을 건다.
	 * 옮기지 않았고 100%면 아무것도 걸지 않는다. 다른 모드와 겹쳐 주입에 실패해도 게임은 켜지고 타이틀만 바닐라 그대로다.
	 */
	@WrapMethod(method = "renderTitle", require = 0)
	private void cafeteria$placeTitle(GuiGraphics graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		boolean transformed = title != null && titleTime > 0 && Modules.TITLE.begin(graphics, title, subtitle);

		try {
			original.call(graphics, deltaTracker);
		} finally {
			if (transformed) {
				graphics.pose().popPose();
			}
		}
	}

	/** 스코어보드 위치·크기: 바닐라가 사이드바를 그리는 동안만 이동·배율 변환을 건다. 타이틀과 같은 방식이다. */
	@WrapMethod(method = "displayScoreboardSidebar", require = 0)
	private void cafeteria$placeScoreboard(GuiGraphics graphics, Objective objective, Operation<Void> original) {
		boolean transformed = Modules.SCOREBOARD.begin(graphics, objective);

		try {
			original.call(graphics, objective);
		} finally {
			if (transformed) {
				graphics.pose().popPose();
			}
		}
	}

	/** 포션 효과 HUD 의 "기본 효과 아이콘 숨기기": 화면 오른쪽 위에 뜨는 바닐라 효과 아이콘을 그리지 않는다. */
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true, require = 0)
	private void cafeteria$hideVanillaEffects(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Modules.POTIONS.hidesVanillaIcons()) {
			ci.cancel();
		}
	}
}
