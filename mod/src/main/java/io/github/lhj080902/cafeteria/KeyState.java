package io.github.lhj080902.cafeteria;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 키가 실제로 눌려 있는지 직접 확인한다.
 *
 * <p>바닐라는 키 하나에 키 설정 하나만 연결하기 때문에, 같은 키를 쓰는 설정이 둘이면 한쪽은 눌림이 전달되지 않는다
 * (줌 기본 키 C 는 바닐라의 "핫바 불러오기"와 겹친다). 그래서 "누르는 동안" 동작하는 기능은 KeyMapping#isDown 대신
 * 키보드·마우스 상태를 직접 읽는다.
 */
public final class KeyState {
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
}
