package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	/** 풀브라이트: 라이트맵 계산에서 "밝기(감마)" 옵션을 읽을 때만 높은 값으로 바꿔 준다. 실제 옵션 값은 건드리지 않는다. */
	@WrapOperation(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
	private Object cafeteria$fullbright(OptionInstance<?> option, Operation<Object> original) {
		if (Modules.FULLBRIGHT.isEnabled() && option == minecraft.options.gamma()) {
			return Modules.FULLBRIGHT.level.get();
		}

		return original.call(option);
	}
}
