package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** 줌: 월드를 그릴 때의 시야각만 줄인다. 손(아이템)을 그릴 때(useFovSetting = false)는 건드리지 않는다. */
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float cafeteria$zoom(float fov, Camera camera, float partialTick, boolean useFovSetting) {
		return useFovSetting ? fov / Modules.ZOOM.currentFactor() : fov;
	}
}
