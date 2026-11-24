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
 *   <li>꼭짓점 손잡이를 끌기: 크기 조절 (1% 단위). 반대쪽 꼭짓점은 제자리에 있다.</li>
 *   <li>휠: 크기 조절 (10% 단위). 오른쪽 클릭: 그 요소의 설정 열기.</li>
 * </ul>
 */
public class HudEditScreen extends Screen {
	/** 이 거리(픽셀) 안으로 들어오면 안내선에 붙는다. */
	private static final int SNAP_DISTANCE = 6;
	private static final int NO_GUIDE = Integer.MIN_VALUE;
	private static final int BOX_FILL = 0x33F2A65A;
	/** 꼭짓점 손잡이의 한 변(픽셀). 테두리의 꼭짓점을 가운데에 두고 그리며, 그려진 네모가 곧 누를 수 있는 영역이다. */
	private static final int HANDLE_SIZE = 5;
	/** 꼭짓점의 방향 (가로, 세로). -1 은 왼쪽·위, 1 은 오른쪽·아래. */
	private static final int[][] CORNERS = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
	/** 옮길 수 없는 요소는 왼쪽 아래가 고정이라 오른쪽 위 손잡이만 있다. */
	private static final int[][] TOP_RIGHT_CORNER = {{1, -1}};

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

