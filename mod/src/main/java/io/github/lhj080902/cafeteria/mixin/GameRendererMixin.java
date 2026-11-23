package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
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
	 * 줌 중 손 숨기기: 설정을 켜 두면 확대하는 동안 1인칭 손과 아이템을 그리지 않는다.
	 * 셰이더 모드처럼 손 그리기를 통째로 바꾸는 모드와 함께 쓰면 적용되지 않을 수 있으므로 필수로 두지 않는다.
	 */
	@WrapWithCondition(
		method = "renderItemInHand",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"),
		require = 0)
	private boolean cafeteria$hideHandWhileZooming(
		ItemInHandRenderer renderer, float partialTick, PoseStack poseStack, MultiBufferSource.BufferSource buffers, LocalPlayer player, int light) {
		return !Modules.ZOOM.hidesHand();
	}
}
