package io.github.lhj080902.cafeteria.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.function.Supplier;

/**
 * 바닐라가 그리는 HUD(타이틀, 스코어보드, 보스바)를 옮기고 크기를 바꾸는 요소.
 * 직접 그리지 않는다. 믹스인이 바닐라의 그리기 메서드 바로 앞에서 {@link #begin} 을 불러 이동·배율 변환만 건다.
 * <ul>
 *   <li>옮기지 않았고 크기가 100%면 아무것도 걸지 않는다. 화면은 바닐라와 픽셀까지 같다.</li>
 *   <li>옮기지 않고 크기만 바꾸면 바닐라의 정렬(가운데, 오른쪽 끝 등)을 유지한다.</li>
 *   <li>한 번 옮기면 다른 HUD 요소처럼 화면 여유 공간에 대한 비율로 자리를 기억한다. 위치 초기화를 하면 바닐라 자리로 돌아간다.</li>
 * </ul>
 * 화면에 실제 내용이 없을 때는 편집 화면에서만 예시를 그려서 자리를 잡을 수 있게 한다.
 */
public abstract class VanillaHudElement extends HudElement {
	/** 바닐라가 배율 1로 그리는 상자와 그때의 화면 크기 (GUI 좌표). */
	private record Box(int x, int y, int width, int height, int screenWidth, int screenHeight) {
	}

	/**
	 * 마지막으로 바닐라가 그린 내용: 그 상자를 구하는 식, 그때의 화면 크기와 프레임 번호.
	 * 상자({@link #live})는 누가 물어볼 때 한 번만 계산해 둔다.
	 */
	private Supplier<int[]> liveSource;
	private Box live;
	private int liveScreenWidth;
	private int liveScreenHeight;
	private long liveFrame = -2;
	private boolean moved;

	protected VanillaHudElement(String id) {
		super(id, true, 0.5, 0.5);
	}

	/**
	 * 믹스인이 바닐라 그리기 직전에 부른다. box 는 바닐라가 배율 1로 그릴 상자 {x, y, 너비, 높이}를 구하는 식이다.
	 * 옮기지 않았고 100%면 여기서는 상자를 구하지 않는다. 편집 화면처럼 상자를 묻는 쪽이 있을 때 그때 구한다.
	 * (스코어보드의 상자는 점수 목록을 걸러서 정렬해야 나오므로, 매 프레임 구하면 바닐라가 하는 일을 한 번 더 하게 된다.)
	 *
	 * @return 변환을 걸었으면 true. 이때는 부른 쪽이 바닐라 그리기가 끝난 뒤(예외로 끝나도) popPose 를 해야 한다.
	 */
	public final boolean begin(GuiGraphics graphics, Supplier<int[]> box) {
		if (!isEnabled()) {
			return false;
		}

		liveSource = box;
		live = null;
		liveScreenWidth = graphics.guiWidth();
		liveScreenHeight = graphics.guiHeight();
		liveFrame = HudRenderer.frame();

		if (untouched()) {
			return false;
		}

		Box current = live();
		Minecraft minecraft = Minecraft.getInstance();
		float factor = scale.asFloat();
		graphics.pose().pushPose();
		graphics.pose().translate(x(minecraft, graphics.guiWidth()) - current.x() * factor, y(minecraft, graphics.guiHeight()) - current.y() * factor, 0.0F);
		graphics.pose().scale(factor, factor, 1.0F);
		return true;
	}

	/** 사용자가 손대지 않은 상태인지: 옮기지 않았고 크기가 100%다. 이때는 바닐라가 그리는 그대로다. */
	private boolean untouched() {
		return !moved && Math.abs(scale.get() - 1.0) < 1.0e-6;
	}

	/** 지금 화면에 떠 있는 내용의 상자. 이번에 그린 내용에 대해 처음 물어볼 때 계산한다. */
	private Box live() {
		if (live == null) {
			int[] box = liveSource.get();
			live = new Box(box[0], box[1], Math.max(1, box[2]), Math.max(1, box[3]), liveScreenWidth, liveScreenHeight);
		}

		return live;
	}

	/** 바닐라가 지금 이 내용을 화면에 그리고 있는지 (이번 프레임이나 바로 앞 프레임에 그렸는지). */
	public final boolean isLive(Minecraft minecraft) {
		return liveSource != null && minecraft.level != null && !minecraft.options.hideGui && liveFrame >= HudRenderer.frame() - 1;
	}

	/** 바닐라가 더는 그리지 않는 내용(사라진 타이틀, 나간 월드의 스코어보드)을 붙잡고 있지 않는다. */
	@Override
	public void tick(Minecraft minecraft) {
		if (liveSource != null && !isLive(minecraft)) {
			liveSource = null;
			live = null;
		}
	}

	/** 사용자가 옮겼는지. 옮기기 전에는 바닐라가 놓는 자리를 따른다. */
	public final boolean isMoved() {
		return moved;
	}

