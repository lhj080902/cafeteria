package io.github.lhj080902.cafeteria.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.module.HealthBarModule;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 체력바를 그린다. 월드 안에 그리지 않고, 머리 위 기준점을 화면 좌표로 옮겨서 HUD 단계에서 2D 로 그린다.
 * 월드 안에 그려지는 것(바닐라 이름표, 서버가 띄운 글자 표시 엔티티)은 모두 그 전에 끝나므로 어떤 칭호보다도 앞에 보인다.
 * 틱과 프레임은 모두 렌더 스레드에서 돌기 때문에 여기 있는 상태는 그 스레드에서만 읽고 쓴다.
 */
public final class HealthBarRenderer {
	/** 카메라 앞쪽으로 이보다 가까운 기준점은 그리지 않는다 (바닐라가 월드를 그리기 시작하는 거리). */
	private static final float MIN_DEPTH = 0.05F;
	/** 바닐라 이름표의 글자 크기: 1픽셀이 0.025블록. */
	private static final float BLOCKS_PER_PIXEL = 0.025F;
	/** 바닐라 이름표는 부착점에서 이만큼 위에 글자의 위쪽 끝을 둔다. */
	private static final double NAME_TAG_LIFT = 0.5;
	/** 바닐라가 웅크린 상대의 이름표를 숨기기 시작하는 거리. */
	private static final double SNEAK_RANGE = 32.0;
	/**
	 * 체력바 하나가 차지하는 깊이(z). 가까운 체력바일수록 이만큼씩 앞에 그린다.
	 * 바닐라는 그림자 있는 글자의 본 글자를 0.03, 취소선을 0.04 앞에, 밑줄의 그림자를 0.01 뒤에 그린다.
	 * 그 폭(0.05)보다 넉넉히 커야 가까운 체력바의 배경과 바가 먼 체력바의 글자를 덮는다.
	 */
	private static final float Z_STEP = 0.1F;
	/**
	 * 깊이를 따로 주는 체력바의 수. 다 써도 0.1 x 999 + 0.04 로 100 을 넘지 않아서, 200 앞에 있는 다음 HUD 층에 닿지 않는다.
	 * 체력바가 이보다 많으면 넘는 만큼의 가장 먼 것들이 맨 뒤 깊이를 같이 쓴다. 게임 테스트가 그 경우를 만들 때도 쓴다.
	 */
	public static final int MAX_LAYERS = 1000;

	/** 배치 (배율 적용 전 픽셀). 이름 줄은 0~9, 체력 줄은 그 아래 10~19 로 바닐라 이름과 머리 사이의 빈 곳에 들어간다. */
	private static final int ROW_Y = 10;
	private static final int ICON = 9;
	private static final int ICON_GAP = 1;
	private static final int TEXT_Y = 1;
	private static final int BAR_GAP = 2;
	private static final int BAR_WIDTH = 30;
	private static final int BAR_HEIGHT = 5;
	private static final int BAR_Y = (ICON - BAR_HEIGHT) / 2;
	private static final int ARMOR_GAP = 3;

	private static final int BACKGROUND = 0x80000000;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int BAR_BACKGROUND = 0xFF2B2B2B;
	private static final int ABSORPTION_COLOR = 0xFFFFD24A;
	private static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
	private static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
	private static final ResourceLocation ARMOR_FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");

	/** 마지막으로 월드를 그린 프레임의 행렬과 카메라 위치. 카메라 위치가 null 이면 아직 받은 적이 없다. */
	private static final Matrix4f POSITION_MATRIX = new Matrix4f();
	private static final Matrix4f PROJECTION_MATRIX = new Matrix4f();
	private static Vec3 cameraPosition;
	/** 마지막 틱에 고른 대상(엔티티 id)과 그때의 월드. id 는 그 월드 안에서만 같은 엔티티를 가리킨다. */
	private static List<Integer> targets = List.of();
	private static ClientLevel targetLevel;
	private static List<Drawn> lastDrawn = List.of();

	/** 한 프레임에 그린 체력바 하나. 좌표는 GUI 좌표(배율 적용 후)이고 bar 로 시작하는 것은 체력바 바탕 사각형, fill 은 채운 비율(0~1). */
	public record Drawn(int entityId, float left, float top, float right, float bottom, float barLeft, float barTop, float barRight, float barBottom, float fill) {
	}

	/** 화면 좌표로 옮긴 기준점. scale 은 그리기 배율, depth 는 카메라 앞쪽으로의 거리. */
	private record Projected(LivingEntity entity, float x, float y, float scale, float depth) {
	}

	private HealthBarRenderer() {
	}

