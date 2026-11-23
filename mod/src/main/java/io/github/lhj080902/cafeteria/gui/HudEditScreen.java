package io.github.lhj080902.cafeteria.gui;

import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.hud.HudElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HUD 위치 편집 화면.
 * <ul>
 *   <li>끌기: 이동. Shift 를 누른 채 끌면 화면 가장자리·가운데, 다른 요소의 가장자리·가운데에 맞춰 붙는다.</li>
 *   <li>빈 곳을 끌기: 박스를 그려서 닿은 요소를 한꺼번에 선택. 선택한 요소들은 함께 움직인다.</li>
 *   <li>Ctrl+클릭: 선택에 추가/해제. Ctrl+A: 전체 선택.</li>
 *   <li>방향키: 선택한 요소를 1픽셀씩 이동 (Shift 와 함께 누르면 10픽셀).</li>
 *   <li>휠: 크기 조절. 오른쪽 클릭: 그 요소의 설정 열기.</li>
 * </ul>
 */
public class HudEditScreen extends Screen {
	/** 이 거리(픽셀) 안으로 들어오면 안내선에 붙는다. */
	private static final int SNAP_DISTANCE = 6;
	private static final int NO_GUIDE = Integer.MIN_VALUE;
	private static final int BOX_FILL = 0x33F2A65A;

	private final Screen parent;
	/** 선택한 요소들 (선택한 순서). */
	private final Set<HudElement> selection = new LinkedHashSet<>();

	/** 마우스로 잡고 끄는 요소. 선택한 다른 요소들도 같은 만큼 함께 움직인다. */
	private HudElement dragging;
	private double grabOffsetX;
	private double grabOffsetY;
	/** 끌기 시작한 순간의 선택 요소 위치. 이동량은 항상 이 위치를 기준으로 계산한다. */
	private final Map<HudElement, int[]> dragStart = new LinkedHashMap<>();
	private boolean dragMoved;
	/** 여러 개 선택한 상태에서 그중 하나를 끌지 않고 눌렀다 떼면 그 요소만 선택한다. */
	private boolean collapseOnRelease;

