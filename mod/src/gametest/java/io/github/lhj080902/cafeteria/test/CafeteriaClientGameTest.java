package io.github.lhj080902.cafeteria.test;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import io.github.lhj080902.cafeteria.gui.ModuleSettingsScreen;
import io.github.lhj080902.cafeteria.hud.ClickTracker;
import io.github.lhj080902.cafeteria.hud.CombatTracker;
import io.github.lhj080902.cafeteria.hud.HudElement;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

			logKeyConflicts(context);
			testWindowTitle(context);
			testTutorialOff(context);
			testHitbox(context);
			testZoom(context, input);
			testFreelook(context, input);
			testCombat(context, input);
			testStatusHuds(context, server);
			testKeysWorkDespiteConflicts(context, input);
			testScreens(context, input);
			testHudEditor(context, input);
			testFullbright(context, input, server);
			testLowFire(context, server);
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

	/** 다른 키 설정과 같은 키를 쓰는 Cafeteria 키가 있으면 기록해 둔다 (실패로 보지는 않는다). */
	private static void logKeyConflicts(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			for (KeyMapping mine : List.of(CafeteriaClient.menuKey, CafeteriaClient.freelookKey, CafeteriaClient.zoomKey, CafeteriaClient.fullbrightKey)) {
				for (KeyMapping other : minecraft.options.keyMappings) {
					if (other != mine && !other.isUnbound() && other.same(mine)) {
						CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 키 겹침 {} ↔ {} ({})", mine.getName(), other.getName(), mine.getTranslatedKeyMessage().getString());
					}
				}
			}
		});
	}

	private static void testTutorialOff(ClientGameTestContext context) {
		context.runOnClient(minecraft -> minecraft.getTutorial().setStep(TutorialSteps.MOVEMENT));
		context.waitTicks(2);
		check(context.computeOnClient(minecraft -> minecraft.options.tutorialStep) == TutorialSteps.NONE, "튜토리얼이 자동으로 꺼지지 않았습니다");

		// 모듈을 끄면 더 이상 건드리지 않는다.
		context.runOnClient(minecraft -> {
			Modules.NO_TUTORIAL.setEnabled(false);
			minecraft.getTutorial().setStep(TutorialSteps.MOVEMENT);
		});
		context.waitTicks(2);
		check(context.computeOnClient(minecraft -> minecraft.options.tutorialStep) == TutorialSteps.MOVEMENT, "모듈을 껐는데도 튜토리얼 단계를 바꿨습니다");
		context.runOnClient(minecraft -> Modules.NO_TUTORIAL.setEnabled(true));
		context.waitTicks(2);
	}

	/** 주민을 때려서 CPS, 콤보, 리치를 확인한다. */
	private static void testCombat(ClientGameTestContext context, TestInput input) {
		context.runOnClient(minecraft -> {
			Modules.COMBO.setEnabled(true);
			Modules.REACH.setEnabled(true);
		});
		check(!context.computeOnClient(Modules.COMBO::hasContent), "아직 때리지 않았는데 콤보가 표시됩니다");
		check(!context.computeOnClient(Modules.REACH::hasContent), "아직 때리지 않았는데 리치가 표시됩니다");

		for (int i = 0; i < 5; i++) {
			input.pressMouse(0);
		}

		int cps = context.computeOnClient(minecraft -> ClickTracker.cps(ClickTracker.LEFT));
		check(cps == 5, "왼쪽 클릭 5번이 CPS 5로 세어지지 않았습니다: " + cps);
		context.waitTicks(3);
		int combo = context.computeOnClient(minecraft -> CombatTracker.combo());
		double reach = context.computeOnClient(minecraft -> CombatTracker.reach());
		// 무적 시간 때문에 5번 눌러도 전부 들어가지는 않는다. 최소 한 번은 적중해야 한다.
		check(combo >= 1 && combo <= 5, "주민을 때렸는데 콤보가 올바르지 않습니다: " + combo);
		check(reach > 1.0 && reach < 4.5, "리치 값이 이상합니다: " + reach);
		// 방금 맞은 주민의 히트박스가 피격 색(빨강)으로 바뀐 모습과 콤보·리치 표시를 남긴다.
		context.takeScreenshot("hitbox-hurt-and-combat-hud");

		// 한동안 맞히지 않으면 콤보가 끊기고 리치 표시도 사라진다.
		context.waitTicks(70);
		check(context.computeOnClient(minecraft -> CombatTracker.combo()) == 0, "시간이 지나도 콤보가 끊기지 않았습니다");
		check(context.computeOnClient(minecraft -> CombatTracker.reach()) < 0.0, "시간이 지나도 리치 표시가 남아 있습니다");

		// 내가 맞으면 콤보가 끊긴다.
		input.pressMouse(0);
		context.waitTicks(3);
		check(context.computeOnClient(minecraft -> CombatTracker.combo()) == 1, "다시 때렸는데 콤보가 1이 아닙니다");
		context.runOnClient(minecraft -> minecraft.player.hurtTime = 10);
		context.waitTicks(2);
		check(context.computeOnClient(minecraft -> CombatTracker.combo()) == 0, "내가 맞았는데 콤보가 끊기지 않았습니다");

		context.runOnClient(minecraft -> {
			Modules.COMBO.setEnabled(false);
			Modules.REACH.setEnabled(false);
		});
	}

	/** 좌표, 갑옷, 포션 HUD. */
	private static void testStatusHuds(ClientGameTestContext context, TestServerContext server) {
		context.runOnClient(minecraft -> Modules.COORDINATES.setEnabled(true));
		check(!context.computeOnClient(Modules.ARMOR::hasContent), "아무것도 입지 않았는데 갑옷 HUD 가 표시됩니다");
		check(!context.computeOnClient(Modules.POTIONS::hasContent), "효과가 없는데 포션 HUD 가 표시됩니다");

		server.runCommand("item replace entity @a armor.head with minecraft:diamond_helmet");
		server.runCommand("item replace entity @a armor.chest with minecraft:iron_chestplate[damage=200]");
		server.runCommand("item replace entity @a armor.feet with minecraft:golden_boots[damage=85]");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:diamond_sword");
		server.runCommand("effect give @a minecraft:speed 90 1");
		server.runCommand("effect give @a minecraft:strength 45 0");
		context.waitTicks(5);
		check(context.computeOnClient(Modules.ARMOR::hasContent), "갑옷을 입었는데 갑옷 HUD 가 비어 있습니다");
		check(context.computeOnClient(Modules.POTIONS::hasContent), "효과가 있는데 포션 HUD 가 비어 있습니다");
		// 손에 든 아이템 표시를 끄면 한 줄이 줄어든다.
		int withHand = context.computeOnClient(Modules.ARMOR::height);
		context.runOnClient(minecraft -> Modules.ARMOR.mainHand.set(false));
		int withoutHand = context.computeOnClient(Modules.ARMOR::height);
		check(withHand - withoutHand == 17, "손에 든 아이템 줄 높이가 예상과 다릅니다: " + withHand + " → " + withoutHand);
		context.runOnClient(minecraft -> Modules.ARMOR.mainHand.set(true));
		context.takeScreenshot("status-huds");

		server.runCommand("effect clear @a");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.runOnClient(minecraft -> Modules.COORDINATES.setEnabled(false));
		context.waitTicks(5);
	}

	/**
	 * 다른 키 설정이 같은 키를 차지하고 있어도 Cafeteria 키는 동작해야 한다.
	 * (바닐라는 키 하나에 키 설정 하나만 연결하므로, 겹치면 한쪽은 눌림을 받지 못한다.)
	 */
	private static void testKeysWorkDespiteConflicts(ClientGameTestContext context, TestInput input) {
		context.runOnClient(minecraft -> {
			// 바닐라 키 두 개를 Cafeteria 메뉴 키·풀브라이트 키와 같은 키로 바꿔 놓는다.
			minecraft.options.keySocialInteractions.setKey(KeyBindingHelper.getBoundKeyOf(CafeteriaClient.menuKey));
			minecraft.options.keyAdvancements.setKey(KeyBindingHelper.getBoundKeyOf(CafeteriaClient.fullbrightKey));
			KeyMapping.resetMapping();
		});

		input.pressKey(KeyBindingHelper.getBoundKeyOf(CafeteriaClient.fullbrightKey));
		context.waitTicks(2);
		check(Modules.FULLBRIGHT.isEnabled(), "키가 겹친 상태에서 풀브라이트 키가 동작하지 않았습니다");
		// 같은 키를 쓰는 바닐라 화면(발전 과제)이 같이 열렸을 수 있으므로 닫고 다시 누른다.
		context.setScreen(() -> null);
		input.pressKey(KeyBindingHelper.getBoundKeyOf(CafeteriaClient.fullbrightKey));
		context.waitTicks(2);
		check(!Modules.FULLBRIGHT.isEnabled(), "키가 겹친 상태에서 풀브라이트 키를 다시 눌러도 꺼지지 않았습니다");
		context.setScreen(() -> null);

		input.pressKey(KeyBindingHelper.getBoundKeyOf(CafeteriaClient.menuKey));
		context.waitTicks(3);
		context.waitForScreen(CafeteriaScreen.class);
		context.setScreen(() -> null);

		context.runOnClient(minecraft -> {
			minecraft.options.keySocialInteractions.setKey(minecraft.options.keySocialInteractions.getDefaultKey());
			minecraft.options.keyAdvancements.setKey(minecraft.options.keyAdvancements.getDefaultKey());
			KeyMapping.resetMapping();
		});
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

		// 목록이 화면보다 길므로 아래로 굴려서 마지막 카드(리치)까지 보이는지 확인한다.
		int last = Modules.ALL.size() - 1;
		int before = context.computeOnClient(minecraft -> ((CafeteriaScreen) minecraft.screen).cardY(last));
		context.runOnClient(minecraft -> minecraft.screen.mouseScrolled(100, 100, 0, -50));
		int after = context.computeOnClient(minecraft -> ((CafeteriaScreen) minecraft.screen).cardY(last));
		int screenHeight = context.computeOnClient(minecraft -> minecraft.screen.height);
		check(after < before && after + 35 <= screenHeight - 6 - 30, "메뉴를 끝까지 내려도 마지막 카드가 보이지 않습니다: " + before + " → " + after);
		context.takeScreenshot("menu-scrolled");
		context.runOnClient(minecraft -> minecraft.screen.mouseScrolled(100, 100, 0, 50));

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

		context.setScreen(() -> null);
		context.waitFor(minecraft -> minecraft.screen == null);
	}

	private static int[] position(ClientGameTestContext context, HudElement element) {
		return context.computeOnClient(minecraft -> new int[] {
			element.x(minecraft, minecraft.getWindow().getGuiScaledWidth()), element.y(minecraft, minecraft.getWindow().getGuiScaledHeight())
		});
	}

	/** HUD 위치 편집: 끌기, Shift 맞춤, 방향키 세밀 조정, 휠 크기 조절, 우클릭 설정. */
	private static void testHudEditor(ClientGameTestContext context, TestInput input) {
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(true);
				element.resetPosition();
			}
		});
		context.setScreen(() -> new HudEditScreen(null));
		// 편집 화면에서는 비어 있는 요소(콤보, 리치, 포션)도 예시 내용으로 보여야 한다.
		check(context.computeOnClient(minecraft -> Modules.COMBO.hasContent(minecraft) || HudElement.isEditing()), "편집 화면에서 편집 상태가 켜지지 않았습니다");
		context.takeScreenshot("hud-edit");

		int screenWidth = context.computeOnClient(minecraft -> minecraft.screen.width);
		int screenHeight = context.computeOnClient(minecraft -> minecraft.screen.height);
		int fpsWidth = context.computeOnClient(Modules.FPS::width);
		int fpsHeight = context.computeOnClient(Modules.FPS::height);

		// 1) 그냥 끌면 놓은 자리에 그대로 간다.
		int[] start = position(context, Modules.FPS);
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(start[0] + 3, start[1] + 3, 0);
			minecraft.screen.mouseDragged(203, 83, 0, 0, 0);
			minecraft.screen.mouseReleased(203, 83, 0);
		});
		int[] moved = position(context, Modules.FPS);
		check(moved[0] == 200 && moved[1] == 80, "HUD 드래그 위치가 다릅니다: " + moved[0] + ", " + moved[1]);

		// 2) 방향키: 1픽셀, Shift 와 함께면 10픽셀.
		input.pressKey(GLFW.GLFW_KEY_RIGHT);
		input.pressKey(GLFW.GLFW_KEY_DOWN);
		int[] nudged = position(context, Modules.FPS);
		check(nudged[0] == 201 && nudged[1] == 81, "방향키로 1픽셀 이동하지 않았습니다: " + nudged[0] + ", " + nudged[1]);
		input.holdShift();
		input.pressKey(GLFW.GLFW_KEY_LEFT);
		input.pressKey(GLFW.GLFW_KEY_UP);
		input.releaseShift();
		int[] jumped = position(context, Modules.FPS);
		check(jumped[0] == 191 && jumped[1] == 71, "Shift+방향키로 10픽셀 이동하지 않았습니다: " + jumped[0] + ", " + jumped[1]);

		// 맞춤 검사는 가장 가까운 안내선에 붙는지를 보는 것이라, 기준이 분명하도록 FPS 와 CPS 만 남긴다.
		// CPS 는 화면 가운데 안내선과 헷갈리지 않는 곳(왼쪽 위)에 고정해 둔다. 기본 위치는 미니맵 모드 유무에 따라 달라진다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.FPS || element == Modules.CPS);
			}

			Modules.CPS.setPosition(minecraft, 20, 30, minecraft.screen.width, minecraft.screen.height);
		});

		// 3) Shift 없이 화면 가운데 근처로 끌면 붙지 않는다.
		int centerX = (int) Math.round(screenWidth / 2.0 - fpsWidth / 2.0);
		int centerY = (int) Math.round(screenHeight / 2.0 - fpsHeight / 2.0);
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(jumped[0] + 3, jumped[1] + 3, 0);
			minecraft.screen.mouseDragged(centerX + 4 + 3, centerY - 4 + 3, 0, 0, 0);
			minecraft.screen.mouseReleased(centerX + 4 + 3, centerY - 4 + 3, 0);
		});
		int[] free = position(context, Modules.FPS);
		check(free[0] == centerX + 4 && free[1] == centerY - 4, "Shift 를 누르지 않았는데 위치가 바뀌었습니다: " + free[0] + ", " + free[1]);

		// 4) Shift 를 누른 채 끌면 화면 가운데 안내선에 붙는다.
		input.holdShift();
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(free[0] + 3, free[1] + 3, 0);
			minecraft.screen.mouseDragged(free[0] + 3 - 1, free[1] + 3 + 1, 0, 0, 0);
		});
		context.takeScreenshot("hud-edit-snap-center");
		context.runOnClient(minecraft -> minecraft.screen.mouseReleased(free[0] + 2, free[1] + 4, 0));
		int[] snapped = position(context, Modules.FPS);
		check(snapped[0] == centerX && snapped[1] == centerY, "화면 가운데에 붙지 않았습니다: " + snapped[0] + ", " + snapped[1] + " (기대 " + centerX + ", " + centerY + ")");

		// 5) Shift 를 누른 채 다른 요소(CPS) 근처로 끌면 그 요소의 왼쪽 가장자리와 아래쪽에 붙는다.
		int[] cps = position(context, Modules.CPS);
		int cpsHeight = context.computeOnClient(Modules.CPS::height);
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(snapped[0] + 3, snapped[1] + 3, 0);
			minecraft.screen.mouseDragged(cps[0] + 4 + 3, cps[1] + cpsHeight + 5 + 3, 0, 0, 0);
			minecraft.screen.mouseReleased(cps[0] + 4 + 3, cps[1] + cpsHeight + 5 + 3, 0);
		});
		input.releaseShift();
		int[] aligned = position(context, Modules.FPS);
		check(aligned[0] == cps[0] && aligned[1] == cps[1] + cpsHeight, "다른 요소에 맞춰 붙지 않았습니다: " + aligned[0] + ", " + aligned[1]);

		// 6) 화면 밖으로 끌어도 화면 안에 머문다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(aligned[0] + 3, aligned[1] + 3, 0);
			minecraft.screen.mouseDragged(-500, 9999, 0, 0, 0);
			minecraft.screen.mouseReleased(-500, 9999, 0);
		});
		int[] clamped = position(context, Modules.FPS);
		check(clamped[0] == 0 && clamped[1] == screenHeight - fpsHeight, "화면 밖으로 나갔습니다: " + clamped[0] + ", " + clamped[1]);

		// 7) 휠: 크기 조절 (왼쪽 위 모서리는 제자리).
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(clamped[0] + 3, clamped[1] + 3, 0);
			minecraft.screen.mouseDragged(150 + 3, 60 + 3, 0, 0, 0);
			minecraft.screen.mouseReleased(150 + 3, 60 + 3, 0);
			minecraft.screen.mouseScrolled(153, 63, 0, 1);
			minecraft.screen.mouseScrolled(153, 63, 0, 1);
		});
		check(near(Modules.FPS.scale.get(), 1.2), "휠로 크기가 바뀌지 않았습니다: " + Modules.FPS.scale.get());
		int[] scaled = position(context, Modules.FPS);
		check(scaled[0] == 150 && scaled[1] == 60, "크기를 바꾸면서 위치가 움직였습니다: " + scaled[0] + ", " + scaled[1]);
		check(context.computeOnClient(Modules.FPS::width) > fpsWidth, "크기를 키웠는데 폭이 그대로입니다");
		context.takeScreenshot("hud-edit-selected-scaled");

		// 8) 우클릭: 그 요소의 설정 화면이 열리고, 완료하면 편집 화면으로 돌아온다.
		context.runOnClient(minecraft -> minecraft.screen.mouseClicked(153, 63, 1));
		context.waitForScreen(ModuleSettingsScreen.class);
		context.clickScreenButton("gui.done");
		context.waitForScreen(HudEditScreen.class);

		context.clickScreenButton("cafeteria.hud_edit.reset");
		context.runOnClient(minecraft -> Modules.FPS.scale.reset());
		int[] reset = position(context, Modules.FPS);
		check(reset[0] == start[0] && reset[1] == start[1], "위치 초기화 후 원래 자리가 아닙니다: " + reset[0] + ", " + reset[1]);
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		check(!context.computeOnClient(minecraft -> HudElement.isEditing()), "편집 화면을 닫았는데 편집 상태가 남아 있습니다");

		// 기본 상태로 되돌린다: 좌표·콤보·리치만 꺼져 있고 나머지는 켜져 있다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element != Modules.COORDINATES && element != Modules.COMBO && element != Modules.REACH);
			}
		});
	}

	private static void testFullbright(ClientGameTestContext context, TestInput input, TestServerContext server) {
		server.runCommand("time set midnight");
		context.waitTicks(40);
		check(!Modules.FULLBRIGHT.isEnabled(), "풀브라이트는 기본으로 꺼져 있어야 합니다");
		context.takeScreenshot("night-normal");

		// 단축키로 켜고 끈다.
		input.pressKey(CafeteriaClient.fullbrightKey);
		context.waitTicks(10);
		check(Modules.FULLBRIGHT.isEnabled(), "풀브라이트 단축키로 켜지지 않았습니다");
		context.takeScreenshot("night-fullbright");
		input.pressKey(CafeteriaClient.fullbrightKey);
		context.waitTicks(2);
		check(!Modules.FULLBRIGHT.isEnabled(), "풀브라이트 단축키로 꺼지지 않았습니다");

		// 채팅창 같은 화면이 열려 있을 때 누른 키는 무시한다.
		context.setScreen(() -> new ChatScreen(""));
		input.pressKey(CafeteriaClient.fullbrightKey);
		context.waitTicks(2);
		context.setScreen(() -> null);
		context.waitTicks(2);
		check(!Modules.FULLBRIGHT.isEnabled(), "채팅 입력 중에 누른 키로 풀브라이트가 켜졌습니다");
		server.runCommand("time set noon");
	}

	/** 낮은 화염: 불이 붙은 상태에서 켜기 전/후 화면을 남긴다. */
	private static void testLowFire(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("effect give @a minecraft:fire_resistance 120 0 true");
		server.runCommand("execute at @a run setblock ~ ~ ~ minecraft:fire");
		context.waitFor(minecraft -> minecraft.player.isOnFire(), 200);
		context.waitTicks(5);
		context.takeScreenshot("fire-normal");
		context.runOnClient(minecraft -> {
			Modules.LOW_FIRE.setEnabled(true);
			Modules.LOW_FIRE.amount.set(100.0);
		});
		context.waitTicks(3);
		context.takeScreenshot("fire-low");
		check(near(Modules.LOW_FIRE.offset(), 0.5), "낮은 화염 100% 의 이동 거리가 다릅니다");
		context.runOnClient(minecraft -> Modules.LOW_FIRE.setEnabled(false));
		check(near(Modules.LOW_FIRE.offset(), 0.0), "낮은 화염을 껐는데 이동 거리가 남아 있습니다");
		server.runCommand("execute at @a run setblock ~ ~ ~ minecraft:air");
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
