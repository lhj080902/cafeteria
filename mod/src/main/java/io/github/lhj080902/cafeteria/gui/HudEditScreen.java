package io.github.lhj080902.cafeteria.gui;

import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.hud.HudElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 위치 편집 화면.
 * <ul>
 *   <li>끌기: 이동. Shift 를 누른 채 끌면 화면 가장자리·가운데, 다른 요소의 가장자리·가운데에 맞춰 붙는다.</li>
 *   <li>방향키: 선택한 요소를 1픽셀씩 이동 (Shift 와 함께 누르면 10픽셀).</li>
 *   <li>휠: 크기 조절. 오른쪽 클릭: 그 요소의 설정 열기.</li>
 * </ul>
 */
public class HudEditScreen extends Screen {
	/** 이 거리(픽셀) 안으로 들어오면 안내선에 붙는다. */
	private static final int SNAP_DISTANCE = 6;
	private static final int NO_GUIDE = Integer.MIN_VALUE;

	private final Screen parent;
	private HudElement selected;
	private HudElement dragging;
	private double grabOffsetX;
	private double grabOffsetY;
	/** 지금 붙어 있는 안내선의 위치. 없으면 NO_GUIDE. */
	private int guideX = NO_GUIDE;
	private int guideY = NO_GUIDE;

