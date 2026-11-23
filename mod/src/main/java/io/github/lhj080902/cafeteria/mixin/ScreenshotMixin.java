package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
	/** 스크린샷 도구: 저장 결과를 알리는 채팅 안내에 [복사] [폴더] 버튼을 붙인다. */
	@ModifyVariable(method = "_grab", at = @At("HEAD"), argsOnly = true, require = 0)
	private static Consumer<Component> cafeteria$addScreenshotButtons(Consumer<Component> messageConsumer) {
		return Modules.SCREENSHOT.wrap(messageConsumer);
	}
}
