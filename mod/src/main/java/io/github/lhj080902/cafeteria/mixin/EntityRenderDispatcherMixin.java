package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	/** 히트박스 모듈이 켜져 있으면 F3+B 를 누르지 않아도 히트박스 그리기 분기로 들어가게 한다. */
	@ModifyExpressionValue(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/EntityRenderer;)V",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitBoxes:Z")
	)
	private boolean cafeteria$forceHitboxes(boolean original) {
		return original || Modules.HITBOX.isEnabled();
	}

	/**
	 * 모듈이 켜져 있으면 바닐라 히트박스 대신 설정(색상·대상)이 적용된 히트박스를 그린다.
	 * 흰색(1, 1, 1)이 아닌 호출은 바닐라의 서버 측 히트박스 디버그용이므로 그대로 둔다.
	 */
	@Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
	private static void cafeteria$customHitbox(PoseStack poseStack, VertexConsumer lines, Entity entity, float partialTick, float red, float green, float blue, CallbackInfo ci) {
		if (Modules.HITBOX.isEnabled() && red == 1.0F && green == 1.0F && blue == 1.0F) {
			Modules.HITBOX.render(poseStack, lines, entity, partialTick);
			ci.cancel();
		}
	}
}
