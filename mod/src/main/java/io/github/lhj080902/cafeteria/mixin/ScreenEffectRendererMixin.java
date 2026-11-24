package io.github.lhj080902.cafeteria.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	/** 낮은 화염: 불이 붙었을 때 화면을 가리는 불꽃을 아래로 내려 그린다. */
	@Inject(method = "renderFire", at = @At("HEAD"))
	private static void cafeteria$lowerFire(PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
		poseStack.pushPose();
		poseStack.translate(0.0F, -Modules.LOW_FIRE.offset(), 0.0F);
	}

	@Inject(method = "renderFire", at = @At("RETURN"))
	private static void cafeteria$restoreAfterFire(PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
		poseStack.popPose();
	}

	/** 안개 제거: 물속에서 화면 전체에 덮이는 물 무늬를 그리지 않는다. */
	@Inject(method = "renderWater", at = @At("HEAD"), cancellable = true, require = 0)
	private static void cafeteria$hideWaterOverlay(Minecraft minecraft, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
		if (Modules.NO_FOG.clears(FogType.WATER)) {
			ci.cancel();
		}
	}
}
