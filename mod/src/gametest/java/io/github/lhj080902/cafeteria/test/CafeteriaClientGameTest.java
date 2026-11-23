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
import io.github.lhj080902.cafeteria.module.ScreenshotModule;
import io.github.lhj080902.cafeteria.util.ScreenshotClipboard;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 실제 클라이언트를 띄워서 Cafeteria 모듈이 게임 안에서 동작하는지 확인한다.
 * 실행: ./gradlew runClientGameTest (화면이 없는 환경에서는 xvfb-run 으로 감싼다)
 * 스크린샷은 build/run/clientGameTest/screenshots 에 저장된다.
 */
public class CafeteriaClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// 서버 리소스팩 호환성 테스트만 돌리라고 요청받았으면 기본 테스트는 건너뛴다.
		if (ServerResourcePackTest.requested()) {
			return;
		}

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
			testZoomKeepsHand(context, input, server);
			testFreelook(context, input);
			testCombat(context, input);
			testStatusHuds(context, server);
			testKeysWorkDespiteConflicts(context, input);
			testMenuBackground(context);
			testScreens(context, input);
			testChatTime(context);
			testInfiniteChat(context);
			testHudAboveChat(context);
			testScreenshotCopy(context, input);
			testHudEditor(context, input);
			testHudBoxSelection(context, input);
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

	/**
	 * 줌을 해도 1인칭 손(든 아이템)의 크기와 위치가 그대로인지 스크린샷의 픽셀로 확인한다.
	 * 주변 풍경에 없는 색(자홍색 콘크리트)을 손에 들려서 그 색이 차지하는 영역을 비교한다.
	 */
	private static void testZoomKeepsHand(ClientGameTestContext context, TestInput input, TestServerContext server) {
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:magenta_concrete");
		// 손 위에 겹쳐 그려지는 HUD(키스트로크 등)가 비교에 섞이지 않게 잠시 끈다.
		List<HudElement> shown = context.computeOnClient(minecraft -> Modules.HUD.stream().filter(HudElement::isEnabled).toList());
		context.runOnClient(minecraft -> shown.forEach(element -> element.setEnabled(false)));
		// 아이템을 꺼내 드는 동작이 끝날 때까지 기다린다.
		context.waitTicks(30);
		int[] normal = magentaArea(context.takeScreenshot("hand-normal"));
		check(normal[0] > 2000, "손에 든 블록이 화면에 보이지 않습니다: " + normal[0] + "픽셀");

		input.holdKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		check(near(zoomFactor(context), Modules.ZOOM.factor.get()), "줌이 걸리지 않았습니다");
		int[] zoomed = magentaArea(context.takeScreenshot("hand-zoomed"));
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 손 영역(픽셀 수, 왼쪽, 위, 오른쪽, 아래) 평소 {} / 줌 {}", java.util.Arrays.toString(normal), java.util.Arrays.toString(zoomed));
		check(Math.abs(zoomed[0] - normal[0]) <= normal[0] / 50, "줌을 하니 손의 크기가 바뀌었습니다: " + normal[0] + " → " + zoomed[0] + "픽셀");

		for (int i = 1; i < 5; i++) {
			check(Math.abs(zoomed[i] - normal[i]) <= 2, "줌을 하니 손의 위치가 바뀌었습니다: " + java.util.Arrays.toString(normal) + " → " + java.util.Arrays.toString(zoomed));
		}

		// "줌 중 손 숨기기"를 켜면 확대하는 동안에만 손이 사라진다.
		context.runOnClient(minecraft -> Modules.ZOOM.hideHand.set(true));
		context.waitTicks(3);
		int[] hidden = magentaArea(context.takeScreenshot("hand-hidden-while-zooming"));
		check(hidden[0] == 0, "줌 중 손 숨기기를 켰는데 손이 보입니다: " + hidden[0] + "픽셀");
		input.releaseKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		int[] back = magentaArea(context.takeScreenshot("hand-after-zoom"));
		check(Math.abs(back[0] - normal[0]) <= normal[0] / 50, "줌을 풀었는데 손이 돌아오지 않았습니다: " + back[0] + "픽셀");

		context.runOnClient(minecraft -> {
			Modules.ZOOM.hideHand.set(false);
			shown.forEach(element -> element.setEnabled(true));
		});
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.waitTicks(10);
	}

	private static BufferedImage readImage(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (java.io.IOException e) {
			throw new AssertionError("스크린샷을 읽을 수 없습니다: " + file, e);
		}
	}

	/**
	 * 스크린샷의 오른쪽 아래(1인칭 손이 그려지는 곳)에서 자홍색 픽셀의 개수와 그 영역(왼쪽, 위, 오른쪽, 아래)을 돌려준다.
	 * 핫바의 아이템 그림은 화면 왼쪽 절반에 있어서 섞이지 않는다.
	 */
	private static int[] magentaArea(Path screenshot) {
		BufferedImage image = readImage(screenshot);
		int count = 0;
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int right = -1;
		int bottom = -1;

		for (int y = image.getHeight() / 2; y < image.getHeight(); y++) {
			for (int x = image.getWidth() / 2; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);
				int red = rgb >> 16 & 0xFF;
				int green = rgb >> 8 & 0xFF;
				int blue = rgb & 0xFF;

				if (red > 70 && blue > 60 && green * 2 < red && Math.abs(red - blue) < 70) {
					count++;
					left = Math.min(left, x);
					top = Math.min(top, y);
					right = Math.max(right, x);
					bottom = Math.max(bottom, y);
				}
			}
		}

		return count == 0 ? new int[] {0, 0, 0, 0, 0} : new int[] {count, left, top, right, bottom};
	}

	/**
	 * 스크린샷 왼쪽 가장자리 아래쪽 영역의 평균 밝기와 선명도(옆 픽셀과의 밝기 차이 평균)를 잰다.
	 * 메뉴 배경이 얼마나 어두워졌는지, 흐려졌는지를 숫자로 비교하는 데 쓴다.
	 */
	private static double[] brightnessAndSharpness(Path screenshot) {
		BufferedImage image = readImage(screenshot);
		// 모드 설정 화면의 패널이 가리지 않는 왼쪽 가장자리만 본다.
		int fromX = 4;
		int toX = 40;
		int fromY = image.getHeight() * 55 / 100;
		int toY = image.getHeight() * 90 / 100;
		double brightness = 0.0;
		double sharpness = 0.0;
		int count = 0;

		for (int y = fromY; y < toY; y++) {
			for (int x = fromX; x < toX; x++) {
				double here = luminance(image.getRGB(x, y));
				brightness += here;
				sharpness += Math.abs(here - luminance(image.getRGB(x + 1, y)));
				count++;
			}
		}

		return new double[] {brightness / count, sharpness / count};
	}

	private static double luminance(int rgb) {
		return 0.299 * (rgb >> 16 & 0xFF) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF);
	}

	/**
	 * 깨끗한 메뉴 배경: ESC 메뉴와 모드 설정을 열어도 뒤 화면이 흐려지거나 검게 가려지지 않아야 한다.
	 * 모듈을 끄면 바닐라처럼 흐려진다.
	 */
	private static void testMenuBackground(ClientGameTestContext context) {
		check(Modules.MENU_BACKGROUND.isEnabled(), "깨끗한 메뉴 배경은 기본으로 켜져 있어야 합니다");
		// HUD 와 채팅이 비교 영역에 끼어들지 않게 잠시 숨긴다 (F1 과 같은 상태).
		context.runOnClient(minecraft -> minecraft.options.hideGui = true);
		context.waitTicks(3);
		double[] game = brightnessAndSharpness(context.takeScreenshot("background-game"));

		context.setScreen(() -> new PauseScreen(true));
		context.waitTicks(3);
		double[] pause = brightnessAndSharpness(context.takeScreenshot("background-pause-clean"));
		context.setScreen(() -> new CafeteriaScreen(null));
		context.waitTicks(3);
		double[] menu = brightnessAndSharpness(context.takeScreenshot("background-menu-clean"));

		// 어둡게 0% 면 게임 화면과 똑같이 보인다.
		context.runOnClient(minecraft -> Modules.MENU_BACKGROUND.dim.set(0.0));
		context.waitTicks(3);
		double[] noDim = brightnessAndSharpness(context.takeScreenshot("background-menu-no-dim"));
		context.runOnClient(minecraft -> Modules.MENU_BACKGROUND.dim.reset());

		// 모듈을 끄면 바닐라 배경(흐림 + 어둡게)으로 돌아간다.
		context.runOnClient(minecraft -> Modules.MENU_BACKGROUND.setEnabled(false));
		context.setScreen(() -> new PauseScreen(true));
		context.waitTicks(3);
		double[] vanilla = brightnessAndSharpness(context.takeScreenshot("background-pause-vanilla"));
		context.runOnClient(minecraft -> {
			Modules.MENU_BACKGROUND.setEnabled(true);
			minecraft.options.hideGui = false;
		});
		context.setScreen(() -> null);
		context.waitFor(minecraft -> minecraft.screen == null);

		CafeteriaClient.LOGGER.info(
			"CAFETERIA GAMETEST: 배경 밝기/선명도 — 게임 {}/{}, ESC {}/{}, 모드 설정 {}/{}, 어둡게 0% {}/{}, 바닐라 ESC {}/{}",
			round(game[0]), round(game[1]), round(pause[0]), round(pause[1]), round(menu[0]), round(menu[1]),
			round(noDim[0]), round(noDim[1]), round(vanilla[0]), round(vanilla[1]));

		check(game[0] > 40.0 && game[1] > 1.0, "비교할 게임 화면이 너무 어둡거나 밋밋합니다: " + game[0] + ", " + game[1]);

		for (double[] clean : List.of(pause, menu)) {
			// 기본값(어둡게 25%)이면 밝기가 75% 로 줄 뿐이고 흐려지지는 않는다.
			check(clean[0] > game[0] * 0.70 && clean[0] < game[0] * 0.80, "메뉴 배경 밝기가 예상(75%)과 다릅니다: " + game[0] + " → " + clean[0]);
			check(clean[1] > game[1] * 0.70, "메뉴 배경이 흐려졌습니다: 선명도 " + game[1] + " → " + clean[1]);
		}

		check(Math.abs(noDim[0] - game[0]) < game[0] * 0.02 && Math.abs(noDim[1] - game[1]) < game[1] * 0.05,
			"어둡게 0% 인데 게임 화면과 다릅니다: " + game[0] + "/" + game[1] + " → " + noDim[0] + "/" + noDim[1]);
		check(vanilla[1] < pause[1] * 0.6, "모듈을 껐는데 바닐라 흐림 효과가 돌아오지 않았습니다: 선명도 " + pause[1] + " → " + vanilla[1]);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	/** 채팅창에 쌓인 메시지를 최근 것부터 글자로 돌려준다. (필드 이름이 환경마다 달라서 형식으로 찾는다.) */
	private static List<String> chatMessages(ClientGameTestContext context) {
		return context.computeOnClient(CafeteriaClientGameTest::readChat);
	}

	private static List<String> readChat(Minecraft minecraft) {
		ChatComponent chat = minecraft.gui.getChat();
		List<String> messages = new ArrayList<>();

		try {
			for (Field field : ChatComponent.class.getDeclaredFields()) {
				if (!List.class.isAssignableFrom(field.getType())) {
					continue;
				}

				field.setAccessible(true);

				for (Object entry : (List<?>) field.get(chat)) {
					if (entry instanceof GuiMessage message) {
						messages.add(message.content().getString());
					}
				}
			}
		} catch (IllegalAccessException e) {
			throw new AssertionError("채팅 메시지를 읽을 수 없습니다", e);
		}

		return messages;
	}

	/** 채팅 시간: 채팅창에 올라오는 메시지 왼쪽에 시각이 붙는다. */
	private static void testChatTime(ClientGameTestContext context) {
		check(Modules.CHAT_TIME.isEnabled(), "채팅 시간은 기본으로 켜져 있어야 합니다");
		LocalTime time = LocalTime.of(14, 5, 9);
		check(Modules.CHAT_TIME.formatTime(time).equals("14:05"), "기본 시간 형식이 다릅니다: " + Modules.CHAT_TIME.formatTime(time));
		context.runOnClient(minecraft -> Modules.CHAT_TIME.format.set("hms24"));
		check(Modules.CHAT_TIME.formatTime(time).equals("14:05:09"), "초 포함 형식이 다릅니다: " + Modules.CHAT_TIME.formatTime(time));
		context.runOnClient(minecraft -> Modules.CHAT_TIME.format.set("hm12"));
		String twelveHour = context.computeOnClient(minecraft -> Modules.CHAT_TIME.formatTime(time));
		boolean korean = context.computeOnClient(minecraft -> minecraft.getLanguageManager().getSelected().startsWith("ko"));
		check(twelveHour.equals(korean ? "오후 2:05" : "2:05 PM"), "오전/오후 형식이 다릅니다: " + twelveHour);
		check(context.computeOnClient(minecraft -> Modules.CHAT_TIME.withTime(Component.literal("안녕"), time).getString()).equals("[" + twelveHour + "] 안녕"),
			"시간을 붙인 메시지가 예상과 다릅니다");
		context.runOnClient(minecraft -> Modules.CHAT_TIME.format.reset());

		context.runOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);
			minecraft.gui.getChat().addMessage(Component.literal("<Steve> 카페테리아 채팅 시간 테스트"));
			Modules.CHAT_TIME.brackets.set(false);
			minecraft.gui.getChat().addMessage(Component.literal("<Alex> 대괄호 없이"));
			Modules.CHAT_TIME.brackets.reset();
			Modules.CHAT_TIME.setEnabled(false);
			minecraft.gui.getChat().addMessage(Component.literal("<Steve> 시간 표시를 끈 메시지"));
			Modules.CHAT_TIME.setEnabled(true);
		});
		List<String> messages = chatMessages(context);
		check(messages.size() == 3, "채팅 메시지 수가 다릅니다: " + messages);
		check(messages.get(2).matches("\\[\\d\\d:\\d\\d] <Steve> 카페테리아 채팅 시간 테스트"), "채팅에 시간이 붙지 않았습니다: " + messages.get(2));
		check(messages.get(1).matches("\\d\\d:\\d\\d <Alex> 대괄호 없이"), "대괄호를 끈 형식이 다릅니다: " + messages.get(1));
		check(messages.get(0).equals("<Steve> 시간 표시를 끈 메시지"), "모듈을 껐는데 시간이 붙었습니다: " + messages.get(0));
		context.waitTicks(3);
		context.takeScreenshot("chat-time");
		context.runOnClient(minecraft -> minecraft.gui.getChat().clearMessages(false));
	}

	/** 무한 채팅: 바닐라의 100줄 제한을 넘겨도 예전 채팅이 지워지지 않는다. */
	private static void testInfiniteChat(ClientGameTestContext context) {
		check(Modules.INFINITE_CHAT.isEnabled(), "무한 채팅은 기본으로 켜져 있어야 합니다");
		context.runOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);

			for (int i = 1; i <= 250; i++) {
				minecraft.gui.getChat().addMessage(Component.literal("무한 채팅 " + i + "번째 줄"));
			}
		});
		List<String> kept = chatMessages(context);
		check(kept.size() == 250, "250줄을 넣었는데 " + kept.size() + "줄만 남았습니다");
		check(kept.get(249).endsWith("무한 채팅 1번째 줄"), "가장 오래된 채팅이 지워졌습니다: " + kept.get(249));

		// 채팅창을 열고 맨 위로 올리면 첫 줄까지 보인다.
		context.setScreen(() -> new ChatScreen(""));
		context.runOnClient(minecraft -> minecraft.gui.getChat().scrollChat(100000));
		context.waitTicks(3);
		context.takeScreenshot("infinite-chat-scrolled-to-top");
		context.setScreen(() -> null);

		// 모듈을 끄면 다음 메시지부터 바닐라처럼 100줄만 남는다.
		context.runOnClient(minecraft -> {
			Modules.INFINITE_CHAT.setEnabled(false);
			minecraft.gui.getChat().addMessage(Component.literal("무한 채팅을 끈 뒤의 줄"));
			Modules.INFINITE_CHAT.setEnabled(true);
		});
		check(chatMessages(context).size() == 100, "모듈을 껐는데 100줄로 줄지 않았습니다: " + chatMessages(context).size());
		context.runOnClient(minecraft -> minecraft.gui.getChat().clearMessages(false));
	}

	/**
	 * HUD 는 채팅보다 앞에 그려져야 한다: 채팅 글자로 꽉 찬 자리에 HUD 를 올려놓고,
	 * HUD 글자색이 그대로 보이는지, 채팅의 흰 글자가 HUD 위로 비치지 않는지 픽셀로 확인한다.
	 */
	private static void testHudAboveChat(ClientGameTestContext context) {
		int[] area = context.computeOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);

			// 흰색 꽉 찬 글자로 채팅창을 덮는다.
			for (int i = 0; i < 8; i++) {
				minecraft.gui.getChat().addMessage(Component.literal("\u2588".repeat(30)));
			}

			int width = minecraft.getWindow().getGuiScaledWidth();
			int height = minecraft.getWindow().getGuiScaledHeight();
			Modules.FPS.textColor.set(0xFF55FF55);
			Modules.FPS.shadow.set(false);
			Modules.FPS.setPosition(minecraft, 60, height - 80, width, height);
			int scale = (int) minecraft.getWindow().getGuiScale();
			return new int[] {
				Modules.FPS.x(minecraft, width) * scale, Modules.FPS.y(minecraft, height) * scale,
				Modules.FPS.width(minecraft) * scale, Modules.FPS.height(minecraft) * scale
			};
		});
		context.waitTicks(3);
		BufferedImage image = readImage(context.takeScreenshot("hud-above-chat"));
		int hudText = 0;
		int chatWhite = 0;

		for (int y = area[1]; y < area[1] + area[3]; y++) {
			for (int x = area[0]; x < area[0] + area[2]; x++) {
				int rgb = image.getRGB(x, y);
				int red = rgb >> 16 & 0xFF;
				int green = rgb >> 8 & 0xFF;
				int blue = rgb & 0xFF;

				// HUD 글자색(연두색)에 가까운 픽셀. 글꼴 가장자리는 배경과 섞여서 정확히 같은 색은 아니다.
				if (green > 170 && red < 150 && blue < 150) {
					hudText++;
				} else if (red > 235 && green > 235 && blue > 235) {
					chatWhite++;
				}
			}
		}

		// 채팅 글자가 실제로 그 자리를 덮고 있었는지도 확인한다 (HUD 바로 왼쪽은 흰 글자가 그대로 보여야 한다).
		int besideHud = 0;

		for (int y = area[1]; y < area[1] + area[3]; y++) {
			for (int x = area[0] - 40; x < area[0] - 4; x++) {
				int rgb = image.getRGB(x, y);

				if ((rgb >> 16 & 0xFF) > 235 && (rgb >> 8 & 0xFF) > 235 && (rgb & 0xFF) > 235) {
					besideHud++;
				}
			}
		}

		context.runOnClient(minecraft -> {
			Modules.FPS.textColor.reset();
			Modules.FPS.shadow.reset();
			Modules.FPS.resetPosition();
			minecraft.gui.getChat().clearMessages(false);
		});
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 채팅 위의 HUD — HUD 글자 {}픽셀, HUD 위로 비친 채팅 {}픽셀, HUD 옆 채팅 {}픽셀", hudText, chatWhite, besideHud);
		check(besideHud > 200, "채팅 글자가 HUD 자리 주변을 덮지 않아서 비교할 수 없습니다: " + besideHud);
		check(hudText > 40, "HUD 글자가 채팅에 가려졌습니다: " + hudText + "픽셀");
		check(chatWhite == 0, "채팅 글자가 HUD 위에 그려졌습니다: " + chatWhite + "픽셀");
	}

	/**
	 * 스크린샷 도구: F2 로 찍으면 채팅 안내에 [복사] 버튼이 붙고, 버튼(명령어)을 실행하면 그 그림이 클립보드에 들어간다.
	 * 클립보드는 게임 밖 프로그램(xclip)으로도 읽어서 실제로 다른 프로그램에 붙여 넣을 수 있는 상태인지 확인한다.
	 */
	private static void testScreenshotCopy(ClientGameTestContext context, TestInput input) {
		check(Modules.SCREENSHOT.isEnabled(), "스크린샷 도구는 기본으로 켜져 있어야 합니다");
		String copyLabel = context.computeOnClient(minecraft -> Component.translatable("cafeteria.screenshot.copy").getString());
		context.runOnClient(minecraft -> minecraft.gui.getChat().clearMessages(false));

		input.pressKey(GLFW.GLFW_KEY_F2);
		context.waitFor(minecraft -> readChat(minecraft).stream().anyMatch(message -> message.contains(copyLabel)), 400);
		String notice = chatMessages(context).get(0);
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{4}-\\d\\d-\\d\\d_\\d\\d\\.\\d\\d\\.\\d\\d(_\\d+)?\\.png").matcher(notice);
		check(matcher.find(), "스크린샷 안내에서 파일 이름을 찾을 수 없습니다: " + notice);
		String fileName = matcher.group();
		Path file = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve("screenshots").resolve(fileName));
		check(Files.isRegularFile(file), "스크린샷 파일이 없습니다: " + file);
		check(ScreenshotClipboard.lastCopied() == null, "자동 복사를 켜지 않았는데 이미 복사됐습니다");
		context.setScreen(() -> new ChatScreen(""));
		context.waitTicks(3);
		context.takeScreenshot("screenshot-copy-button");
		context.setScreen(() -> null);

		// [복사] 버튼이 실행하는 명령어를 그대로 실행한다.
		boolean handled = context.computeOnClient(minecraft -> minecraft.player.connection.sendUnsignedCommand(ScreenshotModule.COPY_COMMAND + fileName));
		check(handled, "복사 명령어가 실행되지 않았습니다");
		context.waitFor(minecraft -> file.equals(ScreenshotClipboard.lastCopied()), 400);
		BufferedImage saved = readImage(file);
		BufferedImage copied = clipboardImage();
		check(copied != null && copied.getWidth() == saved.getWidth() && copied.getHeight() == saved.getHeight(),
			"클립보드의 그림이 스크린샷과 다릅니다: " + (copied == null ? "없음" : copied.getWidth() + "x" + copied.getHeight()));
		check(copied.getRGB(copied.getWidth() / 2, copied.getHeight() / 3) == (saved.getRGB(saved.getWidth() / 2, saved.getHeight() / 3) | 0xFF000000),
			"클립보드 그림의 내용이 스크린샷과 다릅니다");
		checkClipboardFromOutside(saved);

		// 스크린샷 폴더 밖의 파일이나 없는 파일은 복사하지 않는다.
		context.runOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);
			minecraft.player.connection.sendUnsignedCommand(ScreenshotModule.COPY_COMMAND + "../options.txt");
			minecraft.player.connection.sendUnsignedCommand(ScreenshotModule.COPY_COMMAND + "없는파일.png");
		});
		context.waitTicks(5);
		check(chatMessages(context).size() == 2 && file.equals(ScreenshotClipboard.lastCopied()), "잘못된 파일 이름을 거르지 못했습니다: " + chatMessages(context));

		// "찍으면 자동 복사"를 켜면 누르지 않아도 새 스크린샷이 클립보드에 들어간다.
		context.runOnClient(minecraft -> Modules.SCREENSHOT.autoCopy.set(true));
		// 파일 이름이 초 단위라서, 같은 이름이 되지 않게 1초 넘게 기다렸다가 찍는다.
		context.waitTicks(30);
		input.pressKey(GLFW.GLFW_KEY_F2);
		context.waitFor(minecraft -> ScreenshotClipboard.lastCopied() != null && !file.equals(ScreenshotClipboard.lastCopied()), 400);
		context.runOnClient(minecraft -> {
			Modules.SCREENSHOT.autoCopy.reset();
			minecraft.gui.getChat().clearMessages(false);
		});

		// 모듈을 끄면 바닐라 안내 그대로다.
		context.runOnClient(minecraft -> Modules.SCREENSHOT.setEnabled(false));
		context.waitTicks(30);
		input.pressKey(GLFW.GLFW_KEY_F2);
		context.waitFor(minecraft -> !readChat(minecraft).isEmpty(), 400);
		check(!chatMessages(context).get(0).contains(copyLabel), "모듈을 껐는데 [복사] 버튼이 붙었습니다");
		context.runOnClient(minecraft -> {
			Modules.SCREENSHOT.setEnabled(true);
			minecraft.gui.getChat().clearMessages(false);
		});
	}

	/** 지금 클립보드에 있는 그림. 그림이 아니면 null. */
	private static BufferedImage clipboardImage() {
		try {
			java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();

			if (!clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
				return null;
			}

			java.awt.Image image = (java.awt.Image) clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
			BufferedImage result = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D graphics = result.createGraphics();
			graphics.drawImage(image, 0, 0, null);
			graphics.dispose();
			return result;
		} catch (Exception e) {
			throw new AssertionError("클립보드를 읽을 수 없습니다", e);
		}
	}

	/** 다른 프로그램(xclip)이 클립보드에서 그림을 꺼낼 수 있는지 확인한다. xclip 이 없는 환경(Windows 등)에서는 건너뛴다. */
	private static void checkClipboardFromOutside(BufferedImage expected) {
		Path output;

		try {
			output = Files.createTempFile("cafeteria-clipboard", ".png");
			Process process = new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-o")
				.redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();

			if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
				process.destroyForcibly();
				throw new AssertionError("다른 프로그램이 클립보드에서 그림을 꺼내지 못했습니다");
			}
		} catch (java.io.IOException e) {
			CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: xclip 이 없어 게임 밖에서의 클립보드 확인은 건너뜁니다");
			return;
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}

		BufferedImage pasted = readImage(output);
		check(pasted != null && pasted.getWidth() == expected.getWidth() && pasted.getHeight() == expected.getHeight(),
			"다른 프로그램이 꺼낸 그림의 크기가 다릅니다");
		check((pasted.getRGB(pasted.getWidth() / 2, pasted.getHeight() / 3) | 0xFF000000) == (expected.getRGB(expected.getWidth() / 2, expected.getHeight() / 3) | 0xFF000000),
			"다른 프로그램이 꺼낸 그림의 내용이 다릅니다");
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 게임 밖 프로그램(xclip)에서 클립보드 그림 확인 완료 ({}x{})", pasted.getWidth(), pasted.getHeight());
	}

	/** HUD 편집 화면에서 지금 선택된 요소들. */
	private static Set<HudElement> selection(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> Set.copyOf(((HudEditScreen) minecraft.screen).selection()));
	}

	/** HUD 위치 편집: 빈 곳을 끌어 박스로 여러 개 선택하고 함께 옮긴다. */
	private static void testHudBoxSelection(ClientGameTestContext context, TestInput input) {
		context.setScreen(() -> new HudEditScreen(null));
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.FPS || element == Modules.CPS || element == Modules.PING);
			}

			Modules.FPS.setPosition(minecraft, 100, 100, minecraft.screen.width, minecraft.screen.height);
			Modules.CPS.setPosition(minecraft, 100, 120, minecraft.screen.width, minecraft.screen.height);
			Modules.PING.setPosition(minecraft, 300, 150, minecraft.screen.width, minecraft.screen.height);
		});

		// 1) 빈 곳에서 끌면 박스가 그려지고, 박스에 닿은 요소(FPS, CPS)만 선택된다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(90, 90, 0);
			minecraft.screen.mouseDragged(160, 135, 0, 0, 0);
		});
		context.takeScreenshot("hud-edit-box-select");
		context.runOnClient(minecraft -> minecraft.screen.mouseReleased(160, 135, 0));
		check(selection(context).equals(Set.of(Modules.FPS, Modules.CPS)), "박스 선택 결과가 다릅니다: " + names(selection(context)));

		// 2) 선택한 것 중 하나를 끌면 함께 움직이고, 선택하지 않은 요소는 그대로 있다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(103, 103, 0);
			minecraft.screen.mouseDragged(133, 123, 0, 0, 0);
			minecraft.screen.mouseReleased(133, 123, 0);
		});
		int[] fps = position(context, Modules.FPS);
		int[] cps = position(context, Modules.CPS);
		int[] ping = position(context, Modules.PING);
		check(fps[0] == 130 && fps[1] == 120 && cps[0] == 130 && cps[1] == 140, "선택한 요소들이 함께 움직이지 않았습니다: FPS " + fps[0] + "," + fps[1] + " CPS " + cps[0] + "," + cps[1]);
		check(ping[0] == 300 && ping[1] == 150, "선택하지 않은 요소가 움직였습니다: " + ping[0] + ", " + ping[1]);
		check(selection(context).size() == 2, "함께 끈 뒤에 선택이 풀렸습니다");
		context.takeScreenshot("hud-edit-group-moved");

		// 3) 방향키도 선택한 요소 전부를 옮긴다.
		input.pressKey(GLFW.GLFW_KEY_RIGHT);
		check(position(context, Modules.FPS)[0] == 131 && position(context, Modules.CPS)[0] == 131, "방향키가 선택한 요소 전부를 옮기지 않았습니다");

		// 4) 화면 밖으로 끌어도 묶음의 간격이 유지된 채 화면 안에 머문다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(134, 123, 0);
			minecraft.screen.mouseDragged(-900, -900, 0, 0, 0);
			minecraft.screen.mouseReleased(-900, -900, 0);
		});
		fps = position(context, Modules.FPS);
		cps = position(context, Modules.CPS);
		check(fps[0] == 0 && fps[1] == 0 && cps[0] == 0 && cps[1] == 20, "묶음을 화면 밖으로 끌었을 때 간격이 깨졌습니다: FPS " + fps[0] + "," + fps[1] + " CPS " + cps[0] + "," + cps[1]);

		// 5) Ctrl+클릭: 선택에 추가하고, 다시 누르면 뺀다.
		input.holdControl();
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(303, 153, 0);
			minecraft.screen.mouseReleased(303, 153, 0);
		});
		check(selection(context).equals(Set.of(Modules.FPS, Modules.CPS, Modules.PING)), "Ctrl+클릭으로 선택에 추가되지 않았습니다: " + names(selection(context)));
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(3, 23, 0);
			minecraft.screen.mouseReleased(3, 23, 0);
		});
		input.releaseControl();
		check(selection(context).equals(Set.of(Modules.FPS, Modules.PING)), "Ctrl+클릭으로 선택에서 빠지지 않았습니다: " + names(selection(context)));

		// 6) 여러 개 선택한 상태에서 그중 하나를 끌지 않고 누르면 그것만 선택된다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(303, 153, 0);
			minecraft.screen.mouseReleased(303, 153, 0);
		});
		check(selection(context).equals(Set.of(Modules.PING)), "하나만 눌렀는데 선택이 줄지 않았습니다: " + names(selection(context)));

		// 7) 빈 곳을 누르면 선택이 풀리고, Ctrl+A 는 전부 선택한다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(200, 200, 0);
			minecraft.screen.mouseReleased(200, 200, 0);
		});
		check(selection(context).isEmpty(), "빈 곳을 눌렀는데 선택이 남아 있습니다: " + names(selection(context)));
		input.holdControl();
		input.pressKey(GLFW.GLFW_KEY_A);
		input.releaseControl();
		check(selection(context).size() == 3, "Ctrl+A 로 전부 선택되지 않았습니다: " + names(selection(context)));
		context.takeScreenshot("hud-edit-select-all");

		context.clickScreenButton("cafeteria.hud_edit.reset");
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		// 기본 상태로 되돌린다: 좌표·콤보·리치만 꺼져 있고 나머지는 켜져 있다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element != Modules.COORDINATES && element != Modules.COMBO && element != Modules.REACH);
			}
		});
	}

	private static String names(Set<HudElement> elements) {
		return elements.stream().map(HudElement::id).sorted().toList().toString();
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
