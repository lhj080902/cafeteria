package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.KeyState;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	/** 키 입력을 바닐라 키 설정과 따로 세어서, 다른 키 설정과 겹쳐도 Cafeteria 키가 동작하게 한다. */
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void cafeteria$trackKey(long window, int keyCode, int scanCode, int action, int modifiers, CallbackInfo ci) {
		if (window == minecraft.getWindow().getWindow()) {
			KeyState.onKeyEvent(keyCode, scanCode, action);
		}
	}
}
