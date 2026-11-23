package io.github.lhj080902.cafeteria;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Cafeteria 키가 눌렸는지를 바닐라 키 설정과 따로 확인한다.
 *
 * <p>바닐라는 키 하나에 키 설정 하나만 연결하기 때문에, 같은 키를 쓰는 설정이 둘이면 한쪽은 눌림이 전달되지 않는다.
 * 줌 기본 키 C 는 바닐라의 "핫바 불러오기"와 겹치고, 다른 모드(미니맵 등)가 같은 키를 쓰는 경우도 흔하다.
 * 그래서 누르고 있는 상태는 키보드·마우스를 직접 읽고, 한 번 누름은 입력 이벤트(KeyboardHandlerMixin,
 * MouseHandlerMixin)에서 직접 센다. 이렇게 하면 키가 겹쳐도 Cafeteria 기능은 항상 동작한다.
 */
public final class KeyState {
	/** 한 번 누름을 세는 키 설정 → 아직 처리하지 않은 누름 횟수 */
	private static final Map<KeyMapping, Integer> CLICKS = new IdentityHashMap<>();

	private KeyState() {
	}

	/** 게임 화면(GUI가 열려 있지 않은 상태)에서 이 키 설정에 연결된 키가 눌려 있으면 true. */
	public static boolean isHeld(KeyMapping mapping) {
		Minecraft minecraft = Minecraft.getInstance();

		if (mapping == null || minecraft.screen != null || minecraft.getOverlay() != null || !minecraft.isWindowActive()) {
			return false;
		}

		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
		long window = minecraft.getWindow().getWindow();

		if (key.getValue() < 0) {
			return false;
		}

		return switch (key.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
			case MOUSE -> GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
			// 스캔 코드로만 지정된 키는 직접 읽을 방법이 없어 바닐라 상태를 따른다.
			case SCANCODE -> mapping.isDown();
		};
	}

	/** 이 키 설정의 "한 번 누름"을 세기 시작한다. */
	public static void track(KeyMapping mapping) {
		CLICKS.put(mapping, 0);
	}

	/** 처리하지 않은 누름이 있으면 하나를 소비하고 true 를 돌려준다. track() 으로 등록한 키 설정에만 쓸 수 있다. */
	public static boolean consumeClick(KeyMapping mapping) {
		Integer pending = CLICKS.get(mapping);

		if (pending == null || pending == 0) {
			return false;
		}

		CLICKS.put(mapping, pending - 1);
		return true;
	}

	/** 쌓여 있는 누름을 모두 버린다 (기능이 꺼져 있는 동안 눌린 것이 나중에 한꺼번에 처리되지 않게). */
	public static void clearClicks(KeyMapping mapping) {
		if (CLICKS.containsKey(mapping)) {
			CLICKS.put(mapping, 0);
		}
	}

	/** 키보드 입력 이벤트. action 은 GLFW 의 PRESS / RELEASE / REPEAT. */
	public static void onKeyEvent(int keyCode, int scanCode, int action) {
		if (action == GLFW.GLFW_PRESS) {
			countClicks(InputConstants.Type.KEYSYM, keyCode, scanCode);
		}
	}

	/** 마우스 버튼 입력 이벤트. */
	public static void onMouseButtonEvent(int button, int action) {
		if (action == GLFW.GLFW_PRESS) {
			countClicks(InputConstants.Type.MOUSE, button, -1);
		}
	}

	private static void countClicks(InputConstants.Type type, int code, int scanCode) {
		Minecraft minecraft = Minecraft.getInstance();

		// 채팅이나 메뉴가 열려 있을 때 누른 키는 글자 입력이므로 세지 않는다.
		if (minecraft.screen != null || minecraft.getOverlay() != null) {
			return;
		}

		for (Map.Entry<KeyMapping, Integer> entry : CLICKS.entrySet()) {
			InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(entry.getKey());
			boolean matches = switch (bound.getType()) {
				case KEYSYM -> type == InputConstants.Type.KEYSYM && bound.getValue() == code;
				case SCANCODE -> type == InputConstants.Type.KEYSYM && bound.getValue() == scanCode;
				case MOUSE -> type == InputConstants.Type.MOUSE && bound.getValue() == code;
			};

			if (matches && bound.getValue() >= 0) {
				entry.setValue(entry.getValue() + 1);
			}
		}
	}
}