	public HudEditScreen(Screen parent) {
		super(Component.translatable("cafeteria.hud_edit.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// 이 화면이 떠 있는 동안에는 비어 있는 요소(콤보, 갑옷 등)도 예시 내용으로 보여서 위치를 잡을 수 있다.
		HudElement.setEditing(true);
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("cafeteria.hud_edit.reset"), button -> Modules.HUD.forEach(HudElement::resetPosition))
			.bounds(width / 2 - 104, y, 100, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(width / 2 + 4, y, 100, 20).build());
	}

	@Override
	public void removed() {
		HudElement.setEditing(false);
		dragging = null;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 게임 화면 위에 놓일 위치를 그대로 볼 수 있도록 배경을 흐리거나 어둡게 하지 않는다.
		if (minecraft.level == null) {
			super.renderBackground(graphics, mouseX, mouseY, partialTick);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		renderHint(graphics);

		for (HudElement element : enabledElements()) {
			element.render(graphics, minecraft);
			int color = element == selected ? CafeteriaScreen.ACCENT : contains(element, mouseX, mouseY) && dragging == null ? 0xFFFFFFFF : 0x80FFFFFF;
			graphics.renderOutline(element.x(minecraft, width) - 1, element.y(minecraft, height) - 1, element.width(minecraft) + 2, element.height(minecraft) + 2, color);
		}

		if (dragging != null) {
			if (guideX != NO_GUIDE) {
				int x = Math.min(guideX, width - 1);
				graphics.fill(x, 0, x + 1, height, CafeteriaScreen.ACCENT);
			}

			if (guideY != NO_GUIDE) {
				int y = Math.min(guideY, height - 1);
				graphics.fill(0, y, width, y + 1, CafeteriaScreen.ACCENT);
			}
		}

		if (selected != null && selected.isEnabled()) {
			renderSelectionInfo(graphics);
		}
	}

	private void renderHint(GuiGraphics graphics) {
		Component first = Component.translatable("cafeteria.hud_edit.hint");
		Component second = Component.translatable("cafeteria.hud_edit.hint2");
		int hintWidth = Math.max(font.width(first), font.width(second));
		graphics.fill(width / 2 - hintWidth / 2 - 6, 6, width / 2 + hintWidth / 2 + 6, 33, 0xA0000000);
		graphics.drawCenteredString(font, first, width / 2, 10, 0xFFFFFFFF);
		graphics.drawCenteredString(font, second, width / 2, 21, 0xFFC0C0C0);
	}

	/** 선택한 요소의 이름, 좌표, 크기를 요소 바로 위(자리가 없으면 아래)에 보여 준다. */
	private void renderSelectionInfo(GuiGraphics graphics) {
		int x = selected.x(minecraft, width);
		int y = selected.y(minecraft, height);
		String info = selected.name().getString() + "  " + x + ", " + y + "  " + Math.round(selected.scale.get() * 100.0) + "%";
		int infoWidth = font.width(info) + 6;
		int infoX = Math.max(0, Math.min(x - 1, width - infoWidth));
		int infoY = y - 13 >= 0 ? y - 13 : y + selected.height(minecraft) + 2;
		graphics.fill(infoX, infoY, infoX + infoWidth, infoY + 11, 0xE0000000);
		graphics.drawString(font, info, infoX + 3, infoY + 2, CafeteriaScreen.ACCENT, false);
	}

	private List<HudElement> enabledElements() {
		List<HudElement> elements = new ArrayList<>();

		for (HudElement element : Modules.HUD) {
			if (element.isEnabled()) {
				elements.add(element);
			}
		}

		return elements;
	}

	private boolean contains(HudElement element, double mouseX, double mouseY) {
		int x = element.x(minecraft, width);
		int y = element.y(minecraft, height);
		return mouseX >= x && mouseX < x + element.width(minecraft) && mouseY >= y && mouseY < y + element.height(minecraft);
	}

	/** 마우스 아래에 있는 요소 중 가장 위에 그려진 것. */
	private HudElement elementAt(double mouseX, double mouseY) {
		List<HudElement> elements = enabledElements();

		for (int i = elements.size() - 1; i >= 0; i--) {
			if (contains(elements.get(i), mouseX, mouseY)) {
				return elements.get(i);
			}
		}

		return null;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		HudElement element = elementAt(mouseX, mouseY);

		if (button == 0) {
			selected = element;
			dragging = element;

			if (element != null) {
				grabOffsetX = mouseX - element.x(minecraft, width);
				grabOffsetY = mouseY - element.y(minecraft, height);
			}

			return element != null;
		}

		if (button == 1 && element != null) {
			selected = element;
			minecraft.setScreen(new ModuleSettingsScreen(this, element));
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (dragging == null || button != 0) {
			return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
		}

		double x = mouseX - grabOffsetX;
		double y = mouseY - grabOffsetY;
		guideX = NO_GUIDE;
		guideY = NO_GUIDE;

		if (hasShiftDown()) {
			x = snap(x, dragging.width(minecraft), true);
			y = snap(y, dragging.height(minecraft), false);
		}

		dragging.setPosition(minecraft, x, y, width, height);
		return true;
	}

	/**
	 * 요소의 왼쪽·가운데·오른쪽(세로 방향이면 위·가운데·아래)이 안내선 근처에 오면 그 선에 붙인 위치를 돌려준다.
	 * 안내선: 화면의 양 끝과 가운데, 다른 요소의 양 끝과 가운데.
	 */
	private double snap(double position, int size, boolean horizontal) {
		List<Double> lines = new ArrayList<>();
		int screenSize = horizontal ? width : height;
		lines.add(0.0);
		lines.add(screenSize / 2.0);
		lines.add((double) screenSize);

		for (HudElement other : enabledElements()) {
			if (other == dragging) {
				continue;
			}

			int start = horizontal ? other.x(minecraft, width) : other.y(minecraft, height);
			int otherSize = horizontal ? other.width(minecraft) : other.height(minecraft);
			lines.add((double) start);
			lines.add(start + otherSize / 2.0);
			lines.add((double) (start + otherSize));
		}

		double closest = SNAP_DISTANCE + 1.0;
		double snapped = position;
		int guide = NO_GUIDE;

		for (double line : lines) {
			for (double offset : new double[] {0.0, size / 2.0, size}) {
				double distance = Math.abs(position + offset - line);

				if (distance < closest) {
					closest = distance;
					snapped = line - offset;
					guide = (int) Math.round(line);
				}
			}
		}

		if (horizontal) {
			guideX = guide;
		} else {
			guideY = guide;
		}

		return snapped;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging != null && button == 0) {
			dragging = null;
			guideX = NO_GUIDE;
			guideY = NO_GUIDE;
			return true;
		}

		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		HudElement element = elementAt(mouseX, mouseY);

		if (element == null || scrollY == 0.0) {
			return false;
		}

		selected = element;
		// 크기를 바꿔도 요소의 왼쪽 위 모서리는 제자리에 있게 한다.
		int x = element.x(minecraft, width);
		int y = element.y(minecraft, height);
		element.scale.set(element.scale.get() + Math.signum(scrollY) * 0.1);
		element.setPosition(minecraft, x, y, width, height);
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (selected != null && selected.isEnabled()) {
			int step = hasShiftDown() ? 10 : 1;
			int deltaX = keyCode == GLFW.GLFW_KEY_LEFT ? -step : keyCode == GLFW.GLFW_KEY_RIGHT ? step : 0;
			int deltaY = keyCode == GLFW.GLFW_KEY_UP ? -step : keyCode == GLFW.GLFW_KEY_DOWN ? step : 0;

			if (deltaX != 0 || deltaY != 0) {
				selected.setPosition(minecraft, selected.x(minecraft, width) + deltaX, selected.y(minecraft, height) + deltaY, width, height);
				return true;
			}
		}

		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		ConfigManager.save();
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
