package io.github.lhj080902.cafeteria.test;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import io.github.lhj080902.cafeteria.gui.ModuleSettingsScreen;
import io.github.lhj080902.cafeteria.hud.ClickTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 실제 클라이언트를 띄워서 Cafeteria 모듈이 게임 안에서 동작하는지 확인한다.
 * 실행: ./gradlew runClientGameTest (화면이 없는 환경에서는 xvfb-run 으로 감싼다)
 * 스크린샷은 build/run/clientGameTest/screenshots 에 저장된다.
 */
public class CafeteriaClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestInput input = context.getInput();
			TestServerContext server = world.getServer();
			world.getClientWorld().waitForChunksRender();

			// 플레이어가 남쪽(yaw 0)을 보게 하고, 바로 앞에 움직이지 않는 주민과 돼지를 세운다.
			server.runCommand("execute as @a at @s run tp @s ~ ~ ~ 0 5");
			context.waitTicks(2);
			server.runCommand("execute as @a at @s run summon minecraft:villager ^ ^ ^3 {NoAI:1b,Silent:1b}");
			server.runCommand("execute as @a at @s run summon minecraft:pig ^2 ^ ^4 {NoAI:1b,Silent:1b}");
			context.waitTicks(20);
			// 바닐라는 마우스를 잡은 직후의 첫 이동을 무시하므로, 실제 검사 전에 한 번 움직여 둔다.
			input.moveCursor(1.0, 0.0);
			context.waitTicks(2);

			testWindowTitle(context);
			testHitbox(context);
			testZoom(context, input);
			testFreelook(context, input);
			testClicksPerSecond(context, input);
			testScreens(context, input);
			testFullbright(context, server);
			testConfig(context);
		}

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 모든 검사를 통과했습니다");
	}

	private static void testWindowTitle(ClientGameTestContext context) {
		String title;

		try {
			title = context.computeOnClient(minecraft -> {
				Method createTitle = Minecraft.class.getDeclaredMethod("createTitle");
				createTitle.setAccessible(true);
				return (String) createTitle.invoke(minecraft);
			});
		} catch (NoSuchMethodException e) {
			// 배포 환경에서는 메서드 이름이 난독화돼 있어 이름으로 찾을 수 없다.
			// 창 제목 믹스인은 필수(required)라서 적용에 실패했다면 게임이 여기까지 오지 못한다.
			CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 배포 환경이라 창 제목 문자열 검사는 건너뜁니다");
			return;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("창 제목을 확인할 수 없습니다", e);
		}

		// 모드가 들어간 클라이언트는 바닐라가 "Minecraft*" 로 표시한다.
		check(title.startsWith("Cafeteria Client | Minecraft") && title.contains("1.21.4"), "창 제목이 바뀌지 않았습니다: " + title);
	}

	private static void testHitbox(ClientGameTestContext context) {
		check(Modules.HITBOX.isEnabled(), "히트박스 모듈은 기본으로 켜져 있어야 합니다");
		context.takeScreenshot("hitbox-on");

		context.runOnClient(minecraft -> Modules.HITBOX.target.set("players"));
		context.waitTicks(2);
		context.takeScreenshot("hitbox-players-only");

		context.runOnClient(minecraft -> {
			Modules.HITBOX.target.set("all");
			Modules.HITBOX.eyeLine.set(true);
			Modules.HITBOX.lookVector.set(true);
		});
		context.waitTicks(2);
		context.takeScreenshot("hitbox-eye-and-look");

		context.runOnClient(minecraft -> {
			Modules.HITBOX.eyeLine.set(false);
			Modules.HITBOX.lookVector.set(false);
			Modules.HITBOX.setEnabled(false);
		});
		context.waitTicks(2);
		context.takeScreenshot("hitbox-off");
		context.runOnClient(minecraft -> Modules.HITBOX.setEnabled(true));
	}

	private static void testZoom(ClientGameTestContext context, TestInput input) {
		check(near(zoomFactor(context), 1.0), "줌 키를 누르기 전에는 배율이 1이어야 합니다");
		int slot = selectedSlot(context);

		input.holdKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		double zoomed = zoomFactor(context);
		check(near(zoomed, Modules.ZOOM.factor.get()), "줌 배율이 설정값(4배)과 다릅니다: " + zoomed);
		context.takeScreenshot("zoom-4x");

		input.scroll(1.0);
		input.scroll(1.0);
		context.waitTicks(20);
		double scrolled = zoomFactor(context);
		check(scrolled > zoomed + 0.5, "줌 중 휠을 굴려도 배율이 늘지 않았습니다: " + scrolled);
		check(selectedSlot(context) == slot, "줌 중 휠이 핫바 슬롯을 바꿨습니다");
		context.takeScreenshot("zoom-scrolled");

		input.releaseKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		check(near(zoomFactor(context), 1.0), "줌 키를 떼도 배율이 1로 돌아오지 않았습니다");

		input.scroll(-1.0);
		context.waitTicks(2);
		check(selectedSlot(context) != slot, "줌이 아닐 때는 휠이 평소처럼 핫바 슬롯을 바꿔야 합니다");
	}

	private static void testFreelook(ClientGameTestContext context, TestInput input) {
		float playerYaw = playerYaw(context);
		float playerPitch = context.computeOnClient(minecraft -> minecraft.player.getXRot());

		input.holdKey(CafeteriaClient.freelookKey);
		context.waitTicks(3);
		check(Modules.FREELOOK.isActive(), "프리룩 키를 눌러도 프리룩이 시작되지 않았습니다");
		check(cameraType(context) == CameraType.THIRD_PERSON_BACK, "프리룩 중에는 3인칭 시점이어야 합니다");

		input.moveCursor(500.0, -60.0);
		context.waitTicks(3);
		check(near(playerYaw(context), playerYaw), "프리룩 중에 캐릭터가 회전했습니다");
		check(near(context.computeOnClient(minecraft -> minecraft.player.getXRot()), playerPitch), "프리룩 중에 캐릭터 고개가 움직였습니다");
		float cameraYaw = cameraYaw(context);
		check(Math.abs(Mth.wrapDegrees(cameraYaw - playerYaw)) > 30.0F, "프리룩 중에 카메라가 돌지 않았습니다: " + cameraYaw);
		context.takeScreenshot("freelook");

		input.releaseKey(CafeteriaClient.freelookKey);
		context.waitTicks(3);
		check(!Modules.FREELOOK.isActive(), "프리룩 키를 떼도 프리룩이 끝나지 않았습니다");
		check(cameraType(context) == CameraType.FIRST_PERSON, "프리룩이 끝나면 원래 시점(1인칭)으로 돌아와야 합니다");
		check(near(Mth.wrapDegrees(cameraYaw(context) - playerYaw), 0.0), "프리룩이 끝나면 카메라가 캐릭터 시선으로 돌아와야 합니다");

		input.moveCursor(200.0, 0.0);
		context.waitTicks(3);
		check(Math.abs(Mth.wrapDegrees(playerYaw(context) - playerYaw)) > 10.0F, "프리룩이 끝난 뒤 캐릭터가 마우스로 회전하지 않습니다");

		// 토글 방식: 한 번 누르면 켜지고 다시 누르면 꺼진다.
		context.runOnClient(minecraft -> Modules.FREELOOK.mode.set("toggle"));
		input.pressKey(CafeteriaClient.freelookKey);
		context.waitTicks(3);
		check(Modules.FREELOOK.isActive(), "토글 방식에서 한 번 눌러도 프리룩이 켜지지 않았습니다");
		input.pressKey(CafeteriaClient.freelookKey);
		context.waitTicks(3);
		check(!Modules.FREELOOK.isActive(), "토글 방식에서 다시 눌러도 프리룩이 꺼지지 않았습니다");
		context.runOnClient(minecraft -> Modules.FREELOOK.mode.set("hold"));

		// 다음 검사(주민 클릭)를 위해 다시 정면을 보게 한다.
		context.runOnClient(minecraft -> minecraft.player.setYRot(playerYaw));
		context.waitTicks(3);
	}

	private static void testClicksPerSecond(ClientGameTestContext context, TestInput input) {
		for (int i = 0; i < 5; i++) {
			input.pressMouse(0);
		}

		int cps = context.computeOnClient(minecraft -> ClickTracker.cps(ClickTracker.LEFT));
		check(cps == 5, "왼쪽 클릭 5번이 CPS 5로 세어지지 않았습니다: " + cps);
		// 방금 맞은 주민의 히트박스가 피격 색(빨강)으로 바뀐 모습을 남긴다.
		context.takeScreenshot("hitbox-hurt-and-cps");
	}

	private static void testScreens(ClientGameTestContext context, TestInput input) {
		input.pressKey(CafeteriaClient.menuKey);
		context.waitForScreen(CafeteriaScreen.class);
		context.takeScreenshot("menu-english");
		context.setScreen(() -> null);

		// 주 사용 언어인 한국어로 바꿔서 글자가 깨지거나 넘치지 않는지 확인한다.
		context.runOnClient(minecraft -> {
			minecraft.options.languageCode = "ko_kr";
			minecraft.getLanguageManager().setSelected("ko_kr");
			minecraft.reloadResourcePacks();
		});
		context.waitTicks(5);
		context.waitFor(minecraft -> minecraft.getOverlay() == null, 2400);
		context.waitTicks(10);

		input.pressKey(CafeteriaClient.menuKey);
		context.waitForScreen(CafeteriaScreen.class);
		context.takeScreenshot("menu");

		// 첫 번째 카드(프리룩)의 위치와 스위치 위치
		int[] card = context.computeOnClient(minecraft -> {
			CafeteriaScreen screen = (CafeteriaScreen) minecraft.screen;
			return new int[] {screen.cardX(0), screen.cardY(0), screen.switchCenterX(0)};
		});
		int switchX = card[2];
		int switchY = card[1] + 12;

		context.runOnClient(minecraft -> minecraft.screen.mouseClicked(switchX, switchY, 0));
		check(!Modules.FREELOOK.isEnabled(), "스위치를 눌러도 프리룩이 꺼지지 않았습니다");
		context.runOnClient(minecraft -> minecraft.screen.mouseClicked(switchX, switchY, 0));
		check(Modules.FREELOOK.isEnabled(), "스위치를 다시 눌러도 프리룩이 켜지지 않았습니다");

		context.runOnClient(minecraft -> minecraft.screen.mouseClicked(card[0] + 20, card[1] + 28, 0));
		context.waitForScreen(ModuleSettingsScreen.class);
		context.takeScreenshot("settings-freelook");
		context.clickScreenButton("gui.done");
		context.waitForScreen(CafeteriaScreen.class);

		context.setScreen(() -> new ModuleSettingsScreen(null, Modules.ZOOM));
		context.takeScreenshot("settings-zoom");
		context.setScreen(() -> new ModuleSettingsScreen(null, Modules.HITBOX));
		context.takeScreenshot("settings-hitbox");
		context.setScreen(() -> new ModuleSettingsScreen(null, Modules.KEYSTROKES));
		context.takeScreenshot("settings-keystrokes");

		context.setScreen(() -> new HudEditScreen(null));
		context.takeScreenshot("hud-edit");
		context.runOnClient(minecraft -> {
			int x = Modules.FPS.x(minecraft, minecraft.screen.width);
			int y = Modules.FPS.y(minecraft, minecraft.screen.height);
			minecraft.screen.mouseClicked(x + 3, y + 3, 0);
			minecraft.screen.mouseDragged(203, 83, 0, 0, 0);
			minecraft.screen.mouseReleased(203, 83, 0);
		});
		int[] moved = context.computeOnClient(minecraft -> new int[] {
			Modules.FPS.x(minecraft, minecraft.screen.width), Modules.FPS.y(minecraft, minecraft.screen.height)
		});
		check(Math.abs(moved[0] - 200) <= 1 && Math.abs(moved[1] - 80) <= 1, "HUD 드래그 위치가 다릅니다: " + moved[0] + ", " + moved[1]);
		context.takeScreenshot("hud-edit-moved");
		context.clickScreenButton("cafeteria.hud_edit.reset");
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
	}

	private static void testFullbright(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("time set midnight");
		context.waitTicks(40);
		context.takeScreenshot("night-normal");
		context.runOnClient(minecraft -> Modules.FULLBRIGHT.setEnabled(true));
		context.waitTicks(10);
		context.takeScreenshot("night-fullbright");
		context.runOnClient(minecraft -> Modules.FULLBRIGHT.setEnabled(false));
		server.runCommand("time set noon");
	}

	private static void testConfig(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			Modules.ZOOM.factor.set(6.0);
			Modules.HITBOX.color.set(0xFF55FF55);
			Modules.CPS.setEnabled(false);
			Modules.FPS.setPosition(minecraft, 100, 50, 400, 200);
			ConfigManager.save();

			Modules.ZOOM.factor.set(4.0);
			Modules.HITBOX.color.set(0xFFFFFFFF);
			Modules.CPS.setEnabled(true);
			Modules.FPS.resetPosition();
			ConfigManager.load();
		});
		check(near(Modules.ZOOM.factor.get(), 6.0), "저장한 줌 배율을 다시 읽지 못했습니다");
		check(Modules.HITBOX.color.get() == 0xFF55FF55, "저장한 히트박스 색상을 다시 읽지 못했습니다");
		check(!Modules.CPS.isEnabled(), "저장한 모듈 켜짐 상태를 다시 읽지 못했습니다");
		int x = context.computeOnClient(minecraft -> Modules.FPS.x(minecraft, 400));
		check(Math.abs(x - 100) <= 1, "저장한 HUD 위치를 다시 읽지 못했습니다: " + x);

		Path file = FabricLoader.getInstance().getConfigDir().resolve("cafeteria.json");
		String json;

		try {
			json = Files.readString(file, StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new AssertionError("설정 파일을 읽을 수 없습니다: " + file, e);
		}

		check(json.contains("\"zoom_factor\": 6.0") && json.contains("#FF55FF55"), "설정 파일 내용이 예상과 다릅니다");

		// 깨진 설정 파일이 있어도 게임이 죽지 않고 현재 값을 유지해야 한다.
		try {
			context.runOnClient(minecraft -> {
				Files.writeString(file, "{ 이건 JSON 이 아님", StandardCharsets.UTF_8);
				ConfigManager.load();
				Files.delete(file);
			});
		} catch (java.io.IOException e) {
			throw new AssertionError("설정 파일을 쓸 수 없습니다: " + file, e);
		}

		check(near(Modules.ZOOM.factor.get(), 6.0), "깨진 설정 파일을 읽은 뒤 값이 바뀌었습니다");
	}

	private static double zoomFactor(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> Modules.ZOOM.displayedFactor());
	}

	private static int selectedSlot(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> minecraft.player.getInventory().selected);
	}

	private static float playerYaw(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> minecraft.player.getYRot());
	}

	private static float cameraYaw(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> minecraft.gameRenderer.getMainCamera().getYRot());
	}

	private static CameraType cameraType(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> minecraft.options.getCameraType());
	}

	private static boolean near(double actual, double expected) {
		return Math.abs(actual - expected) < 0.05;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
