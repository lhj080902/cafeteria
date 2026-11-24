package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.module.NoFogModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	/**
	 * 안개 제거: 지형·엔티티·입자에 쓰는 안개의 시작·끝 거리를 아주 멀리 보낸다. 색과 모양은 그대로 둔다.
	 * 하늘에 쓰는 안개(FOG_SKY)와 실명·어둠 효과의 안개는 건드리지 않는다.
	 */
	@ModifyReturnValue(method = "setupFog", at = @At("RETURN"), require = 0)
	private static FogParameters cafeteria$clearFog(FogParameters original, Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance, boolean foggy, float partialTick) {
		if (mode != FogRenderer.FogMode.FOG_TERRAIN || !Modules.NO_FOG.clears(camera.getFluidInCamera())) {
			return original;
		}

		if (camera.getEntity() instanceof LivingEntity living && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))) {
			return original;
		}

		return new FogParameters(NoFogModule.FAR_START, NoFogModule.FAR_END, original.shape(), original.red(), original.green(), original.blue(), original.alpha());
	}
}
