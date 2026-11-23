package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lhj080902.cafeteria.KeyState;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.hud.ClickTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	/** 마우스 이동을 캐릭터 회전으로 넘기는 지점. 줌 감도 보정과 프리룩 카메라 회전을 여기서 처리한다. */
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void cafeteria$turn(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
		double scale = Modules.ZOOM.sensitivityScale();
		yaw *= scale;
		pitch *= scale;

		if (Modules.FREELOOK.isActive()) {
			Modules.FREELOOK.turn(yaw, pitch);
		} else {
			original.call(player, yaw, pitch);
		}
	}

	/** 마우스 버튼에 연결한 Cafeteria 키와 CPS 측정. CPS 는 화면(GUI)이 열려 있지 않을 때의 누름만 센다. */
	@Inject(method = "onPress", at = @At("HEAD"))
	private void cafeteria$trackButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
		if (window != minecraft.getWindow().getWindow()) {
			return;
		}

		KeyState.onMouseButtonEvent(button, action);

		if (action == 1 && minecraft.screen == null) {
			ClickTracker.record(button);
		}
	}

	/** 줌 중에는 휠이 핫바 대신 줌 배율을 바꾼다. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void cafeteria$zoomScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (window == minecraft.getWindow().getWindow() && minecraft.screen == null && minecraft.getOverlay() == null && Modules.ZOOM.onScroll(vertical)) {
			ci.cancel();
		}
	}
}
