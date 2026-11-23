package io.github.lhj080902.cafeteria.module;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * 히트박스 표시. 바닐라의 F3+B 히트박스를 색상·대상 필터가 있는 버전으로 바꿔 그린다.
 * 바닐라와 똑같이 투명 상태인 엔티티와 "디버그 정보 축소" 서버에서는 표시하지 않는다.
 */
public class HitboxModule extends Module {
	public final ModeSetting target = add(new ModeSetting("hitbox_target", "all", "all", "living", "players"));
	public final ColorSetting color = add(new ColorSetting("hitbox_color", 0xFFFFFFFF));
	public final BooleanSetting hurtHighlight = add(new BooleanSetting("hitbox_hurt", true));
	public final ColorSetting hurtColor = add(new ColorSetting("hitbox_hurt_color", 0xFFFF5555));
	public final BooleanSetting eyeLine = add(new BooleanSetting("hitbox_eye", false));
	public final BooleanSetting lookVector = add(new BooleanSetting("hitbox_look", false));
	public final BooleanSetting showSelf = add(new BooleanSetting("hitbox_self", false));

	public HitboxModule() {
		super("hitbox", true);
	}

	public boolean shouldRender(Entity entity) {
		if (entity == Minecraft.getInstance().player && !showSelf.get()) {
			return false;
		}

		if (target.is("players")) {
			return entity instanceof Player;
		}

		return !target.is("living") || entity instanceof LivingEntity;
	}

	/** EntityRenderDispatcher#renderHitbox 대신 호출된다. poseStack 은 엔티티 위치로 이동된 상태다. */
	public void render(PoseStack poseStack, VertexConsumer lines, Entity entity, float partialTick) {
		if (!shouldRender(entity)) {
			return;
		}

		boolean hurt = hurtHighlight.get() && entity instanceof LivingEntity living && living.hurtTime > 0;
		ColorSetting lineColor = hurt ? hurtColor : color;
		AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
		ShapeRenderer.renderLineBox(poseStack, lines, box, lineColor.red(), lineColor.green(), lineColor.blue(), 1.0F);

		if (eyeLine.get() && entity instanceof LivingEntity) {
			float eye = entity.getEyeHeight();
			ShapeRenderer.renderLineBox(poseStack, lines, box.minX, eye - 0.01F, box.minZ, box.maxX, eye + 0.01F, box.maxZ, 1.0F, 0.0F, 0.0F, 1.0F);
		}

		if (lookVector.get()) {
			ShapeRenderer.renderVector(poseStack, lines, new Vector3f(0.0F, entity.getEyeHeight(), 0.0F), entity.getViewVector(partialTick).scale(2.0), 0xFF0000FF);
		}
	}
}