	/** 꼭짓점 손잡이를 잡고 크기를 바꾸는 요소. */
	private HudElement resizing;
	/** 크기를 바꾸는 동안 제자리에 있는 반대쪽 꼭짓점. */
	private int resizeFixedX;
	private int resizeFixedY;
	/** 고정된 꼭짓점에서 잡은 꼭짓점 쪽으로의 방향 (1 또는 -1). */
	private int resizeDirectionX;
	private int resizeDirectionY;
	/** 누른 순간 마우스와 꼭짓점이 어긋난 만큼. 끄는 동안 이만큼을 빼서 손잡이가 튀지 않게 한다. */
	private double resizeGrabX;
	private double resizeGrabY;

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
		resizing = null;
		boxSelecting = false;
	}

	/** 지금 선택돼 있는 요소들 (선택한 순서). */
	public List<HudElement> selection() {
		return List.copyOf(selection);
	}

	/** 꼭짓점 손잡이를 끄는 중인지. */
	public boolean isResizing() {
		return resizing != null;
	}

	/**
	 * 꼭짓점을 끈 거리로 배율을 구한다. 거리는 고정된 반대쪽 꼭짓점에서 잰 것이고 끄는 방향이 양수다.
	 * 가로·세로 중 더 많이 끈 쪽을 따르고(비율 유지), 화면 여유(room)와 설정 범위를 넘지 않는다. 결과는 1% 단위.
	 */
	public static double resizeScale(double distanceX, double distanceY, int baseWidth, int baseHeight, double roomX, double roomY, double min, double max) {
		int baseX = Math.max(1, baseWidth);
		int baseY = Math.max(1, baseHeight);
		double wanted = Math.round(Math.max(distanceX / baseX, distanceY / baseY) * 100.0) / 100.0;
		// 여유는 1% 단위로 내려서, 반올림 때문에 화면 밖으로 1픽셀 나가는 일이 없게 한다.
		double room = Math.floor(Math.min(roomX / baseX, roomY / baseY) * 100.0 + 1.0e-9) / 100.0;
		return Math.max(min, Math.min(max, Math.min(wanted, room)));
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
		boolean busy = dragging != null || boxSelecting || resizing != null;
		List<HudElement> elements = enabledElements();

		for (HudElement element : elements) {
			element.render(graphics, minecraft);
			int color = selection.contains(element) ? CafeteriaScreen.ACCENT : contains(element, mouseX, mouseY) && !busy ? 0xFFFFFFFF : 0x80FFFFFF;
			graphics.renderOutline(element.x(minecraft, width) - 1, element.y(minecraft, height) - 1, element.width(minecraft) + 2, element.height(minecraft) + 2, color);
		}

		// 손잡이는 다른 요소에 가리지 않게 요소를 모두 그린 뒤에 그린다.
		Handle hovered = busy ? null : handleAt(mouseX, mouseY);

		for (HudElement element : elements) {
			boolean pointed = !busy && (contains(element, mouseX, mouseY) || hovered != null && hovered.element() == element);

			if (element == resizing || resizing == null && (selection.contains(element) || pointed)) {
				renderHandles(graphics, element, hovered);
			}
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

	private void renderHandles(GuiGraphics graphics, HudElement element, Handle hovered) {
		for (int[] corner : corners(element)) {
			int left = handleLeft(element, corner[0]);
			int top = handleTop(element, corner[1]);
			boolean active = element == resizing ? corner[0] == resizeDirectionX && corner[1] == resizeDirectionY
				: hovered != null && hovered.element() == element && hovered.directionX() == corner[0] && hovered.directionY() == corner[1];
			graphics.fill(left, top, left + HANDLE_SIZE, top + HANDLE_SIZE, 0xFF000000);
			graphics.fill(left + 1, top + 1, left + HANDLE_SIZE - 1, top + HANDLE_SIZE - 1, active ? CafeteriaScreen.ACCENT : 0xFFFFFFFF);
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
		// 크기를 바꾸는 동안에는 여러 개를 선택했더라도 잡은 요소의 크기를 보여 준다.
		Set<HudElement> shown = resizing != null ? Set.of(resizing) : selection;
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int bottom = Integer.MIN_VALUE;

		for (HudElement element : shown) {
			left = Math.min(left, element.x(minecraft, width));
			top = Math.min(top, element.y(minecraft, height));
			bottom = Math.max(bottom, element.y(minecraft, height) + element.height(minecraft));
		}

		String info;

		if (shown.size() == 1) {
			HudElement only = shown.iterator().next();
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

	/** 꼭짓점 손잡이 하나: 어느 요소의 어느 쪽 꼭짓점인지. */
	private record Handle(HudElement element, int directionX, int directionY) {
	}

	private static int[][] corners(HudElement element) {
		return element.movable() ? CORNERS : TOP_RIGHT_CORNER;
	}

	/** 손잡이 네모의 왼쪽 좌표. 테두리(요소보다 1픽셀 바깥)의 꼭짓점이 네모 가운데에 온다. */
	private int handleLeft(HudElement element, int directionX) {
		int x = element.x(minecraft, width);
		return (directionX > 0 ? x + element.width(minecraft) : x - 1) - HANDLE_SIZE / 2;
	}

	private int handleTop(HudElement element, int directionY) {
		int y = element.y(minecraft, height);
		return (directionY > 0 ? y + element.height(minecraft) : y - 1) - HANDLE_SIZE / 2;
	}

	/** 마우스 아래의 손잡이. 선택한 요소의 손잡이가 먼저고, 그다음은 위에 그려진 요소 순서다. */
	private Handle handleAt(double mouseX, double mouseY) {
		List<HudElement> elements = enabledElements();

		for (boolean selected : new boolean[] {true, false}) {
			for (int i = elements.size() - 1; i >= 0; i--) {
				HudElement element = elements.get(i);

				if (selection.contains(element) != selected) {
					continue;
				}

				for (int[] corner : corners(element)) {
					int left = handleLeft(element, corner[0]);
					int top = handleTop(element, corner[1]);

					if (mouseX >= left && mouseX < left + HANDLE_SIZE && mouseY >= top && mouseY < top + HANDLE_SIZE) {
						return new Handle(element, corner[0], corner[1]);
					}
				}
			}
		}

		return null;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		if (button == 0) {
			// 손잡이는 요소 가장자리에 걸쳐 있어서, 이동이나 박스 선택보다 먼저 확인한다.
			Handle handle = handleAt(mouseX, mouseY);

			if (handle != null) {
				startResize(handle, mouseX, mouseY);
				return true;
			}
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

	/** 손잡이를 잡는다. 선택 밖의 요소면 그것만 선택하고, 선택 안의 요소면 선택은 그대로 두고 그 요소만 바꾼다. */
	private void startResize(Handle handle, double mouseX, double mouseY) {
		HudElement element = handle.element();

		if (!selection.contains(element)) {
			selectOnly(element);
		}

		int left = element.x(minecraft, width);
		int top = element.y(minecraft, height);
		int right = left + element.width(minecraft);
		int bottom = top + element.height(minecraft);
		resizing = element;
		resizeDirectionX = handle.directionX();
		resizeDirectionY = handle.directionY();
		resizeFixedX = resizeDirectionX > 0 ? left : right;
		resizeFixedY = resizeDirectionY > 0 ? top : bottom;
		resizeGrabX = mouseX - (resizeDirectionX > 0 ? right : left);
		resizeGrabY = mouseY - (resizeDirectionY > 0 ? bottom : top);
	}

	/** 잡은 꼭짓점이 마우스를 따라오도록 크기를 바꾸고, 반대쪽 꼭짓점이 제자리에 있게 위치를 맞춘다. */
	private void resizeTo(double mouseX, double mouseY) {
		double distanceX = (mouseX - resizeGrabX - resizeFixedX) * resizeDirectionX;
		double distanceY = (mouseY - resizeGrabY - resizeFixedY) * resizeDirectionY;
		double roomX = resizeDirectionX > 0 ? width - resizeFixedX : resizeFixedX;
		double roomY = resizeDirectionY > 0 ? height - resizeFixedY : resizeFixedY;
		resizing.scale.set(resizeScale(distanceX, distanceY, resizing.baseWidth(minecraft), resizing.baseHeight(minecraft),
			roomX, roomY, resizing.scale.min(), resizing.scale.max()));
		int x = resizeDirectionX > 0 ? resizeFixedX : resizeFixedX - resizing.width(minecraft);
		int y = resizeDirectionY > 0 ? resizeFixedY : resizeFixedY - resizing.height(minecraft);
		resizing.setPosition(minecraft, x, y, width, height);
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

		if (resizing != null) {
			resizeTo(mouseX, mouseY);
			return true;
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
		if (button == 0 && resizing != null) {
			resizing = null;
			return true;
		}

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
			element.rescale(minecraft, element.scale.get() + Math.signum(scrollY) * 0.1, width, height);
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

		if (!selection.isEmpty() && dragging == null && resizing == null) {
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
