package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 프리룩 중에는 카메라가 캐릭터의 시선 대신 프리룩 각도를 따라가게 한다. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
	private float cafeteria$freelookYaw(Entity entity, float partialTick, Operation<Float> original) {
		return Modules.FREELOOK.isActive() ? Modules.FREELOOK.yaw() : original.call(entity, partialTick);
	}

	@WrapOperation(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
	private float cafeteria$freelookPitch(Entity entity, float partialTick, Operation<Float> original) {
		return Modules.FREELOOK.isActive() ? Modules.FREELOOK.pitch() : original.call(entity, partialTick);
	}
}