	/**
	 * 체력바 층이 마지막으로 돈 프레임에 그린 체력바들, 그린 순서대로 (그 프레임에 그린 것이 없으면 빈 목록).
	 * F1 로 HUD 를 숨긴 동안에는 층이 돌지 않아서 그 전 프레임의 목록이 그대로 남는다. 게임 테스트가 자리를 확인할 때 쓴다.
	 */
	public static List<Drawn> lastDrawn() {
		return lastDrawn;
	}

	/**
	 * 월드를 그릴 때 쓴 행렬과 카메라 위치를 받아 둔다. 화면 흔들림과 줌까지 들어 있는 실제 값이다.
	 * 행렬 객체는 다음 프레임에 다시 쓰일 수 있어서 값을 복사해 둔다. 카메라 위치(Vec3)는 바뀌지 않는 값이라 그대로 둔다.
	 */
	public static void captureFrame(WorldRenderContext context) {
		POSITION_MATRIX.set(context.positionMatrix());
		PROJECTION_MATRIX.set(context.projectionMatrix());
		cameraPosition = context.camera().getPosition();
	}

	/** 체력바를 띄울 대상을 고른다. 블록 광선을 쏘는 시야 판정이 들어 있어서 프레임마다가 아니라 틱마다 한 번만 한다. */
	public static void updateTargets(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		Camera camera = minecraft.gameRenderer.getMainCamera();

		if (level == null || minecraft.player == null || !camera.isInitialized()) {
			forgetTargets();
			return;
		}

		Vec3 eye = camera.getPosition();
		double range = Modules.HEALTH_BAR.distance.get();
		List<Integer> visible = new ArrayList<>();

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || !isTarget(minecraft, living)) {
				continue;
			}

			// 웅크린 상대는 바닐라 이름표가 사라지는 거리까지만 보여 준다. 바닐라보다 멀리서 드러내지 않는다.
			double limit = living.isDiscrete() ? Math.min(range, SNEAK_RANGE) : range;

			if (living.position().distanceToSqr(eye) > limit * limit) {
				continue;
			}

