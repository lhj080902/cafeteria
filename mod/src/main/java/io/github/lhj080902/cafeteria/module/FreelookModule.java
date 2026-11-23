package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.KeyState;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 360° 프리룩. 키를 누르는 동안 캐릭터가 바라보는 방향은 그대로 두고 카메라만 3인칭으로 자유롭게 돌린다.
 * 마우스 입력은 MouseHandlerMixin 이 가로채서 turn() 으로 넘기고, CameraMixin 이 yaw()/pitch() 를 카메라에 적용한다.
 */
public class FreelookModule extends Module {
	public final ModeSetting mode = add(new ModeSetting("freelook_mode", "hold", "hold", "toggle"));

	private boolean active;
	private float yaw;
	private float pitch;
	private CameraType previousCamera = CameraType.FIRST_PERSON;

	public FreelookModule() {
		super("freelook", true);
	}

	public boolean isActive() {
		return active;
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}

	/** 바닐라 Entity#turn 과 같은 감도(0.15)로 카메라 각도만 바꾼다. */
	public void turn(double deltaYaw, double deltaPitch) {
		yaw += (float) deltaYaw * 0.15F;
		pitch = Mth.clamp(pitch + (float) deltaPitch * 0.15F, -90.0F, 90.0F);
	}

	@Override
	public void tick(Minecraft minecraft) {
		KeyMapping key = CafeteriaClient.freelookKey;

		if (key == null) {
			return;
		}

		if (!isEnabled() || minecraft.player == null) {
			KeyState.clearClicks(key);
			stop(minecraft);
			return;
		}

		boolean wanted;

		if (mode.is("toggle")) {
			wanted = active;

			while (KeyState.consumeClick(key)) {
				wanted = !wanted;
			}
		} else {
			wanted = KeyState.isHeld(key);
			KeyState.clearClicks(key);
		}

		if (wanted) {
			start(minecraft);
		} else {
			stop(minecraft);
		}
	}

	private void start(Minecraft minecraft) {
		if (active) {
			return;
		}

		previousCamera = minecraft.options.getCameraType();
		yaw = minecraft.player.getYRot();
		pitch = minecraft.player.getXRot();
		minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		active = true;
	}

	private void stop(Minecraft minecraft) {
		if (!active) {
			return;
		}

		active = false;
		minecraft.options.setCameraType(previousCamera);
	}
}
