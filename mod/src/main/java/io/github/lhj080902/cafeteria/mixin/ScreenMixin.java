package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
	/** 깨끗한 메뉴 배경: 메뉴(ESC, 설정 등)를 열 때 뒤 화면을 흐리게 만드는 효과를 건너뛴다. */
	@Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
	private void cafeteria$skipBlur(CallbackInfo ci) {
		if (Modules.MENU_BACKGROUND.skipsBlur()) {
			ci.cancel();
		}
	}

	/** 깨끗한 메뉴 배경: 바닐라의 어두운 배경 그림 대신 설정한 만큼만 어둡게 덮는다. */
	@Inject(method = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;IIII)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void cafeteria$dimBackground(GuiGraphics graphics, int x, int y, int width, int height, CallbackInfo ci) {
		if (!Modules.MENU_BACKGROUND.isEnabled()) {
			return;
		}

		int color = Modules.MENU_BACKGROUND.dimColor();

		if (color != 0) {
			graphics.fill(x, y, x + width, y + height, color);
		}

		ci.cancel();
	}
}
