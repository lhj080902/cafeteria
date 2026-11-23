package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** 창 제목 앞에 클라이언트 이름을 붙인다. */
	@ModifyReturnValue(method = "createTitle", at = @At("RETURN"))
	private String cafeteria$windowTitle(String title) {
		return "Cafeteria Client | " + title;
	}
}
