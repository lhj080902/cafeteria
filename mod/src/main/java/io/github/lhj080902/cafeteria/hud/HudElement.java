package io.github.lhj080902.cafeteria.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lhj080902.cafeteria.module.Module;
import io.github.lhj080902.cafeteria.module.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 화면에 떠 있는 HUD 요소. 위치는 "화면에서 요소 크기를 뺀 여유 공간"에 대한 비율(0~1)로 저장해서
 * 창 크기나 GUI 배율이 바뀌어도 화면 밖으로 나가지 않는다.
 */
public abstract class HudElement extends Module {
	public final NumberSetting scale = add(new NumberSetting("scale", 1.0, 0.5, 2.0, 0.1, "x"));

	private final double defaultAnchorX;
	private final double defaultAnchorY;
	private double anchorX;
	private double anchorY;

	protected HudElement(String id, boolean defaultEnabled, double anchorX, double anchorY) {
		super(id, defaultEnabled);
		this.defaultAnchorX = anchorX;
		this.defaultAnchorY = anchorY;
		this.anchorX = anchorX;
		this.anchorY = anchorY;
	}

	/** 배율 적용 전의 내용 크기. */
	protected abstract int contentWidth(Minecraft minecraft);

	protected abstract int contentHeight(Minecraft minecraft);

	/** (0, 0)을 왼쪽 위로 해서 내용을 그린다. 위치·배율은 render() 가 이미 적용해 둔다. */
	protected abstract void draw(GuiGraphics graphics, Minecraft minecraft);

	public int width(Minecraft minecraft) {
		return Math.max(1, Math.round(contentWidth(minecraft) * scale.asFloat()));
	}

	public int height(Minecraft minecraft) {
		return Math.max(1, Math.round(contentHeight(minecraft) * scale.asFloat()));
	}

	public int x(Minecraft minecraft, int screenWidth) {
		return (int) Math.round(anchorX * Math.max(0, screenWidth - width(minecraft)));
	}

	public int y(Minecraft minecraft, int screenHeight) {
		return (int) Math.round(anchorY * Math.max(0, screenHeight - height(minecraft)));
	}

	public void setPosition(Minecraft minecraft, double x, double y, int screenWidth, int screenHeight) {
		int freeX = screenWidth - width(minecraft);
		int freeY = screenHeight - height(minecraft);
		anchorX = freeX <= 0 ? 0.0 : Mth.clamp(x / freeX, 0.0, 1.0);
		anchorY = freeY <= 0 ? 0.0 : Mth.clamp(y / freeY, 0.0, 1.0);
	}

	public void resetPosition() {
		anchorX = defaultAnchorX;
		anchorY = defaultAnchorY;
	}

	public final void render(GuiGraphics graphics, Minecraft minecraft) {
		float factor = scale.asFloat();
		graphics.pose().pushPose();
		graphics.pose().translate(x(minecraft, graphics.guiWidth()), y(minecraft, graphics.guiHeight()), 0.0F);
		graphics.pose().scale(factor, factor, 1.0F);
		draw(graphics, minecraft);
		graphics.pose().popPose();
	}

	@Override
	public JsonObject toJson() {
		JsonObject json = super.toJson();
		json.addProperty("x", anchorX);
		json.addProperty("y", anchorY);
		return json;
	}

	@Override
	public void fromJson(JsonObject json) {
		super.fromJson(json);
		anchorX = readAnchor(json.get("x"), defaultAnchorX);
		anchorY = readAnchor(json.get("y"), defaultAnchorY);
	}

	private static double readAnchor(JsonElement json, double fallback) {
		if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}

		double value = json.getAsDouble();
		return Double.isNaN(value) ? fallback : Mth.clamp(value, 0.0, 1.0);
	}
}