			// 눈 높이와 머리 위 기준점 중 하나라도 블록에 가리지 않으면 보이는 것으로 친다. 벽 뒤는 표시하지 않는다.
			if (canSee(level, eye, living.getEyePosition(), living) || canSee(level, eye, anchor(living, 1.0F), living)) {
				visible.add(living.getId());
			}
		}

		targets = visible;
		targetLevel = level;
	}

	/** 고른 대상을 버린다. 월드가 바뀌거나 모듈이 꺼지면 예전 id 가 다른 엔티티를 가리킬 수 있다. */
	private static void forgetTargets() {
		targets = List.of();
		targetLevel = null;
	}

	/** 나 자신(카메라 엔티티)과 내가 탄 엔티티, 죽은 것, 관전자, 갑옷 거치대, 나에게 투명한 것은 대상이 아니다. */
	private static boolean isTarget(Minecraft minecraft, LivingEntity entity) {
		Entity cameraEntity = minecraft.getCameraEntity();

		if (entity == cameraEntity || !entity.isAlive() || entity.isSpectator() || entity instanceof ArmorStand) {
			return false;
		}

		// 내가 탄 엔티티(그것이 또 무언가를 타고 있으면 거기까지)의 기준점은 카메라 바로 아래라서, 그리면 화면을 덮을 만큼 커진다.
		// 바닐라도 누가 타고 있는 엔티티의 이름표는 숨긴다.
		if (cameraEntity != null && entity.hasIndirectPassenger(cameraEntity)) {
			return false;
		}

		if (Modules.HEALTH_BAR.target.is("players") && !(entity instanceof Player)) {
			return false;
		}

		return !entity.isInvisibleTo(minecraft.player);
	}

	/** 두 지점 사이를 블록이 가리지 않는지 (엔티티는 따지지 않는다). */
	private static boolean canSee(ClientLevel level, Vec3 from, Vec3 to, Entity entity) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS;
	}

	/**
	 * 체력바의 기준점(이름 줄의 위쪽 가운데). 바닐라 이름표의 글자와 같은 자리에 높이 조정을 더한 곳이다.
	 * 이름 아래에 점수가 있으면 바닐라 이름은 이보다 위로 올라가는데, 그 차이는 높이 조정 설정으로 맞춘다.
	 */
	private static Vec3 anchor(LivingEntity entity, float partialTick) {
		Vec3 attachment = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));

		if (attachment == null) {
			attachment = new Vec3(0.0, entity.getBbHeight(), 0.0);
		}

		return entity.getPosition(partialTick).add(attachment).add(0.0, NAME_TAG_LIFT + Modules.HEALTH_BAR.offset.get(), 0.0);
	}

	/** HUD 단계에서 프레임마다 불린다. F1 로 HUD 를 숨기면 불리지 않는다. */
	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		lastDrawn = List.of();

		if (!Modules.HEALTH_BAR.isEnabled()) {
			// 꺼져 있는 동안에는 대상을 새로 고르지 않는다. 다시 켰을 때 예전에 고른 대상이 그려지지 않게 지워 둔다.
			forgetTargets();
			return;
		}

		// 다른 월드에서 고른 id 는 이 월드에서 엉뚱한 엔티티를 가리킨다. 행렬을 아직 받지 못한 프레임에도 그리지 않는다.
		if (level == null || level != targetLevel || cameraPosition == null) {
			return;
		}

		List<Projected> points = new ArrayList<>();

		for (int id : targets) {
			// 대상을 고른 뒤(지난 틱)에 죽었거나 사라졌을 수 있다.
			if (level.getEntity(id) instanceof LivingEntity entity && entity.isAlive()) {
				// 바닐라가 엔티티를 그릴 때와 같은 보간 값을 써서 움직이는 상대의 머리 위를 그대로 따라간다.
				float partialTick = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
				Projected point = project(entity, partialTick, graphics.guiWidth(), graphics.guiHeight());

				if (point != null) {
					points.add(point);
				}
			}
		}

		// 먼 것부터 그리되 가까운 것일수록 한 단계씩 앞(z)에 그려서, 겹치면 가까운 것이 위에 오게 한다.
		// 순서만으로는 안 된다: GUI 는 깊이 검사를 하고 그림자 있는 글자는 본 글자가 조금 앞에 그려지므로,
		// 모두 같은 깊이에 그리면 먼 체력바의 글자가 가까운 체력바의 배경과 바를 뚫고 보인다.
		points.sort(Comparator.comparingDouble(Projected::depth).reversed());
		int shared = Math.max(0, points.size() - MAX_LAYERS);
		List<Drawn> drawn = new ArrayList<>();

		for (int index = 0; index < points.size(); index++) {
			drawn.add(draw(graphics, minecraft.font, points.get(index), Math.max(0, index - shared) * Z_STEP));
		}

		lastDrawn = List.copyOf(drawn);
	}

	/** 기준점을 화면 좌표로 옮긴다. 카메라 뒤에 있거나 카메라에 붙어 있으면 null. */
	private static Projected project(LivingEntity entity, float partialTick, int guiWidth, int guiHeight) {
		Vec3 relative = anchor(entity, partialTick).subtract(cameraPosition);
		Vector4f clip = new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F).mul(POSITION_MATRIX).mul(PROJECTION_MATRIX);

		// w 는 카메라 앞쪽으로의 거리다. 0 보다 작으면 카메라 뒤인데 그대로 나누면 화면 반대편에 뒤집혀 나오고, 0 이면 나눌 수 없다.
		if (clip.w <= MIN_DEPTH) {
			return null;
		}

		float x = (clip.x / clip.w + 1.0F) / 2.0F * guiWidth;
		float y = (1.0F - clip.y / clip.w) / 2.0F * guiHeight;
		// 그 거리에서 1블록이 차지하는 GUI 픽셀 수. 바닐라 이름표처럼 멀수록 작아지고 줌을 하면 같이 커진다.
		float blockPixels = guiHeight / 2.0F * PROJECTION_MATRIX.m11() / clip.w;
		return new Projected(entity, x, y, blockPixels * BLOCKS_PER_PIXEL * Modules.HEALTH_BAR.scale.asFloat(), clip.w);
	}

	/** 기준점을 위쪽 가운데로 해서 이름 줄과 체력 줄을 깊이 z 에 그리고, 그린 자리를 돌려준다. */
	private static Drawn draw(GuiGraphics graphics, Font font, Projected point, float z) {
		HealthBarModule module = Modules.HEALTH_BAR;
		LivingEntity entity = point.entity();
		float maxHealth = entity.getMaxHealth();
		float ratio = Mth.clamp(entity.getHealth() / maxHealth, 0.0F, 1.0F);
		// 살아 있는 대상만 그리므로, 체력이 아주 조금 남았어도 한 칸은 채워서 죽은 것처럼 보이지 않게 한다.
		int filled = Math.max(1, Math.round(BAR_WIDTH * ratio));
		// 흡수 체력은 플레이어만 서버가 알려 준다 (몹은 항상 0). 조금이라도 있으면 수치에 더해지므로 줄도 한 칸은 보이게 한다.
		float absorption = entity.getAbsorptionAmount();
		int absorbed = absorption > 0.0F ? Math.max(1, Math.round(BAR_WIDTH * Math.min(1.0F, absorption / maxHealth))) : 0;

		// 이름은 서버가 보낸 그대로(팀 접두·접미, 색 포함) 쓴다.
		Component name = module.showName.get() ? entity.getDisplayName() : null;
		String number = module.showNumber.get() ? formatHealth(entity.getHealth() + absorption) : null;
		String armor = module.showArmor.get() ? String.valueOf(entity.getArmorValue()) : null;
		int nameWidth = name == null ? 0 : font.width(name);
		// 체력 줄: 하트, 체력 수치, 체력바, 방어구 그림, 방어도. 끈 것은 그 앞의 간격까지 뺀다.
		int barX = ICON + (number == null ? 0 : ICON_GAP + font.width(number)) + BAR_GAP;
		int armorX = barX + BAR_WIDTH + ARMOR_GAP;
		int rowWidth = armor == null ? barX + BAR_WIDTH : armorX + ICON + ICON_GAP + font.width(armor);
		int width = Math.max(nameWidth, rowWidth);
		// 이름을 꺼도 체력 줄은 제자리에 둔다. 그 위의 바닐라 이름표를 가리지 않는다.
		int top = name == null ? ROW_Y : 0;
		int bottom = ROW_Y + ICON;
		int margin = module.background.get() ? 1 : 0;
		float rowLeft = (width - rowWidth) / 2.0F;
		float scale = point.scale();

		PoseStack pose = graphics.pose();
		pose.pushPose();
		pose.translate(point.x(), point.y(), z);
		pose.scale(scale, scale, 1.0F);
		// 여기부터 (0, 0) 은 내용의 왼쪽 위다.
		pose.translate(-width / 2.0F, 0.0F, 0.0F);

		if (margin > 0) {
			graphics.fill(-margin, top - margin, width + margin, bottom + margin, BACKGROUND);
		}

		if (name != null) {
			pose.pushPose();
			pose.translate((width - nameWidth) / 2.0F, 0.0F, 0.0F);
			graphics.drawString(font, name, 0, 0, TEXT_COLOR, true);
			pose.popPose();
		}

		pose.translate(rowLeft, ROW_Y, 0.0F);
		graphics.blitSprite(RenderType::guiTextured, HEART_CONTAINER, 0, 0, ICON, ICON);
		graphics.blitSprite(RenderType::guiTextured, HEART_FULL, 0, 0, ICON, ICON);

		if (number != null) {
			graphics.drawString(font, number, ICON + ICON_GAP, TEXT_Y, TEXT_COLOR, true);
		}

		graphics.fill(barX, BAR_Y, barX + BAR_WIDTH, BAR_Y + BAR_HEIGHT, BAR_BACKGROUND);
		graphics.fill(barX, BAR_Y, barX + filled, BAR_Y + BAR_HEIGHT, fillColor(ratio));

		if (absorbed > 0) {
			graphics.fill(barX, BAR_Y, barX + absorbed, BAR_Y + 1, ABSORPTION_COLOR);
		}

		if (armor != null) {
			graphics.blitSprite(RenderType::guiTextured, ARMOR_FULL, armorX, 0, ICON, ICON);
			graphics.drawString(font, armor, armorX + ICON + ICON_GAP, TEXT_Y, TEXT_COLOR, true);
		}

		pose.popPose();

		float left = point.x() - (width / 2.0F + margin) * scale;
		float barLeft = point.x() + (rowLeft + barX - width / 2.0F) * scale;
		float barTop = point.y() + (ROW_Y + BAR_Y) * scale;
		return new Drawn(entity.getId(),
			left, point.y() + (top - margin) * scale, left + (width + margin * 2) * scale, point.y() + (bottom + margin) * scale,
			barLeft, barTop, barLeft + BAR_WIDTH * scale, barTop + BAR_HEIGHT * scale, (float) filled / BAR_WIDTH);
	}

	/** 절반을 넘으면 초록, 4분의 1을 넘으면 노랑, 그 밖에는 빨강. */
	private static int fillColor(float ratio) {
		if (ratio > 0.5F) {
			return 0xFF3FD23F;
		}

		return ratio > 0.25F ? 0xFFE6C229 : 0xFFE0382B;
	}

	/** 소수 첫째 자리까지 반올림하고 ".0" 은 뗀다 (20, 17.5). 서버가 0 보다 작은 값을 보내면 0 으로 적는다. */
	private static String formatHealth(float value) {
		long tenths = Math.round(Math.max(0.0F, value) * 10.0);
		return tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10;
	}
}
