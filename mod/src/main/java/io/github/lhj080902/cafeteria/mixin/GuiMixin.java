package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	/** 포션 효과 HUD 의 "기본 효과 아이콘 숨기기": 화면 오른쪽 위에 뜨는 바닐라 효과 아이콘을 그리지 않는다. */
	@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true, require = 0)
	private void cafeteria$hideVanillaEffects(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Modules.POTIONS.hidesVanillaIcons()) {
			ci.cancel();
		}
	}
}