	/** 예시 내용을 바닐라가 그렸을 상자 {x, y, 너비, 높이}. */
	protected abstract int[] sampleBox(Minecraft minecraft, int screenWidth, int screenHeight);

	/** 예시 내용을 바닐라가 그리는 자리(화면 좌표)에 그대로 그린다. 상자의 왼쪽 위로 옮기는 일은 이 클래스가 한다. */
	protected abstract void drawSample(GuiGraphics graphics, Minecraft minecraft);

	/** 지금 기준이 되는 상자: 화면에 떠 있으면 그 내용, 아니면 예시. */
	private Box box(Minecraft minecraft) {
		if (isLive(minecraft)) {
			return live();
		}

		int screenWidth = minecraft.getWindow().getGuiScaledWidth();
		int screenHeight = minecraft.getWindow().getGuiScaledHeight();
		int[] sample = sampleBox(minecraft, screenWidth, screenHeight);
		return new Box(sample[0], sample[1], Math.max(1, sample[2]), Math.max(1, sample[3]), screenWidth, screenHeight);
	}

	/**
	 * 옮기지 않고 크기만 바꾼 요소의 자리: 바닐라 상자가 여유 공간에서 차지하던 비율을, 크기를 바꾼 뒤의 여유 공간에 그대로 적용한다.
	 * 가운데 있던 것은 가운데에, 오른쪽 끝에 있던 것은 오른쪽 끝에 남는다.
	 * 원래 크기로는 화면보다 커도(여유 공간이 음수) 비율은 같은 뜻이므로 그대로 쓴다. 크기를 바꾼 뒤에도 화면보다 크면 왼쪽·위에 붙인다.
	 * (크기도 바꾸지 않은 요소는 여기로 오지 않는다. 바닐라가 그리는 자리를 그대로 쓴다.)
	 */
	private static int place(int start, int free, int scaledFree) {
		if (free == 0 || scaledFree <= 0) {
			return 0;
		}

		return (int) Math.round(Mth.clamp((double) start / free, 0.0, 1.0) * scaledFree);
	}

	@Override
	public final boolean hasContent(Minecraft minecraft) {
		// 평소에는 바닐라가 그린다. HudRenderer 는 이 요소를 그리지 않는다.
		return false;
	}

	@Override
	public boolean underlay() {
		return true;
	}

	@Override
	protected final int contentWidth(Minecraft minecraft) {
		return box(minecraft).width();
	}

	@Override
	protected final int contentHeight(Minecraft minecraft) {
		return box(minecraft).height();
	}

	@Override
	public int x(Minecraft minecraft, int screenWidth) {
		if (moved) {
			return super.x(minecraft, screenWidth);
		}

		Box box = box(minecraft);

		// 손대지 않았으면 바닐라가 그리는 자리 그대로다 (화면보다 넓어서 양쪽으로 넘쳐 있어도 그 자리를 가리킨다).
		if (untouched() && screenWidth == box.screenWidth()) {
			return box.x();
		}

		return place(box.x(), box.screenWidth() - box.width(), screenWidth - width(minecraft));
	}

	@Override
	public int y(Minecraft minecraft, int screenHeight) {
		if (moved) {
			return super.y(minecraft, screenHeight);
		}

		Box box = box(minecraft);

		if (untouched() && screenHeight == box.screenHeight()) {
			return box.y();
		}

		return place(box.y(), box.screenHeight() - box.height(), screenHeight - height(minecraft));
	}

	@Override
	public void setPosition(Minecraft minecraft, double x, double y, int screenWidth, int screenHeight) {
		moved = true;
		super.setPosition(minecraft, x, y, screenWidth, screenHeight);
	}

	@Override
	public void resetPosition() {
		moved = false;
		super.resetPosition();
	}

	/** 옮기지 않았으면 크기만 바꿔서 바닐라의 정렬을 유지한다. 옮겼으면 다른 요소처럼 왼쪽 위를 제자리에 둔다. */
	@Override
	public void rescale(Minecraft minecraft, double value, int screenWidth, int screenHeight) {
		if (moved) {
			super.rescale(minecraft, value, screenWidth, screenHeight);
		} else {
			scale.set(value);
		}
	}

	/** 편집 화면이 부른다. 실제 내용은 바닐라가 이미 그렸으므로, 내용이 없을 때만 예시를 그린다. */
	@Override
	protected final void draw(GuiGraphics graphics, Minecraft minecraft) {
		if (isLive(minecraft)) {
			return;
		}

		Box box = box(minecraft);
		graphics.pose().pushPose();
		graphics.pose().translate(-box.x(), -box.y(), 0.0F);
		drawSample(graphics, minecraft);
		graphics.pose().popPose();
	}

	@Override
	public JsonObject toJson() {
		JsonObject json = super.toJson();
		json.addProperty("moved", moved);
		return json;
	}

	@Override
	public void fromJson(JsonObject json) {
		super.fromJson(json);
		JsonElement value = json.get("moved");
		moved = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}
}