	/** 박스 선택 중인지와 박스의 두 모서리. */
	private boolean boxSelecting;
	private double boxStartX;
	private double boxStartY;
	private double boxEndX;
	private double boxEndY;
	/** Ctrl 을 누른 채 박스를 그리기 시작했을 때 이미 선택돼 있던 요소들. */
	private final Set<HudElement> boxBase = new LinkedHashSet<>();

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
		boxSelecting = false;
	}

	/** 지금 선택돼 있는 요소들 (선택한 순서). */
	public List<HudElement> selection() {
		return List.copyOf(selection);
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
		selection.removeIf(element -> !element.isEnabled());
		boolean busy = dragging != null || boxSelecting;

		for (HudElement element : enabledElements()) {
			element.render(graphics, minecraft);
			int color = selection.contains(element) ? CafeteriaScreen.ACCENT : contains(element, mouseX, mouseY) && !busy ? 0xFFFFFFFF : 0x80FFFFFF;
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

		if (boxSelecting) {
			int[] box = box();
			graphics.fill(box[0], box[1], box[2], box[3], BOX_FILL);
			graphics.renderOutline(box[0], box[1], Math.max(1, box[2] - box[0]), Math.max(1, box[3] - box[1]), CafeteriaScreen.ACCENT);
		}

		if (!selection.isEmpty()) {
			renderSelectionInfo(graphics);
		}
	}

	private void renderHint(GuiGraphics graphics) {
		List<Component> lines = List.of(
			Component.translatable("cafeteria.hud_edit.hint"),
			Component.translatable("cafeteria.hud_edit.hint2"),
			Component.translatable("cafeteria.hud_edit.hint3"));
		int hintWidth = 0;

		for (Component line : lines) {
			hintWidth = Math.max(hintWidth, font.width(line));
		}

		graphics.fill(width / 2 - hintWidth / 2 - 6, 6, width / 2 + hintWidth / 2 + 6, 11 + lines.size() * 11, 0xA0000000);

		for (int i = 0; i < lines.size(); i++) {
			graphics.drawCenteredString(font, lines.get(i), width / 2, 10 + i * 11, i == 0 ? 0xFFFFFFFF : 0xFFC0C0C0);
		}
	}

	/**
	 * 선택 정보를 선택한 요소(여러 개면 전체를 감싸는 영역) 바로 위에 보여 준다. 자리가 없으면 아래에 보여 준다.
	 * 하나를 골랐으면 이름·좌표·크기, 여러 개면 개수.
	 */
	private void renderSelectionInfo(GuiGraphics graphics) {
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int bottom = Integer.MIN_VALUE;

		for (HudElement element : selection) {
			left = Math.min(left, element.x(minecraft, width));
			top = Math.min(top, element.y(minecraft, height));
			bottom = Math.max(bottom, element.y(minecraft, height) + element.height(minecraft));
		}

		String info;

		if (selection.size() == 1) {
			HudElement only = selection.iterator().next();
			info = only.name().getString() + "  " + left + ", " + top + "  " + Math.round(only.scale.get() * 100.0) + "%";
		} else {
			info = Component.translatable("cafeteria.hud_edit.selected_count", selection.size()).getString();
		}

		int infoWidth = font.width(info) + 6;
		int infoX = Math.max(0, Math.min(left - 1, width - infoWidth));
		int infoY = top - 13 >= 0 ? top - 13 : bottom + 2;
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

	private void selectOnly(HudElement element) {
		selection.clear();
		selection.add(element);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		HudElement element = elementAt(mouseX, mouseY);

		if (button == 0) {
			if (element == null) {
				startBoxSelection(mouseX, mouseY);
				return true;
			}

			collapseOnRelease = false;

			if (hasControlDown()) {
				// Ctrl+클릭: 선택에 넣거나 뺀다. 뺀 요소는 끌지 않는다.
				if (selection.remove(element)) {
					return true;
				}

				selection.add(element);
			} else if (selection.contains(element)) {
				collapseOnRelease = selection.size() > 1;
			} else {
				selectOnly(element);
			}

			startDrag(element, mouseX, mouseY);
			return true;
		}

		if (button == 1 && element != null) {
			selectOnly(element);
			minecraft.setScreen(new ModuleSettingsScreen(this, element));
			return true;
		}

		return false;
	}

	private void startBoxSelection(double mouseX, double mouseY) {
		if (!hasControlDown()) {
			selection.clear();
		}

		boxBase.clear();
		boxBase.addAll(selection);
		boxSelecting = true;
		boxStartX = mouseX;
		boxStartY = mouseY;
		boxEndX = mouseX;
		boxEndY = mouseY;
	}

	private void startDrag(HudElement element, double mouseX, double mouseY) {
		dragging = element;
		dragMoved = false;
		grabOffsetX = mouseX - element.x(minecraft, width);
		grabOffsetY = mouseY - element.y(minecraft, height);
		dragStart.clear();
		dragStart.putAll(positions());
	}

	/** 선택한 요소들의 현재 위치. */
	private Map<HudElement, int[]> positions() {
		Map<HudElement, int[]> positions = new LinkedHashMap<>();

		for (HudElement element : selection) {
			positions.put(element, new int[] {element.x(minecraft, width), element.y(minecraft, height)});
		}

		return positions;
	}

	/** 박스의 왼쪽·위·오른쪽·아래 좌표. */
	private int[] box() {
		return new int[] {
			Mth.floor(Math.min(boxStartX, boxEndX)), Mth.floor(Math.min(boxStartY, boxEndY)),
			Mth.ceil(Math.max(boxStartX, boxEndX)), Mth.ceil(Math.max(boxStartY, boxEndY))
		};
	}

	/** 박스에 조금이라도 닿은 요소를 선택한다. */
	private void updateBoxSelection() {
		int[] box = box();
		selection.clear();
		selection.addAll(boxBase);

		for (HudElement element : enabledElements()) {
			int x = element.x(minecraft, width);
			int y = element.y(minecraft, height);

			if (x < box[2] && x + element.width(minecraft) > box[0] && y < box[3] && y + element.height(minecraft) > box[1]) {
				selection.add(element);
			}
		}
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (button != 0) {
			return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
		}

		if (boxSelecting) {
			boxEndX = mouseX;
			boxEndY = mouseY;
			updateBoxSelection();
			return true;
		}

		if (dragging == null) {
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

		dragMoved = true;
		int[] origin = dragStart.get(dragging);
		moveFrom(dragStart, (int) Math.round(x) - origin[0], (int) Math.round(y) - origin[1]);
		return true;
	}

	/**
	 * 요소들을 기준 위치에서 (deltaX, deltaY) 만큼 옮긴다.
	 * 묶음 전체가 화면 안에 머물도록 이동량을 줄여서, 가장자리에 닿아도 요소 사이의 간격이 유지된다.
	 */
	private void moveFrom(Map<HudElement, int[]> origins, int deltaX, int deltaY) {
		int minX = Integer.MIN_VALUE;
		int maxX = Integer.MAX_VALUE;
		int minY = Integer.MIN_VALUE;
		int maxY = Integer.MAX_VALUE;

		for (Map.Entry<HudElement, int[]> entry : origins.entrySet()) {
			HudElement element = entry.getKey();
			int[] origin = entry.getValue();
			minX = Math.max(minX, -origin[0]);
			maxX = Math.min(maxX, width - element.width(minecraft) - origin[0]);
			minY = Math.max(minY, -origin[1]);
			maxY = Math.min(maxY, height - element.height(minecraft) - origin[1]);
		}

		// 화면보다 큰 요소가 섞여 있으면 범위가 뒤집힐 수 있다. 그럴 때는 왼쪽·위쪽 기준을 따른다.
		int limitedX = Math.max(minX, Math.min(deltaX, maxX));
		int limitedY = Math.max(minY, Math.min(deltaY, maxY));

		for (Map.Entry<HudElement, int[]> entry : origins.entrySet()) {
			int[] origin = entry.getValue();
			entry.getKey().setPosition(minecraft, origin[0] + limitedX, origin[1] + limitedY, width, height);
		}
	}

	/**
	 * 요소의 왼쪽·가운데·오른쪽(세로 방향이면 위·가운데·아래)이 안내선 근처에 오면 그 선에 붙인 위치를 돌려준다.
	 * 안내선: 화면의 양 끝과 가운데, 함께 움직이지 않는 다른 요소의 양 끝과 가운데.
	 */
	private double snap(double position, int size, boolean horizontal) {
		List<Double> lines = new ArrayList<>();
		int screenSize = horizontal ? width : height;
		lines.add(0.0);
		lines.add(screenSize / 2.0);
		lines.add((double) screenSize);

		for (HudElement other : enabledElements()) {
			if (selection.contains(other)) {
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
		if (button == 0 && boxSelecting) {
			boxSelecting = false;
			boxBase.clear();
			return true;
		}

		if (button == 0 && dragging != null) {
			if (!dragMoved && collapseOnRelease) {
				selectOnly(dragging);
			}

			dragging = null;
			dragStart.clear();
			guideX = NO_GUIDE;
			guideY = NO_GUIDE;
			return true;
		}

		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		HudElement hovered = elementAt(mouseX, mouseY);

		if (hovered == null || scrollY == 0.0) {
			return false;
		}

		// 선택한 묶음 위에서 굴리면 묶음 전체의 크기를, 그 밖의 요소 위에서 굴리면 그 요소만 바꾼다.
		if (!selection.contains(hovered)) {
			selectOnly(hovered);
		}

		for (HudElement element : selection) {
			// 크기를 바꿔도 요소의 왼쪽 위 모서리는 제자리에 있게 한다.
			int x = element.x(minecraft, width);
			int y = element.y(minecraft, height);
			element.scale.set(element.scale.get() + Math.signum(scrollY) * 0.1);
			element.setPosition(minecraft, x, y, width, height);
		}

		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (isSelectAll(keyCode)) {
			selection.clear();
			selection.addAll(enabledElements());
			return true;
		}

		if (!selection.isEmpty() && dragging == null) {
			int step = hasShiftDown() ? 10 : 1;
			int deltaX = keyCode == GLFW.GLFW_KEY_LEFT ? -step : keyCode == GLFW.GLFW_KEY_RIGHT ? step : 0;
			int deltaY = keyCode == GLFW.GLFW_KEY_UP ? -step : keyCode == GLFW.GLFW_KEY_DOWN ? step : 0;

			if (deltaX != 0 || deltaY != 0) {
				moveFrom(positions(), deltaX, deltaY);
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
