package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** 손을 완전히 숨길 때 화면 아래로 내리는 거리 (손을 그리는 좌표계 기준). */
	private static final float HAND_DROP = 1.0F;

	/**
	 * 줌: 월드를 그릴 때 쓰는 시야각만 줄인다.
	 * getFov 의 결과를 통째로 바꾸지 않고 "월드를 그리는 곳에서 읽은 값"만 바꾸므로,
	 * 손(아이템)을 그리는 쪽은 다른 모드가 어떤 방식으로 시야각을 읽더라도 줌의 영향을 받지 않는다.
	 */
	@ModifyExpressionValue(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;getFov(Lnet/minecraft/client/Camera;FZ)F"))
	private float cafeteria$zoom(float fov) {
		return fov / Modules.ZOOM.currentFactor();
	}

	/**
	 * 줌 중 손 숨기기: 확대하는 동안 1인칭 손과 든 아이템을 화면 아래로 내리고, 다 내려가면 그리지 않는다.
	 * 줌한 화면을 손이 가리지 않게 하기 위한 것으로, 줌을 풀면 다시 올라온다.
	 * 셰이더 모드처럼 손 그리기를 통째로 바꾸는 모드와 함께 쓰면 적용되지 않을 수 있으므로 필수로 두지 않는다.
	 */
	@WrapOperation(
		method = "renderItemInHand",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"),
		require = 0)
	private void cafeteria$lowerHandWhileZooming(
		ItemInHandRenderer renderer, float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource buffers, LocalPlayer player, int light,
		Operation<Void> original) {
		float hidden = Modules.ZOOM.handHiddenAmount();

		if (hidden >= 0.999F) {
			return;
		}

		if (hidden <= 0.0F) {
			original.call(renderer, partialTick, poseStack, buffers, player, light);
			return;
		}

		poseStack.pushPose();
		poseStack.translate(0.0F, -hidden * HAND_DROP, 0.0F);
		original.call(renderer, partialTick, poseStack, buffers, player, light);
		poseStack.popPose();
	}
}
