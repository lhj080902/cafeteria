package io.github.lhj080902.cafeteria.test;

import com.mojang.authlib.GameProfile;
import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.gui.ContainerPreviewTooltip;
import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import io.github.lhj080902.cafeteria.gui.ModuleSettingsScreen;
import io.github.lhj080902.cafeteria.hud.ClickTracker;
import io.github.lhj080902.cafeteria.hud.CombatTracker;
import io.github.lhj080902.cafeteria.hud.HudElement;
import io.github.lhj080902.cafeteria.hud.InventoryHud;
import io.github.lhj080902.cafeteria.module.ContainerPreviewModule;
import io.github.lhj080902.cafeteria.module.HealthBarModule;
import io.github.lhj080902.cafeteria.module.NoFogModule;
import io.github.lhj080902.cafeteria.module.ScreenshotModule;
import io.github.lhj080902.cafeteria.render.HealthBarRenderer;
import io.github.lhj080902.cafeteria.util.ScreenshotClipboard;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestClientWorldContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.tooltip.BundleTooltip;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SeededContainerLoot;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
			testHudResizeHandles(context);
			testHudBoxSelection(context, input);
			testTitlePlacement(context, server);
			testScoreboardPlacement(context, server);
			testBossbarPlacement(context, server);
			testChatScale(context, input);
			testFullbright(context, input, server);
			testLowFire(context, server);
			testNoFog(context, input, server, world.getClientWorld());
			testInventoryHud(context, server);
			testContainerPreview(context, input, server);
			testHealthBar(context, input, server);
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
	 * 줌과 1인칭 손. 스크린샷의 픽셀로 확인한다 (주변 풍경에 없는 색인 자홍색 콘크리트를 손에 들려서 그 색이 차지하는 영역을 본다).
	 *  - 기본: 줌하는 동안 손이 화면 아래로 내려가 보이지 않고, 줌을 풀면 제자리로 돌아온다.
	 *  - "줌 중 손 숨기기"를 끄면: 손이 그대로 보이되 크기와 위치는 평소와 똑같다 (손은 확대되지 않는다).
	 */
	private static void testZoomKeepsHand(ClientGameTestContext context, TestInput input, TestServerContext server) {
		check(Modules.ZOOM.hideHand.get(), "줌 중 손 숨기기는 기본으로 켜져 있어야 합니다");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:magenta_concrete");
		// 손 위에 겹쳐 그려지는 HUD(키스트로크 등)가 비교에 섞이지 않게 잠시 끈다.
		List<HudElement> shown = context.computeOnClient(minecraft -> Modules.HUD.stream().filter(HudElement::isEnabled).toList());
		context.runOnClient(minecraft -> shown.forEach(element -> element.setEnabled(false)));
		// 아이템을 꺼내 드는 동작이 끝날 때까지 기다린다.
		context.waitTicks(30);
		int[] normal = magentaArea(context.takeScreenshot("hand-normal"));
		check(normal[0] > 2000, "손에 든 블록이 화면에 보이지 않습니다: " + normal[0] + "픽셀");

		// 1) 기본: 줌하면 손이 사라지고, 풀면 돌아온다.
		input.holdKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		check(near(zoomFactor(context), Modules.ZOOM.factor.get()), "줌이 걸리지 않았습니다");
		int[] hidden = magentaArea(context.takeScreenshot("hand-hidden-while-zooming"));
		check(hidden[0] == 0, "줌하는 동안 손이 화면에 남아 있습니다: " + hidden[0] + "픽셀");
		input.releaseKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		int[] back = magentaArea(context.takeScreenshot("hand-after-zoom"));
		check(java.util.Arrays.equals(back, normal), "줌을 풀었는데 손이 제자리로 돌아오지 않았습니다: " + java.util.Arrays.toString(normal) + " → " + java.util.Arrays.toString(back));

		// 줌이 걸리는 도중에는 손이 조금만 내려간 상태를 거친다 (갑자기 사라지지 않는다).
		check(near(context.computeOnClient(minecraft -> (double) Modules.ZOOM.handHiddenAmount()), 0.0), "줌을 풀었는데 손이 내려가 있습니다");

		// 2) 설정을 끄면 줌 중에도 손이 보이고, 크기와 위치는 평소와 똑같다.
		context.runOnClient(minecraft -> Modules.ZOOM.hideHand.set(false));
		input.holdKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		int[] zoomed = magentaArea(context.takeScreenshot("hand-kept-while-zooming"));
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 손 영역(픽셀 수, 왼쪽, 위, 오른쪽, 아래) 평소 {} / 줌(손 숨기기 끔) {}", java.util.Arrays.toString(normal), java.util.Arrays.toString(zoomed));
		check(Math.abs(zoomed[0] - normal[0]) <= normal[0] / 50, "줌을 하니 손의 크기가 바뀌었습니다: " + normal[0] + " → " + zoomed[0] + "픽셀");

		for (int i = 1; i < 5; i++) {
			check(Math.abs(zoomed[i] - normal[i]) <= 2, "줌을 하니 손의 위치가 바뀌었습니다: " + java.util.Arrays.toString(normal) + " → " + java.util.Arrays.toString(zoomed));
		}

		input.releaseKey(CafeteriaClient.zoomKey);
		context.waitTicks(20);
		context.runOnClient(minecraft -> {
			Modules.ZOOM.hideHand.reset();
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

	/** HUD 요소가 기본으로 켜져 있는지. 좌표·콤보·리치·인벤토리만 꺼져 있고 나머지는 켜져 있다. */
	private static boolean defaultHudEnabled(HudElement element) {
		return element != Modules.COORDINATES && element != Modules.COMBO && element != Modules.REACH && element != Modules.INVENTORY;
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
		// 기본 상태로 되돌린다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
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
		float pitchAfter = context.computeOnClient(minecraft -> minecraft.player.getXRot());
		check(near(pitchAfter, playerPitch), "프리룩 중에 캐릭터 고개가 움직였습니다");
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
		testPotionIcons(context, server);

		server.runCommand("effect clear @a");
		server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
		context.runOnClient(minecraft -> Modules.COORDINATES.setEnabled(false));
		context.waitTicks(5);

		// 속도·힘 효과에서 나온 입자는 내 주변 아무 데나 떠서 길면 40틱을 살기 때문에, 그대로 두면 뒤에서 화면을 재는 검사에 걸린다.
		// 클라이언트는 서버가 보내 준 "입자를 낼 효과" 목록이 빌 때까지 입자를 새로 만드므로, 먼저 효과를 지운 것이 여기까지 닿기를 기다린다.
		context.waitFor(minecraft -> {
			List<SynchedEntityData.DataValue<?>> changed = minecraft.player.getEntityData().getNonDefaultValues();
			return changed == null || changed.stream().noneMatch(value -> value.serializer() == EntityDataSerializers.PARTICLES);
		});
		// 이미 떠 있는 입자는 같은 월드를 다시 넣어서 없앤다. 입자 엔진은 이때 입자 목록만 비운다.
		int particles = context.computeOnClient(minecraft -> {
			int count = Integer.parseInt(minecraft.particleEngine.countParticles());
			minecraft.particleEngine.setLevel(minecraft.level);
			return count;
		});
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 효과를 지운 뒤에도 떠 있던 입자 {}개를 없앴습니다", particles);
	}

	/**
	 * 포션 효과 HUD: 효과 아이콘이 이름·시간 옆에 그려지고, 아이콘을 끄면 글자만 남는다.
	 * "기본 아이콘 숨김"을 켜면 화면 오른쪽 위의 바닐라 효과 아이콘이 사라진다.
	 * 속도 II·힘 효과가 걸려 있는 상태에서 부른다. 배경색이 비교에 섞이지 않도록 실명 효과로 화면을 검게 만든 뒤에 잰다.
	 */
	private static void testPotionIcons(ClientGameTestContext context, TestServerContext server) {
		check(Modules.POTIONS.icons.get(), "효과 아이콘은 기본으로 켜져 있어야 합니다");
		// 속도·힘 효과에서 나오는 입자는 내 주변 아무 데나 떠다녀서, 카메라 앞에 뜨면 아래에서 재는 자리를 덮는다.
		// 입자 설정을 "최소"로 두면 새로 생기지 않는다. 이미 떠 있던 것(수명 최대 40틱)은 아래에서 기다리는 동안 사라진다.
		ParticleStatus particles = context.computeOnClient(minecraft -> {
			ParticleStatus before = minecraft.options.particles().get();
			minecraft.options.particles().set(ParticleStatus.MINIMAL);
			return before;
		});
		server.runCommand("effect give @a minecraft:blindness 120 0 true");
		context.waitTicks(50);
		// 발전 과제 알림이 오른쪽 위를 가리지 않게 치우고, 다른 HUD 도 잠시 끈다.
		List<HudElement> others = context.computeOnClient(minecraft -> Modules.HUD.stream().filter(element -> element.isEnabled() && element != Modules.POTIONS).toList());
		int[] area = context.computeOnClient(minecraft -> {
			minecraft.getToastManager().clear();
			others.forEach(element -> element.setEnabled(false));
			int width = minecraft.getWindow().getGuiScaledWidth();
			int height = minecraft.getWindow().getGuiScaledHeight();
			// 미니맵 모드가 있어도 겹치지 않는 화면 위쪽 가운데 근처에 둔다.
			Modules.POTIONS.setPosition(minecraft, 150, 20, width, height);
			int scale = (int) minecraft.getWindow().getGuiScale();
			return new int[] {Modules.POTIONS.x(minecraft, width) * scale, Modules.POTIONS.y(minecraft, height) * scale, scale};
		});
		int scale = area[2];
		int withIconsHeight = context.computeOnClient(Modules.POTIONS::height);
		check(withIconsHeight == 3 * 20 + 6 - 2, "아이콘을 켠 포션 HUD 의 높이가 예상과 다릅니다: " + withIconsHeight);
		context.waitTicks(3);
		BufferedImage withIcons = readImage(context.takeScreenshot("potion-icons"));

		context.runOnClient(minecraft -> Modules.POTIONS.icons.set(false));
		check(context.computeOnClient(Modules.POTIONS::height) == 3 * 10 + 6 - 1, "아이콘을 끈 포션 HUD 의 높이가 예상과 다릅니다");
		context.waitTicks(3);
		BufferedImage withoutIcons = readImage(context.takeScreenshot("potion-text-only"));

		// 아이콘 자리(HUD 왼쪽의 18픽셀 폭, 세 줄)에 색이 있는 그림이 그려졌는지 본다. 글자와 배경은 무채색이다.
		int iconsOn = colorfulPixels(withIcons, area[0] + 3 * scale, area[1] + 3 * scale, 18 * scale, 58 * scale);
		int iconsOff = colorfulPixels(withoutIcons, area[0] + 3 * scale, area[1] + 3 * scale, 18 * scale, 58 * scale);
		// 바닐라 효과 아이콘(오른쪽 위)은 기본으로 그대로 둔다.
		int vanillaShown = brightPixels(withoutIcons, withoutIcons.getWidth() - 80 * scale, 0, 80 * scale, 52 * scale);

		context.runOnClient(minecraft -> {
			Modules.POTIONS.icons.reset();
			Modules.POTIONS.hideVanilla.set(true);
		});
		context.waitTicks(3);
		BufferedImage hidden = readImage(context.takeScreenshot("potion-vanilla-hidden"));
		int vanillaHidden = brightPixels(hidden, hidden.getWidth() - 80 * scale, 0, 80 * scale, 52 * scale);

		context.runOnClient(minecraft -> {
			Modules.POTIONS.hideVanilla.reset();
			Modules.POTIONS.resetPosition();
			others.forEach(element -> element.setEnabled(true));
			minecraft.options.particles().set(particles);
		});
		server.runCommand("effect clear @a minecraft:blindness");
		context.waitTicks(40);

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 포션 아이콘 자리의 색 있는 픽셀 — 아이콘 켬 {}, 끔 {} / 오른쪽 위 바닐라 아이콘의 밝은 픽셀 — 기본 {}, 숨김 {}",
			iconsOn, iconsOff, vanillaShown, vanillaHidden);
		check(iconsOn > 150, "효과 아이콘이 그려지지 않았습니다: " + iconsOn + "픽셀");
		check(iconsOff < iconsOn / 5, "아이콘을 껐는데도 아이콘 자리에 그림이 있습니다: " + iconsOff + "픽셀");
		check(vanillaShown > 500, "바닐라 효과 아이콘이 보이지 않아 비교할 수 없습니다: " + vanillaShown);
		check(vanillaHidden < vanillaShown / 10, "기본 아이콘 숨김을 켰는데 바닐라 아이콘이 남아 있습니다: " + vanillaHidden);
	}

	/** 영역 안에서 채도가 뚜렷한(무채색이 아닌) 픽셀 수. */
	private static int colorfulPixels(BufferedImage image, int left, int top, int width, int height) {
		int count = 0;

		for (int y = Math.max(0, top); y < Math.min(image.getHeight(), top + height); y++) {
			for (int x = Math.max(0, left); x < Math.min(image.getWidth(), left + width); x++) {
				int rgb = image.getRGB(x, y);
				int max = Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF));
				int min = Math.min(rgb >> 16 & 0xFF, Math.min(rgb >> 8 & 0xFF, rgb & 0xFF));

				if (max > 90 && max - min > max * 0.3) {
					count++;
				}
			}
		}

		return count;
	}

	/** 영역 안에서 어둡지 않은 픽셀 수. */
	private static int brightPixels(BufferedImage image, int left, int top, int width, int height) {
		int count = 0;

		for (int y = Math.max(0, top); y < Math.min(image.getHeight(), top + height); y++) {
			for (int x = Math.max(0, left); x < Math.min(image.getWidth(), left + width); x++) {
				int rgb = image.getRGB(x, y);

				if (Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF)) > 70) {
					count++;
				}
			}
		}

		return count;
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

		// 기능 묶음의 끝은 위 두 장 어디에도 찍히지 않는다. 기능 묶음의 마지막 카드가 목록 맨 아래에 닿을 만큼만 내려서 찍는다.
		// 내릴 거리를 그 카드의 위치에서 구하므로 카드가 늘어도 따라간다. 목록은 화면 위아래 6픽셀과 머리·바닥 30픽셀씩을 뺀 곳이다.
		int lastFeature = Modules.FEATURES.size() - 1;
		int listTop = 6 + 30;
		int listBottom = screenHeight - 6 - 30;
		int hidden = context.computeOnClient(minecraft -> ((CafeteriaScreen) minecraft.screen).cardY(lastFeature));
		context.runOnClient(minecraft -> minecraft.screen.mouseScrolled(100, 100, 0, -(hidden + 35 - listBottom) / 16.0));
		int shown = context.computeOnClient(minecraft -> ((CafeteriaScreen) minecraft.screen).cardY(lastFeature));
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 메뉴 — 기능 묶음 마지막 카드의 세로 위치 {} → {} (목록 {}~{})", hidden, shown, listTop, listBottom);
		check(hidden + 35 > listBottom && shown >= listTop && shown + 35 <= listBottom, "기능 묶음의 마지막 카드가 목록 안에 들어오지 않습니다: " + hidden + " → " + shown);
		context.takeScreenshot("menu-features-end");
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
		context.setScreen(() -> new ModuleSettingsScreen(null, Modules.NO_FOG));
		context.takeScreenshot("settings-no-fog");

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
				// 인벤토리 HUD 는 기본으로 꺼져 있다. 아래 검사의 배치가 달라지지 않게 여기서도 켜지 않는다 (testInventoryHud 가 따로 본다).
				element.setEnabled(element != Modules.INVENTORY);
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

		// 기본 상태로 되돌린다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
	}

	/** HUD 위치 편집: 꼭짓점 손잡이를 끌어 크기를 1% 단위로 바꾼다. 반대쪽 꼭짓점은 제자리에 있다. */
	private static void testHudResizeHandles(ClientGameTestContext context) {
		// 배율 계산: 고정된 꼭짓점에서의 가로·세로 거리 중 큰 쪽 비율을 따르고, 화면 여유와 범위를 넘지 않는다.
		check(exactly(HudEditScreen.resizeScale(30, 5, 20, 10, 1000, 1000, 0.5, 2.0), 1.5), "가로가 더 긴 끌기의 배율이 다릅니다");
		check(exactly(HudEditScreen.resizeScale(1, 1, 20, 10, 1000, 1000, 0.5, 2.0), 0.5), "최소 배율 아래로 내려갔습니다");
		check(exactly(HudEditScreen.resizeScale(500, 500, 20, 10, 1000, 1000, 0.5, 2.0), 2.0), "최대 배율을 넘었습니다");
		check(exactly(HudEditScreen.resizeScale(500, 500, 20, 10, 30, 1000, 0.5, 2.0), 1.5), "화면 여유보다 크게 커졌습니다");
		check(exactly(HudEditScreen.resizeScale(-40, -40, 20, 10, 1000, 1000, 0.5, 2.0), 0.5), "꼭짓점을 넘겨 끌었을 때 최소 배율이 아닙니다");

		context.setScreen(() -> new HudEditScreen(null));
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.FPS || element == Modules.CPS);
				element.scale.reset();
			}

			Modules.FPS.setPosition(minecraft, 100, 100, minecraft.screen.width, minecraft.screen.height);
			Modules.CPS.setPosition(minecraft, 300, 150, minecraft.screen.width, minecraft.screen.height);
		});
		int screenWidth = context.computeOnClient(minecraft -> minecraft.screen.width);
		int screenHeight = context.computeOnClient(minecraft -> minecraft.screen.height);
		int w = context.computeOnClient(Modules.FPS::width);
		int h = context.computeOnClient(Modules.FPS::height);

		// 1) 오른쪽 아래 손잡이를 1.37배 자리로 끈다: 배율이 1% 단위로 따라오고 왼쪽 위는 제자리.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(100 + w, 100 + h, 0);
			minecraft.screen.mouseDragged(100 + w * 1.37, 100 + h * 1.37, 0, 0, 0);
		});
		check(context.computeOnClient(minecraft -> ((HudEditScreen) minecraft.screen).isResizing()), "손잡이를 끄는 중인데 크기 조절 상태가 아닙니다");
		context.takeScreenshot("hud-edit-resize-handle");
		context.runOnClient(minecraft -> minecraft.screen.mouseReleased(100 + w * 1.37, 100 + h * 1.37, 0));
		check(!context.computeOnClient(minecraft -> ((HudEditScreen) minecraft.screen).isResizing()), "손잡이를 놓았는데 크기 조절 상태가 남았습니다");
		double grown = Modules.FPS.scale.get();
		check(Math.abs(grown - 1.37) < 0.011, "꼭짓점을 끈 만큼 커지지 않았습니다: " + grown);
		check(Math.abs(grown * 100 - Math.round(grown * 100)) < 1.0e-6, "크기가 1% 단위가 아닙니다: " + grown);
		int[] grownAt = position(context, Modules.FPS);
		check(grownAt[0] == 100 && grownAt[1] == 100, "오른쪽 아래를 끌었는데 왼쪽 위가 움직였습니다: " + grownAt[0] + ", " + grownAt[1]);

		// 2) 왼쪽 위 손잡이를 안쪽으로 끈다: 작아지고 오른쪽 아래는 제자리.
		int right = 100 + context.computeOnClient(Modules.FPS::width);
		int bottom = 100 + context.computeOnClient(Modules.FPS::height);
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(100, 100, 0);
			minecraft.screen.mouseDragged(100 + w * 0.2, 100 + h * 0.2, 0, 0, 0);
			minecraft.screen.mouseReleased(100 + w * 0.2, 100 + h * 0.2, 0);
		});
		double shrunk = Modules.FPS.scale.get();
		check(shrunk < grown - 0.1, "왼쪽 위를 안쪽으로 끌었는데 작아지지 않았습니다: " + shrunk);
		int[] shrunkAt = position(context, Modules.FPS);
		int shrunkRight = shrunkAt[0] + context.computeOnClient(Modules.FPS::width);
		int shrunkBottom = shrunkAt[1] + context.computeOnClient(Modules.FPS::height);
		check(Math.abs(shrunkRight - right) <= 1 && Math.abs(shrunkBottom - bottom) <= 1,
			"왼쪽 위를 끌었는데 오른쪽 아래가 움직였습니다: " + shrunkRight + ", " + shrunkBottom + " (기대 " + right + ", " + bottom + ")");

		// 3) 화면 밖까지 끌어도 범위(50~200%)와 화면 안에 머문다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(shrunkRight, shrunkBottom, 0);
			minecraft.screen.mouseDragged(9999, 9999, 0, 0, 0);
			minecraft.screen.mouseReleased(9999, 9999, 0);
		});
		double largest = Modules.FPS.scale.get();
		int[] largestAt = position(context, Modules.FPS);
		int largestWidth = context.computeOnClient(Modules.FPS::width);
		int largestHeight = context.computeOnClient(Modules.FPS::height);
		check(largest <= 2.0 + 1.0e-9, "최대 크기를 넘었습니다: " + largest);
		check(largestAt[0] == shrunkAt[0] && largestAt[1] == shrunkAt[1], "끝까지 키웠는데 왼쪽 위가 움직였습니다");
		check(largestAt[0] + largestWidth <= screenWidth && largestAt[1] + largestHeight <= screenHeight, "끌어서 화면 밖으로 커졌습니다");

		if ((screenWidth - shrunkAt[0]) >= w * 2 && (screenHeight - shrunkAt[1]) >= h * 2) {
			check(exactly(largest, 2.0), "자리가 넉넉한데 최대 크기까지 커지지 않았습니다: " + largest);
		}

		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(largestAt[0] + largestWidth, largestAt[1] + largestHeight, 0);
			minecraft.screen.mouseDragged(-500, -500, 0, 0, 0);
			minecraft.screen.mouseReleased(-500, -500, 0);
		});
		check(exactly(Modules.FPS.scale.get(), 0.5), "끝까지 줄였는데 최소 크기가 아닙니다: " + Modules.FPS.scale.get());

		// 4) 손잡이가 아닌 가운데를 끌면 예전처럼 이동하고 크기는 그대로다.
		context.runOnClient(minecraft -> {
			Modules.FPS.scale.reset();
			Modules.FPS.setPosition(minecraft, 100, 100, minecraft.screen.width, minecraft.screen.height);
			minecraft.screen.mouseClicked(100 + w / 2.0, 100 + h / 2.0, 0);
			minecraft.screen.mouseDragged(130 + w / 2.0, 120 + h / 2.0, 0, 0, 0);
			minecraft.screen.mouseReleased(130 + w / 2.0, 120 + h / 2.0, 0);
		});
		int[] dragged = position(context, Modules.FPS);
		check(dragged[0] == 130 && dragged[1] == 120, "가운데를 끌었는데 이동하지 않았습니다: " + dragged[0] + ", " + dragged[1]);
		check(exactly(Modules.FPS.scale.get(), 1.0), "가운데를 끌었는데 크기가 바뀌었습니다: " + Modules.FPS.scale.get());

		// 5) 여러 개를 선택한 상태에서 손잡이를 끌면 잡은 요소만 바뀌고 선택은 그대로다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(90, 90, 0);
			minecraft.screen.mouseDragged(380, 200, 0, 0, 0);
			minecraft.screen.mouseReleased(380, 200, 0);
		});
		check(selection(context).equals(Set.of(Modules.FPS, Modules.CPS)), "박스 선택 결과가 다릅니다: " + names(selection(context)));
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(130 + w, 120 + h, 0);
			minecraft.screen.mouseDragged(130 + w * 1.5, 120 + h * 1.5, 0, 0, 0);
			minecraft.screen.mouseReleased(130 + w * 1.5, 120 + h * 1.5, 0);
		});
		check(Math.abs(Modules.FPS.scale.get() - 1.5) < 0.011, "선택 묶음 안의 요소가 손잡이로 커지지 않았습니다: " + Modules.FPS.scale.get());
		check(exactly(Modules.CPS.scale.get(), 1.0), "잡지 않은 요소의 크기가 바뀌었습니다: " + Modules.CPS.scale.get());
		int[] other = position(context, Modules.CPS);
		check(other[0] == 300 && other[1] == 150, "잡지 않은 요소가 움직였습니다: " + other[0] + ", " + other[1]);
		check(selection(context).size() == 2, "손잡이를 끈 뒤에 선택이 바뀌었습니다: " + names(selection(context)));

		// 6) 설정 화면에 보이는 값은 소수 둘째 자리까지.
		context.runOnClient(minecraft -> Modules.FPS.scale.set(1.25));
		check(Modules.FPS.scale.format().equals("1.25x"), "크기 표시가 다릅니다: " + Modules.FPS.scale.format());

		context.runOnClient(minecraft -> {
			Modules.FPS.scale.reset();
			Modules.CPS.scale.reset();
		});
		context.clickScreenButton("cafeteria.hud_edit.reset");
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		// 기본 상태로 되돌린다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
	}

	/**
	 * 스크린샷에서 표시 색(밝은 청록, 채팅 색 이름으로 aqua)인 픽셀의 개수와 영역(왼쪽, 위, 오른쪽, 아래)을 GUI 좌표로 돌려준다.
	 * 바닐라가 그리는 타이틀·스코어보드·보스바·채팅이 실제로 어디에 얼마나 크게 그려졌는지 재는 데 쓴다.
	 */
	private static double[] markerBounds(ClientGameTestContext context, String name) {
		BufferedImage image = readImage(context.takeScreenshot(name));
		double scale = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScale());
		int count = 0;
		int left = Integer.MAX_VALUE;
		int top = Integer.MAX_VALUE;
		int right = -1;
		int bottom = -1;

		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				int rgb = image.getRGB(x, y);

				if ((rgb >> 16 & 0xFF) < 130 && (rgb >> 8 & 0xFF) > 200 && (rgb & 0xFF) > 200) {
					count++;
					left = Math.min(left, x);
					top = Math.min(top, y);
					right = Math.max(right, x);
					bottom = Math.max(bottom, y);
				}
			}
		}

		if (count == 0) {
			return new double[] {0, 0, 0, 0, 0};
		}

		return new double[] {count, left / scale, top / scale, (right + 1) / scale, (bottom + 1) / scale};
	}

	/** 요소가 차지하는 상자 (x, y, 너비, 높이). */
	private static int[] elementBox(ClientGameTestContext context, HudElement element) {
		return context.computeOnClient(minecraft -> {
			int width = minecraft.getWindow().getGuiScaledWidth();
			int height = minecraft.getWindow().getGuiScaledHeight();
			return new int[] {element.x(minecraft, width), element.y(minecraft, height), element.width(minecraft), element.height(minecraft)};
		});
	}

	/** 잰 영역이 요소의 상자 안에 있는지 (1픽셀까지는 반올림 차이로 본다). */
	private static boolean inside(double[] bounds, int[] box) {
		return bounds[0] > 0 && bounds[1] >= box[0] - 1 && bounds[2] >= box[1] - 1 && bounds[3] <= box[0] + box[2] + 1 && bounds[4] <= box[1] + box[3] + 1;
	}

	private static String describe(double[] bounds) {
		return java.util.Arrays.toString(bounds);
	}

	/** 스크린샷에서 GUI 좌표 (x, y) 둘레 3x3 픽셀의 평균 밝기 (0~255). */
	private static double brightnessAt(ClientGameTestContext context, BufferedImage image, double x, double y) {
		double scale = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScale());
		int centerX = (int) Math.round(x * scale);
		int centerY = (int) Math.round(y * scale);
		double sum = 0;

		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				sum += luminance(image.getRGB(Mth.clamp(centerX + dx, 0, image.getWidth() - 1), Mth.clamp(centerY + dy, 0, image.getHeight() - 1)));
			}
		}

		return sum / 9;
	}

	/** 스크린샷의 상자 안에서 흰색에 가까운 픽셀 수. 편집 화면의 예시 글자(흰색)가 그려졌는지 볼 때 쓴다. */
	private static int whitePixels(ClientGameTestContext context, BufferedImage image, int[] box) {
		int scale = context.computeOnClient(minecraft -> (int) minecraft.getWindow().getGuiScale());
		int count = 0;

		for (int y = Math.max(0, box[1] * scale); y < Math.min(image.getHeight(), (box[1] + box[3]) * scale); y++) {
			for (int x = Math.max(0, box[0] * scale); x < Math.min(image.getWidth(), (box[0] + box[2]) * scale); x++) {
				int rgb = image.getRGB(x, y);

				if ((rgb >> 16 & 0xFF) > 235 && (rgb >> 8 & 0xFF) > 235 && (rgb & 0xFF) > 235) {
					count++;
				}
			}
		}

		return count;
	}

	/**
	 * 타이틀: 기본 상태(옮기지 않음, 100%)에서는 바닐라와 똑같이 그려지고, 옮기거나 크기를 바꾸면 그 자리·크기로 그려진다.
	 * 타이틀이 떠 있지 않을 때 편집 화면에는 예시가 보인다.
	 */
	private static void testTitlePlacement(ClientGameTestContext context, TestServerContext server) {
		check(Modules.TITLE.isEnabled(), "타이틀 요소는 기본으로 켜져 있어야 합니다");
		check(!Modules.TITLE.isMoved() && exactly(Modules.TITLE.scale.get(), 1.0), "타이틀 요소가 기본 상태가 아닙니다");
		int width = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth());
		int height = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledHeight());
		// 앞의 갑옷 HUD 검사에서 입힌 다이아몬드 투구에는 표시 색(aqua) 범위에 드는 픽셀이 있고, 갑옷 HUD 가 그 아이템을 화면에 그린다.
		// 여기부터는 표시 색 픽셀을 재므로 갑옷을 벗긴다.
		server.runCommand("item replace entity @a armor.head with minecraft:air");
		server.runCommand("item replace entity @a armor.chest with minecraft:air");
		server.runCommand("item replace entity @a armor.feet with minecraft:air");
		context.waitTicks(5);
		check(!context.computeOnClient(Modules.ARMOR::hasContent), "갑옷을 벗겼는데 갑옷 HUD 가 남아 있습니다");
		double[] none = markerBounds(context, "title-none");
		check(none[0] == 0, "배경에 표시 색과 같은 픽셀이 있어서 잴 수 없습니다: " + describe(none));

		server.runCommand("title @a times 0 6000 0");
		server.runCommand("title @a title {\"text\":\"██\",\"color\":\"aqua\"}");
		context.waitTicks(10);

		// 1) 기본 상태는 요소를 끈 것(바닐라 그대로)과 픽셀까지 같다.
		context.runOnClient(minecraft -> Modules.TITLE.setEnabled(false));
		context.waitTicks(3);
		double[] vanilla = markerBounds(context, "title-vanilla");
		check(vanilla[0] > 200, "타이틀이 화면에 보이지 않습니다: " + describe(vanilla));
		context.runOnClient(minecraft -> Modules.TITLE.setEnabled(true));
		context.waitTicks(3);
		double[] normal = markerBounds(context, "title-default");
		check(java.util.Arrays.equals(normal, vanilla), "기본 상태의 타이틀이 바닐라와 다르게 그려졌습니다: " + describe(vanilla) + " → " + describe(normal));
		check(Math.abs((normal[1] + normal[3]) / 2 - width / 2.0) <= 5, "타이틀이 화면 가운데에 있지 않습니다: " + describe(normal));
		check(context.computeOnClient(Modules.TITLE::isLive), "화면에 뜬 타이틀을 요소가 알지 못합니다");
		int[] normalBox = elementBox(context, Modules.TITLE);
		check(inside(normal, normalBox), "타이틀이 요소 상자 밖에 그려졌습니다: " + describe(normal) + " / 상자 " + java.util.Arrays.toString(normalBox));

		// 2) 옮기지 않고 크기만 키우면 화면 가운데 정렬이 유지된다.
		context.runOnClient(minecraft -> Modules.TITLE.rescale(minecraft, 1.5, width, height));
		context.waitTicks(3);
		double[] grown = markerBounds(context, "title-grown");
		check(!Modules.TITLE.isMoved(), "크기만 바꿨는데 옮긴 것으로 처리됐습니다");
		check(Math.abs((grown[3] - grown[1]) - (normal[3] - normal[1]) * 1.5) <= 3, "타이틀이 1.5배가 되지 않았습니다: " + describe(normal) + " → " + describe(grown));
		check(Math.abs((grown[1] + grown[3]) / 2 - width / 2.0) <= 6, "크기를 키운 타이틀이 가운데에서 벗어났습니다: " + describe(grown));
		check(inside(grown, elementBox(context, Modules.TITLE)), "키운 타이틀이 요소 상자 밖에 그려졌습니다: " + describe(grown));

		// 3) 왼쪽 위로 옮기고 절반 크기로 줄이면 그 자리에 그 크기로 그려진다.
		context.runOnClient(minecraft -> {
			Modules.TITLE.scale.set(0.5);
			Modules.TITLE.setPosition(minecraft, 10, 10, width, height);
		});
		context.waitTicks(3);
		double[] placed = markerBounds(context, "title-moved-half");
		int[] placedBox = elementBox(context, Modules.TITLE);
		check(Modules.TITLE.isMoved(), "옮겼는데 옮긴 것으로 기록되지 않았습니다");
		check(placedBox[0] == 10 && placedBox[1] == 10, "타이틀 요소가 옮긴 자리에 있지 않습니다: " + java.util.Arrays.toString(placedBox));
		check(inside(placed, placedBox), "옮긴 타이틀이 요소 상자 밖에 그려졌습니다: " + describe(placed) + " / 상자 " + java.util.Arrays.toString(placedBox));
		check(placed[1] - 10 <= 4, "옮긴 타이틀이 상자의 왼쪽에 붙어 있지 않습니다: " + describe(placed));
		check(Math.abs((placed[3] - placed[1]) - (normal[3] - normal[1]) / 2) <= 2, "타이틀이 절반 크기가 아닙니다: " + describe(normal) + " → " + describe(placed));

		// 4) 창이 작아져도(다른 화면 크기) 옮긴 타이틀은 화면 안에 있다.
		int[] small = context.computeOnClient(minecraft -> new int[] {Modules.TITLE.x(minecraft, 200), Modules.TITLE.y(minecraft, 120), Modules.TITLE.width(minecraft), Modules.TITLE.height(minecraft)});
		check(small[0] >= 0 && small[1] >= 0 && small[0] <= Math.max(0, 200 - small[2]) && small[1] <= Math.max(0, 120 - small[3]),
			"작은 화면에서 타이틀이 화면 밖으로 나갑니다: " + java.util.Arrays.toString(small));

		// 5) 위치 초기화와 100%로 되돌리면 다시 바닐라와 같다.
		context.runOnClient(minecraft -> {
			Modules.TITLE.resetPosition();
			Modules.TITLE.scale.reset();
		});
		context.waitTicks(3);
		double[] restored = markerBounds(context, "title-restored");
		check(!Modules.TITLE.isMoved(), "위치를 초기화했는데 옮긴 상태가 남았습니다");
		check(java.util.Arrays.equals(restored, vanilla), "되돌린 타이틀이 바닐라와 다릅니다: " + describe(vanilla) + " → " + describe(restored));

		// 5-0) 접근성 설정의 "글자 배경"을 "모든 곳"으로 두면 바닐라가 타이틀 뒤에 어두운 배경을 깐다. 요소 상자는 그 배경까지 감싼다.
		//      배경은 글자(4배) 둘레로 2씩, 화면에서는 8픽셀씩 넓다.
		//      밝기를 재야 하므로 낮으로 맞춘다 (밤이면 배경이 어두워서 차이가 보이지 않는다).
		server.runCommand("time set noon");
		context.waitTicks(5);
		BufferedImage plainTitle = readImage(context.takeScreenshot("title-no-backdrop"));
		int[] plainBox = elementBox(context, Modules.TITLE);
		context.runOnClient(minecraft -> minecraft.options.backgroundForChatOnly().set(false));
		context.waitTicks(3);
		BufferedImage backedTitle = readImage(context.takeScreenshot("title-backdrop"));
		int[] backedBox = elementBox(context, Modules.TITLE);
		context.runOnClient(minecraft -> minecraft.options.backgroundForChatOnly().set(true));
		context.waitTicks(3);
		check(backedBox[0] == plainBox[0] - 8 && backedBox[1] == plainBox[1] - 8 && backedBox[2] == plainBox[2] + 16 && backedBox[3] == plainBox[3] + 16,
			"글자 배경을 켰을 때 타이틀 상자가 배경만큼 넓어지지 않았습니다: " + java.util.Arrays.toString(plainBox) + " → " + java.util.Arrays.toString(backedBox));
		// 상자의 네 변에서, 변 바로 안쪽(배경만 있는 곳)은 어두워지고 변 바로 바깥은 그대로여야 한다. 십자선을 피해서 가운데보다 20픽셀 왼쪽을 잰다.
		double middleY = backedBox[1] + backedBox[3] / 2.0;
		double sampleX = width / 2.0 - 20;
		double[][] edges = {
			{backedBox[0] + 3, middleY, backedBox[0] - 4, middleY},
			{backedBox[0] + backedBox[2] - 3, middleY, backedBox[0] + backedBox[2] + 4, middleY},
			{sampleX, backedBox[1] + 3, sampleX, backedBox[1] - 4},
			{sampleX, backedBox[1] + backedBox[3] - 3, sampleX, backedBox[1] + backedBox[3] + 4},
		};

		for (double[] edge : edges) {
			double insideBefore = brightnessAt(context, plainTitle, edge[0], edge[1]);
			double insideAfter = brightnessAt(context, backedTitle, edge[0], edge[1]);
			double outsideBefore = brightnessAt(context, plainTitle, edge[2], edge[3]);
			double outsideAfter = brightnessAt(context, backedTitle, edge[2], edge[3]);
			check(insideBefore >= 30, "타이틀 뒤의 화면이 너무 어두워서 글자 배경을 잴 수 없습니다: " + java.util.Arrays.toString(edge) + " 밝기 " + round(insideBefore));
			check(insideAfter <= insideBefore - 12, "타이틀 상자 안쪽인데 글자 배경이 그려지지 않았습니다: " + java.util.Arrays.toString(edge) + " 밝기 " + round(insideBefore) + " → " + round(insideAfter));
			check(Math.abs(outsideAfter - outsideBefore) <= 8, "타이틀 상자 바깥인데 글자 배경이 그려졌습니다: " + java.util.Arrays.toString(edge) + " 밝기 " + round(outsideBefore) + " → " + round(outsideAfter));
		}

		// 5-1) 원래 크기로는 화면보다 넓은 타이틀도, 옮기지 않고 화면에 들어가게 줄이면 가운데에 놓인다 (왼쪽에 붙지 않는다).
		// 타이틀의 양쪽 끝까지 재야 하므로 다른 HUD 는 잠시 끈다. HUD 는 타이틀보다 위에 그려지고,
		// 미니맵 모드가 있으면 HUD 기본 위치가 타이틀과 같은 높이로 내려와서 타이틀의 왼쪽 끝을 가린다.
		List<HudElement> overTitle = context.computeOnClient(minecraft -> Modules.HUD.stream().filter(element -> element.isEnabled() && element != Modules.TITLE).toList());
		context.runOnClient(minecraft -> overTitle.forEach(element -> element.setEnabled(false)));
		server.runCommand("title @a title {\"text\":\"███████████████\",\"color\":\"aqua\"}");
		context.waitTicks(10);
		int[] wideBox = elementBox(context, Modules.TITLE);
		check(wideBox[2] > width, "이 검사는 타이틀이 화면보다 넓어야 합니다: " + java.util.Arrays.toString(wideBox) + " / 화면 " + width);
		// 손대지 않은 상태(옮기지 않음, 100%)의 요소 상자는 바닐라가 그리는 자리 그대로다: 화면보다 넓으면 가운데에서 양쪽으로 넘친다.
		// (편집 화면의 테두리가 실제 그림과 달리 왼쪽 끝에 붙어 보이면 안 된다.)
		int wideTitleWidth = context.computeOnClient(minecraft -> minecraft.font.width("███████████████"));
		double[] wide = markerBounds(context, "title-wide");
		check(wideBox[0] < 0 && wideBox[0] == width / 2 + 4 * (-wideTitleWidth / 2),
			"화면보다 넓은 타이틀의 요소 상자가 바닐라가 그리는 자리와 다릅니다: " + java.util.Arrays.toString(wideBox) + " / 화면 " + width + ", 글자 너비 " + wideTitleWidth);
		check(wide[1] <= 8 && wide[3] >= width - 8, "화면보다 넓은 타이틀이 화면 양쪽 끝까지 그려지지 않았습니다: " + describe(wide));
		context.runOnClient(minecraft -> Modules.TITLE.rescale(minecraft, 0.7, width, height));
		context.waitTicks(3);
		double[] shrunk = markerBounds(context, "title-wide-shrunk");
		int[] shrunkBox = elementBox(context, Modules.TITLE);
		check(!Modules.TITLE.isMoved() && shrunkBox[2] <= width, "이 검사는 줄인 타이틀이 화면에 들어가야 합니다: " + java.util.Arrays.toString(shrunkBox) + " / 화면 " + width);
		check(Math.abs(shrunkBox[0] * 2 + shrunkBox[2] - width) <= 2, "화면보다 넓던 타이틀을 줄였는데 요소가 가운데에 놓이지 않았습니다: " + java.util.Arrays.toString(shrunkBox) + " / 화면 " + width);
		check(inside(shrunk, shrunkBox) && Math.abs((shrunk[1] + shrunk[3]) / 2 - width / 2.0) <= 4, "줄인 타이틀이 가운데에 그려지지 않았습니다: " + describe(shrunk) + " / 상자 " + java.util.Arrays.toString(shrunkBox));
		context.runOnClient(minecraft -> {
			Modules.TITLE.scale.reset();
			overTitle.forEach(element -> element.setEnabled(true));
		});
		context.waitTicks(3);

		// 6) 타이틀이 없을 때: 편집 화면에서만 예시가 보인다.
		server.runCommand("title @a clear");
		server.runCommand("title @a reset");
		context.waitTicks(5);
		check(markerBounds(context, "title-cleared")[0] == 0, "타이틀을 지웠는데 화면에 남아 있습니다");
		context.setScreen(() -> new HudEditScreen(null));
		context.waitTicks(3);
		check(!context.computeOnClient(Modules.TITLE::isLive), "타이틀이 없는데 떠 있다고 판단했습니다");
		int[] sampleBox = elementBox(context, Modules.TITLE);
		check(sampleBox[2] > 20 && sampleBox[3] >= 36, "편집 화면의 타이틀 예시 크기가 이상합니다: " + java.util.Arrays.toString(sampleBox));
		BufferedImage editing = readImage(context.takeScreenshot("hud-edit-title-sample"));
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		context.waitTicks(3);
		BufferedImage plain = readImage(context.takeScreenshot("title-no-sample"));
		int sampleWhite = whitePixels(context, editing, sampleBox);
		int plainWhite = whitePixels(context, plain, sampleBox);
		check(sampleWhite > plainWhite + 200, "편집 화면에 타이틀 예시가 보이지 않습니다: " + plainWhite + " → " + sampleWhite + "픽셀");
	}

	/**
	 * 스코어보드(화면 오른쪽): 기본 상태는 바닐라와 같고, 크기만 바꾸면 오른쪽 끝에 붙어 있으며,
	 * 편집 화면에서 꼭짓점을 끌면 반대쪽 꼭짓점이 제자리에 있고 그때부터 옮긴 것으로 기억한다.
	 */
	private static void testScoreboardPlacement(ClientGameTestContext context, TestServerContext server) {
		check(Modules.SCOREBOARD.isEnabled(), "스코어보드 요소는 기본으로 켜져 있어야 합니다");
		int width = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth());
		int height = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledHeight());
		// 오른쪽에 겹쳐 그려지는 HUD(갑옷 등)가 섞이지 않게 이 검사 동안에는 스코어보드 요소만 켜 둔다.
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.SCOREBOARD);
			}
		});
		context.waitTicks(3);
		check(markerBounds(context, "scoreboard-none")[0] == 0, "배경에 표시 색과 같은 픽셀이 있어서 잴 수 없습니다");
		server.runCommand("scoreboard objectives add cafe dummy {\"text\":\"███\",\"color\":\"aqua\"}");
		server.runCommand("scoreboard objectives setdisplay sidebar cafe");
		server.runCommand("scoreboard players set Alpha cafe 3");
		server.runCommand("scoreboard players set Beta cafe 2");
		context.waitTicks(10);

		// 1) 기본 상태는 요소를 끈 것(바닐라 그대로)과 같고, 화면 오른쪽 끝에 있다.
		context.runOnClient(minecraft -> Modules.SCOREBOARD.setEnabled(false));
		context.waitTicks(3);
		double[] vanilla = markerBounds(context, "scoreboard-vanilla");
		check(vanilla[0] > 50, "스코어보드가 화면에 보이지 않습니다: " + describe(vanilla));
		context.runOnClient(minecraft -> Modules.SCOREBOARD.setEnabled(true));
		context.waitTicks(3);
		double[] normal = markerBounds(context, "scoreboard-default");
		check(java.util.Arrays.equals(normal, vanilla), "기본 상태의 스코어보드가 바닐라와 다르게 그려졌습니다: " + describe(vanilla) + " → " + describe(normal));
		check(width - normal[3] <= 40, "스코어보드가 화면 오른쪽 끝에 있지 않습니다: " + describe(normal));
		check(context.computeOnClient(Modules.SCOREBOARD::isLive), "화면에 뜬 스코어보드를 요소가 알지 못합니다");
		int[] normalBox = elementBox(context, Modules.SCOREBOARD);
		check(inside(normal, normalBox), "스코어보드가 요소 상자 밖에 그려졌습니다: " + describe(normal) + " / 상자 " + java.util.Arrays.toString(normalBox));
		// 제목 줄(9) + 점수 두 줄(18) + 위아래 여백(1)
		check(normalBox[3] == 28 && normalBox[0] + normalBox[2] == width - 1, "스코어보드 상자가 바닐라 배치와 다릅니다: " + java.util.Arrays.toString(normalBox));

		// 2) 옮기지 않고 크기만 바꾸면(휠과 같은 경로) 오른쪽 끝에 붙은 채로 커진다.
		context.runOnClient(minecraft -> Modules.SCOREBOARD.rescale(minecraft, 1.5, width, height));
		context.waitTicks(3);
		double[] grown = markerBounds(context, "scoreboard-grown");
		int[] grownBox = elementBox(context, Modules.SCOREBOARD);
		check(!Modules.SCOREBOARD.isMoved(), "크기만 바꿨는데 옮긴 것으로 처리됐습니다");
		check(grownBox[0] + grownBox[2] >= width - 3 && grownBox[0] + grownBox[2] <= width, "크기를 키운 스코어보드가 오른쪽 끝에서 떨어졌습니다: " + java.util.Arrays.toString(grownBox));
		check(inside(grown, grownBox), "키운 스코어보드가 요소 상자 밖에 그려졌습니다: " + describe(grown) + " / 상자 " + java.util.Arrays.toString(grownBox));
		check(Math.abs((grown[4] - grown[2]) - (normal[4] - normal[2]) * 1.5) <= 2, "스코어보드가 1.5배가 되지 않았습니다: " + describe(normal) + " → " + describe(grown));
		context.runOnClient(minecraft -> Modules.SCOREBOARD.scale.reset());

		// 3) 편집 화면에서 왼쪽 아래 꼭짓점을 끌면 오른쪽 위 꼭짓점은 제자리에 있고, 그때부터 옮긴 것으로 기억한다.
		context.setScreen(() -> new HudEditScreen(null));
		context.waitTicks(3);
		check(context.computeOnClient(Modules.SCOREBOARD::isLive), "편집 화면에서 스코어보드를 예시로 바꿔 그렸습니다");
		int[] before = elementBox(context, Modules.SCOREBOARD);
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(before[0], before[1] + before[3], 0);
			minecraft.screen.mouseDragged(before[0] - before[2] * 0.3, before[1] + before[3] * 1.3, 0, 0, 0);
			minecraft.screen.mouseReleased(before[0] - before[2] * 0.3, before[1] + before[3] * 1.3, 0);
		});
		int[] dragged = elementBox(context, Modules.SCOREBOARD);
		check(Math.abs(Modules.SCOREBOARD.scale.get() - 1.3) < 0.03, "스코어보드가 꼭짓점을 끈 만큼 커지지 않았습니다: " + Modules.SCOREBOARD.scale.get());
		check(Modules.SCOREBOARD.isMoved(), "꼭짓점을 끌었는데 옮긴 것으로 기억하지 않습니다");
		check(Math.abs(dragged[0] + dragged[2] - (before[0] + before[2])) <= 1 && Math.abs(dragged[1] - before[1]) <= 1,
			"왼쪽 아래를 끌었는데 오른쪽 위가 움직였습니다: " + java.util.Arrays.toString(before) + " → " + java.util.Arrays.toString(dragged));
		context.waitTicks(3);
		check(inside(markerBounds(context, "hud-edit-scoreboard-resized"), dragged), "끌어서 키운 스코어보드가 요소 상자 밖에 그려졌습니다");
		context.clickScreenButton("cafeteria.hud_edit.reset");
		check(!Modules.SCOREBOARD.isMoved(), "위치 초기화를 눌렀는데 바닐라 자리로 돌아가지 않았습니다");
		context.runOnClient(minecraft -> Modules.SCOREBOARD.scale.reset());
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		context.waitTicks(3);
		check(java.util.Arrays.equals(markerBounds(context, "scoreboard-restored"), vanilla), "되돌린 스코어보드가 바닐라와 다릅니다");

		// 4) 왼쪽 위로 옮기면 그 자리에 그려진다.
		context.runOnClient(minecraft -> Modules.SCOREBOARD.setPosition(minecraft, 5, 5, width, height));
		context.waitTicks(3);
		double[] placed = markerBounds(context, "scoreboard-moved");
		int[] placedBox = elementBox(context, Modules.SCOREBOARD);
		check(placedBox[0] == 5 && placedBox[1] == 5, "스코어보드 요소가 옮긴 자리에 있지 않습니다: " + java.util.Arrays.toString(placedBox));
		check(inside(placed, placedBox), "옮긴 스코어보드가 요소 상자 밖에 그려졌습니다: " + describe(placed) + " / 상자 " + java.util.Arrays.toString(placedBox));

		// 5) 줄이 많고(바닐라는 15줄까지만 보여 준다) 200%여서 화면보다 커도 자리 계산이 깨지지 않는다.
		for (int i = 1; i <= 17; i++) {
			server.runCommand("scoreboard players set Line" + i + " cafe " + (10 + i));
		}

		context.waitTicks(10);
		context.runOnClient(minecraft -> Modules.SCOREBOARD.scale.set(2.0));
		int[] tall = elementBox(context, Modules.SCOREBOARD);
		check(tall[3] == (15 * 9 + 10) * 2, "스코어보드가 15줄 기준 높이가 아닙니다: " + java.util.Arrays.toString(tall));
		int[] cramped = context.computeOnClient(minecraft -> new int[] {Modules.SCOREBOARD.x(minecraft, 200), Modules.SCOREBOARD.y(minecraft, 120)});
		check(cramped[0] >= 0 && cramped[1] >= 0, "화면보다 큰 스코어보드의 자리가 화면 밖입니다: " + java.util.Arrays.toString(cramped));
		context.runOnClient(minecraft -> Modules.SCOREBOARD.resetPosition());
		cramped = context.computeOnClient(minecraft -> new int[] {Modules.SCOREBOARD.x(minecraft, 200), Modules.SCOREBOARD.y(minecraft, 120)});
		check(cramped[0] >= 0 && cramped[1] >= 0, "옮기지 않은 큰 스코어보드의 자리가 화면 밖입니다: " + java.util.Arrays.toString(cramped));
		context.waitTicks(3);
		context.takeScreenshot("scoreboard-tall");

		server.runCommand("scoreboard objectives remove cafe");
		context.waitTicks(5);
		context.runOnClient(minecraft -> {
			Modules.SCOREBOARD.scale.reset();
			Modules.SCOREBOARD.resetPosition();

			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
		check(markerBounds(context, "scoreboard-removed")[0] == 0, "스코어보드를 지웠는데 화면에 남아 있습니다");
		check(!context.computeOnClient(Modules.SCOREBOARD::isLive), "스코어보드가 없는데 떠 있다고 판단했습니다");

		// 6) 상자는 필요할 때만 구한다: 손대지 않은 상태(옮기지 않음, 100%)면 그리기 직전에는 구하지 않고, 누가 물어볼 때 한 번만 구한다.
		//    스코어보드의 상자는 점수 목록을 걸러서 정렬해야 나오므로, 매 프레임 구하면 바닐라가 하는 일을 한 번 더 하게 된다.
		String lazy = context.computeOnClient(minecraft -> {
			int[] calls = {0};
			java.util.function.Supplier<int[]> box = () -> {
				calls[0]++;
				return new int[] {width - 61, 50, 60, 40};
			};
			net.minecraft.client.gui.GuiGraphics graphics = new net.minecraft.client.gui.GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
			StringBuilder problems = new StringBuilder();

			if (Modules.SCOREBOARD.begin(graphics, box) || calls[0] != 0) {
				problems.append("손대지 않은 상태인데 그리기 직전에 상자를 구했습니다 (" + calls[0] + "번). ");
			}

			int x = Modules.SCOREBOARD.x(minecraft, width);
			int y = Modules.SCOREBOARD.y(minecraft, height);
			int boxWidth = Modules.SCOREBOARD.width(minecraft);

			if (calls[0] != 1 || x != width - 61 || y != 50 || boxWidth != 60) {
				problems.append("물어봤을 때 상자를 한 번만 구해야 합니다 (" + calls[0] + "번, x " + x + ", y " + y + ", 너비 " + boxWidth + "). ");
			}

			Modules.SCOREBOARD.scale.set(1.5);
			boolean transformed = Modules.SCOREBOARD.begin(graphics, box);

			if (transformed) {
				graphics.pose().popPose();
			}

			Modules.SCOREBOARD.scale.reset();

			if (!transformed || calls[0] != 2) {
				problems.append("크기를 바꿨으면 그리기 직전에 상자를 한 번 구해야 합니다 (" + calls[0] + "번). ");
			}

			return problems.toString();
		});
		check(lazy.isEmpty(), lazy);
		// 바닐라가 더 그리지 않으면 떠 있지 않은 것으로 돌아간다.
		context.waitTicks(5);
		check(!context.computeOnClient(Modules.SCOREBOARD::isLive), "그리기가 멈춘 스코어보드를 계속 떠 있다고 판단합니다");
	}

	/** 스크린샷의 상자 안에서 편집 화면 보스바 예시의 막대 색(자홍색)인 픽셀 수. */
	private static int sampleBarPixels(ClientGameTestContext context, BufferedImage image, int[] box) {
		int scale = context.computeOnClient(minecraft -> (int) minecraft.getWindow().getGuiScale());
		int count = 0;

		for (int y = Math.max(0, box[1] * scale); y < Math.min(image.getHeight(), (box[1] + box[3]) * scale); y++) {
			for (int x = Math.max(0, box[0] * scale); x < Math.min(image.getWidth(), (box[0] + box[2]) * scale); x++) {
				int rgb = image.getRGB(x, y);

				if ((rgb >> 16 & 0xFF) > 200 && (rgb >> 8 & 0xFF) < 60 && (rgb & 0xFF) > 140) {
					count++;
				}
			}
		}

		return count;
	}

	/**
	 * 보스바(화면 위): 기본 상태는 바닐라와 같고, 크기만 바꾸면 위쪽 가운데에 그대로 있으며, 옮기면 그 자리에 그려진다.
	 * 보스바가 여러 개면 전체가 한 덩어리다. 보스바가 없을 때 편집 화면에는 예시가 보인다.
	 */
	private static void testBossbarPlacement(ClientGameTestContext context, TestServerContext server) {
		check(Modules.BOSSBAR.isEnabled(), "보스바 요소는 기본으로 켜져 있어야 합니다");
		int width = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth());
		int height = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledHeight());
		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.BOSSBAR);
			}
		});
		context.waitTicks(3);
		check(markerBounds(context, "bossbar-none")[0] == 0, "배경에 표시 색과 같은 픽셀이 있어서 잴 수 없습니다");
		server.runCommand("bossbar add cafe:test {\"text\":\"███\",\"color\":\"aqua\"}");
		server.runCommand("bossbar set cafe:test players @a");
		context.waitTicks(10);

		// 1) 기본 상태는 요소를 끈 것(바닐라 그대로)과 같고, 화면 위쪽 가운데에 있다.
		context.runOnClient(minecraft -> Modules.BOSSBAR.setEnabled(false));
		context.waitTicks(3);
		double[] vanilla = markerBounds(context, "bossbar-vanilla");
		check(vanilla[0] > 50, "보스바 이름이 화면에 보이지 않습니다: " + describe(vanilla));
		context.runOnClient(minecraft -> Modules.BOSSBAR.setEnabled(true));
		context.waitTicks(3);
		double[] normal = markerBounds(context, "bossbar-default");
		check(java.util.Arrays.equals(normal, vanilla), "기본 상태의 보스바가 바닐라와 다르게 그려졌습니다: " + describe(vanilla) + " → " + describe(normal));
		check(Math.abs((normal[1] + normal[3]) / 2 - width / 2.0) <= 2 && normal[2] <= 20, "보스바가 화면 위쪽 가운데에 있지 않습니다: " + describe(normal));
		check(context.computeOnClient(Modules.BOSSBAR::isLive), "화면에 뜬 보스바를 요소가 알지 못합니다");
		int[] normalBox = elementBox(context, Modules.BOSSBAR);
		check(java.util.Arrays.equals(normalBox, new int[] {width / 2 - 91, 3, 182, 14}), "보스바 상자가 바닐라 배치와 다릅니다: " + java.util.Arrays.toString(normalBox));
		check(inside(normal, normalBox), "보스바 이름이 요소 상자 밖에 그려졌습니다: " + describe(normal));

		// 2) 보스바가 둘이면 둘을 합친 높이가 한 덩어리다.
		server.runCommand("bossbar add cafe:second {\"text\":\"second\"}");
		server.runCommand("bossbar set cafe:second players @a");
		context.waitTicks(10);
		int[] doubleBox = elementBox(context, Modules.BOSSBAR);
		check(doubleBox[3] == 19 * 2 - 5 && doubleBox[1] == 3, "보스바 두 개의 상자 높이가 다릅니다: " + java.util.Arrays.toString(doubleBox));
		server.runCommand("bossbar remove cafe:second");
		context.waitTicks(10);

		// 3) 옮기지 않고 크기만 바꾸면 위쪽 가운데에 그대로 있다.
		context.runOnClient(minecraft -> Modules.BOSSBAR.rescale(minecraft, 1.5, width, height));
		context.waitTicks(3);
		double[] grown = markerBounds(context, "bossbar-grown");
		int[] grownBox = elementBox(context, Modules.BOSSBAR);
		check(!Modules.BOSSBAR.isMoved(), "크기만 바꿨는데 옮긴 것으로 처리됐습니다");
		check(Math.abs((grown[1] + grown[3]) / 2 - width / 2.0) <= 3 && grownBox[1] <= 4, "크기를 키운 보스바가 위쪽 가운데에서 벗어났습니다: " + describe(grown) + " / 상자 " + java.util.Arrays.toString(grownBox));
		check(inside(grown, grownBox), "키운 보스바가 요소 상자 밖에 그려졌습니다: " + describe(grown) + " / 상자 " + java.util.Arrays.toString(grownBox));
		check(Math.abs((grown[3] - grown[1]) - (normal[3] - normal[1]) * 1.5) <= 2, "보스바가 1.5배가 되지 않았습니다: " + describe(normal) + " → " + describe(grown));

		// 4) 왼쪽 아래로 옮기고 절반 크기로 줄이면 그 자리에 그려진다.
		context.runOnClient(minecraft -> {
			Modules.BOSSBAR.scale.set(0.5);
			Modules.BOSSBAR.setPosition(minecraft, 20, height - 60, width, height);
		});
		context.waitTicks(3);
		double[] placed = markerBounds(context, "bossbar-moved-half");
		int[] placedBox = elementBox(context, Modules.BOSSBAR);
		check(placedBox[0] == 20 && placedBox[1] == height - 60 && placedBox[2] == 91, "보스바 요소가 옮긴 자리·크기가 아닙니다: " + java.util.Arrays.toString(placedBox));
		check(inside(placed, placedBox), "옮긴 보스바가 요소 상자 밖에 그려졌습니다: " + describe(placed) + " / 상자 " + java.util.Arrays.toString(placedBox));

		// 5) 보스바를 없애고 되돌린다. 보스바가 없을 때는 편집 화면에서만 예시가 보인다.
		server.runCommand("bossbar remove cafe:test");
		context.waitTicks(10);
		context.runOnClient(minecraft -> {
			Modules.BOSSBAR.scale.reset();
			Modules.BOSSBAR.resetPosition();
		});
		check(markerBounds(context, "bossbar-removed")[0] == 0, "보스바를 지웠는데 화면에 남아 있습니다");
		check(!context.computeOnClient(Modules.BOSSBAR::isLive), "보스바가 없는데 떠 있다고 판단했습니다");
		context.setScreen(() -> new HudEditScreen(null));
		context.waitTicks(3);
		int[] sampleBox = elementBox(context, Modules.BOSSBAR);
		check(sampleBox[1] == 3 && sampleBox[2] >= 182 && sampleBox[3] == 14, "편집 화면의 보스바 예시 상자가 이상합니다: " + java.util.Arrays.toString(sampleBox));
		BufferedImage editing = readImage(context.takeScreenshot("hud-edit-bossbar-sample"));
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		context.waitTicks(3);
		BufferedImage plain = readImage(context.takeScreenshot("bossbar-no-sample"));
		int sampleBar = sampleBarPixels(context, editing, sampleBox);
		int plainBar = sampleBarPixels(context, plain, sampleBox);
		check(sampleBar > 300 && plainBar == 0, "보스바 예시가 편집 화면에서만 보여야 합니다: 편집 화면 " + sampleBar + "픽셀, 평소 " + plainBar + "픽셀");

		context.runOnClient(minecraft -> {
			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
	}

	/** 채팅창을 비우고 표시 색(aqua) 꽉 찬 글자 한 메시지를 넣는다. 채팅은 10초 뒤에 흐려지므로 잴 때마다 새로 넣는다. */
	private static void showChatMarker(ClientGameTestContext context, int characters) {
		context.runOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);
			minecraft.gui.getChat().addMessage(Component.literal("█".repeat(characters)).withStyle(style -> style
				.withColor(net.minecraft.ChatFormatting.AQUA)
				.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/cafeteria-test"))));
		});
		context.waitTicks(3);
	}

	/** 기본 글꼴로 쟀을 때 너비가 정확히 target 픽셀이고 띄어쓰기가 없는 글자열. */
	private static String textOfWidth(ClientGameTestContext context, int target) {
		return context.computeOnClient(minecraft -> {
			int unit = minecraft.font.width("a");

			for (String tail : new String[] {"", "i", "l", "ii", "il", "ll", "iil"}) {
				int rest = target - minecraft.font.width(tail);

				if (rest >= 0 && rest % unit == 0) {
					String text = "a".repeat(rest / unit) + tail;

					if (minecraft.font.width(text) == target) {
						return text;
					}
				}
			}

			throw new AssertionError("너비가 " + target + "픽셀인 글자열을 만들 수 없습니다");
		});
	}

	/** 채팅창을 비우고 메시지 하나를 넣은 뒤, 그 메시지가 채팅창에서 몇 줄로 나뉘었는지 돌려준다. (필드 이름이 환경마다 달라서 형식으로 찾는다.) */
	private static int chatLines(ClientGameTestContext context, String text) {
		return context.computeOnClient(minecraft -> {
			ChatComponent chat = minecraft.gui.getChat();
			chat.clearMessages(false);
			chat.addMessage(Component.literal(text));
			return countChatLines(chat);
		});
	}

	private static int countChatLines(ChatComponent chat) {
		int lines = 0;

		try {
			for (Field field : ChatComponent.class.getDeclaredFields()) {
				if (!List.class.isAssignableFrom(field.getType())) {
					continue;
				}

				field.setAccessible(true);

				for (Object entry : (List<?>) field.get(chat)) {
					if (entry instanceof GuiMessage.Line) {
						lines++;
					}
				}
			}
		} catch (IllegalAccessException e) {
			throw new AssertionError("채팅 줄을 읽을 수 없습니다", e);
		}

		return lines;
	}

	/** 채팅의 (x, y) 자리에 있는 글자에 달린 클릭 명령. 없으면 null. 메서드 이름이 환경마다 달라서 형식(Style 을 돌려주고 double 두 개를 받는)으로 찾는다. */
	private static String chatClickValueAt(ClientGameTestContext context, double x, double y) {
		try {
			return context.computeOnClient(minecraft -> {
				for (Method method : ChatComponent.class.getDeclaredMethods()) {
					if (method.getReturnType() == net.minecraft.network.chat.Style.class && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] {double.class, double.class})) {
						method.setAccessible(true);
						net.minecraft.network.chat.Style style = (net.minecraft.network.chat.Style) method.invoke(minecraft.gui.getChat(), x, y);
						return style == null || style.getClickEvent() == null ? null : style.getClickEvent().getValue();
					}
				}

				throw new AssertionError("채팅에서 글자 스타일을 찾는 메서드가 없습니다");
			});
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("채팅에서 글자 스타일을 읽을 수 없습니다", e);
		}
	}

	/**
	 * 채팅 배율: 글자·줄 간격·폭·높이가 같은 비율로 바뀌고(한 줄에 들어가는 글자 수는 그대로), 왼쪽 아래는 제자리에 있다.
	 * 편집 화면에서 채팅은 옮길 수 없고 혼자서만 선택된다.
	 */
	private static void testChatScale(ClientGameTestContext context, TestInput input) {
		check(Modules.CHAT.isEnabled() && exactly(Modules.CHAT.scale.get(), 1.0), "채팅 요소는 기본으로 켜져 있고 100%여야 합니다");
		int width = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth());
		int height = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledHeight());
		int chatBottom = height - 40;
		context.runOnClient(minecraft -> {
			minecraft.gui.getChat().clearMessages(false);

			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.CHAT);
			}
		});
		context.waitTicks(3);
		check(markerBounds(context, "chat-none")[0] == 0, "배경에 표시 색과 같은 픽셀이 있어서 잴 수 없습니다");

		// 1) 기본 상태(100%)는 요소를 끈 것(바닐라 그대로)과 같다.
		context.runOnClient(minecraft -> Modules.CHAT.setEnabled(false));
		showChatMarker(context, 10);
		double[] vanilla = markerBounds(context, "chat-vanilla");
		check(vanilla[0] > 100, "채팅 글자가 화면에 보이지 않습니다: " + describe(vanilla));
		context.runOnClient(minecraft -> Modules.CHAT.setEnabled(true));
		showChatMarker(context, 10);
		double[] normal = markerBounds(context, "chat-default");
		check(java.util.Arrays.equals(normal, vanilla), "기본 상태의 채팅이 바닐라와 다르게 그려졌습니다: " + describe(vanilla) + " → " + describe(normal));
		check(inside(normal, elementBox(context, Modules.CHAT)), "채팅 글자가 채팅 요소 상자 밖에 있습니다: " + describe(normal) + " / 상자 " + java.util.Arrays.toString(elementBox(context, Modules.CHAT)));

		// 2) 200%: 글자의 너비·높이가 두 배가 되고, 채팅창의 왼쪽 아래(0, 화면 높이 - 40)를 기준으로 커진다.
		context.runOnClient(minecraft -> Modules.CHAT.scale.set(2.0));
		context.waitTicks(3);
		showChatMarker(context, 10);
		double[] doubled = markerBounds(context, "chat-double");
		check(Math.abs((doubled[3] - doubled[1]) - (normal[3] - normal[1]) * 2) <= 2 && Math.abs((doubled[4] - doubled[2]) - (normal[4] - normal[2]) * 2) <= 2,
			"채팅 글자가 두 배가 되지 않았습니다: " + describe(normal) + " → " + describe(doubled));
		check(Math.abs(doubled[1] - normal[1] * 2) <= 2 && Math.abs((chatBottom - doubled[4]) - (chatBottom - normal[4]) * 2) <= 2,
			"채팅이 왼쪽 아래를 기준으로 커지지 않았습니다: " + describe(normal) + " → " + describe(doubled));
		check(inside(doubled, elementBox(context, Modules.CHAT)), "키운 채팅 글자가 채팅 요소 상자 밖에 있습니다: " + describe(doubled));

		// 3) 채팅을 열었을 때 글자 위에서 클릭이 맞는 자리에 걸린다 (화면 좌표를 채팅 좌표로 바꾸는 쪽도 같은 배율을 쓴다).
		context.setScreen(() -> new ChatScreen(""));
		context.waitTicks(3);
		double[] focused = markerBounds(context, "chat-double-open");
		check(focused[0] > 100, "채팅을 열었는데 글자가 보이지 않습니다");
		String clicked = chatClickValueAt(context, (focused[1] + focused[3]) / 2, (focused[2] + focused[4]) / 2);
		check("/cafeteria-test".equals(clicked), "키운 채팅에서 글자 위의 클릭이 걸리지 않습니다: " + clicked);
		check(chatClickValueAt(context, (focused[1] + focused[3]) / 2, focused[2] - 30) == null, "글자가 없는 곳에서 클릭이 걸렸습니다");
		context.setScreen(() -> null);
		context.waitFor(minecraft -> minecraft.screen == null);

		// 4) 한 줄에 들어가는 글자 수는 그대로다: 여러 줄로 나뉘는 긴 메시지가 125%에서도 같은 줄 수로, 1.25배 크기로 보인다.
		context.runOnClient(minecraft -> Modules.CHAT.scale.set(1.0));
		context.waitTicks(3);
		showChatMarker(context, 110);
		double[] wrapped = markerBounds(context, "chat-wrapped");
		check(wrapped[4] - wrapped[2] > (normal[4] - normal[2]) * 2, "긴 메시지가 여러 줄로 나뉘지 않아서 비교할 수 없습니다: " + describe(wrapped));
		context.runOnClient(minecraft -> Modules.CHAT.scale.set(1.25));
		context.waitTicks(3);
		showChatMarker(context, 110);
		double[] wrappedLarger = markerBounds(context, "chat-wrapped-125");
		check(Math.abs((wrappedLarger[4] - wrappedLarger[2]) - (wrapped[4] - wrapped[2]) * 1.25) <= 3 && Math.abs((wrappedLarger[3] - wrappedLarger[1]) - (wrapped[3] - wrapped[1]) * 1.25) <= 3,
			"배율을 바꾸니 줄바꿈이 달라졌습니다: " + describe(wrapped) + " → " + describe(wrappedLarger));

		// 4-1) 줄바꿈 폭은 배율과 상관없이 100%일 때와 정확히 같다: 그 폭에 꼭 맞는 메시지는 어떤 배율에서도 한 줄이고, 1픽셀 넘는 메시지는 두 줄이다.
		//      (배율을 곱한 폭은 정수로 내림되므로 그 값을 다시 나누면 1~2픽셀 좁아진다. 그러면 꼭 맞는 메시지가 두 줄로 나뉜다.)
		boolean chatTime = Modules.CHAT_TIME.isEnabled();
		context.runOnClient(minecraft -> Modules.CHAT_TIME.setEnabled(false));
		int wrapWidth = context.computeOnClient(minecraft -> Mth.floor(ChatComponent.getWidth(minecraft.options.chatWidth().get()) / minecraft.options.chatScale().get()));
		String fits = textOfWidth(context, wrapWidth);
		String overflows = textOfWidth(context, wrapWidth + 1);

		for (double factor : new double[] {1.0, 0.53, 0.77, 1.37, 1.99}) {
			context.runOnClient(minecraft -> Modules.CHAT.scale.set(factor));
			context.waitTicks(3);
			int fitLines = chatLines(context, fits);
			int overflowLines = chatLines(context, overflows);
			check(fitLines == 1 && overflowLines == 2,
				"배율 " + factor + " 에서 줄바꿈 폭이 100%일 때(" + wrapWidth + ")와 다릅니다: 꼭 맞는 메시지 " + fitLines + "줄, 1픽셀 넘는 메시지 " + overflowLines + "줄");
		}

		context.runOnClient(minecraft -> {
			Modules.CHAT_TIME.setEnabled(chatTime);
			minecraft.gui.getChat().clearMessages(false);
		});

		// 5) 요소를 끄면 배율이 남아 있어도 바닐라 그대로다.
		context.runOnClient(minecraft -> {
			Modules.CHAT.scale.set(2.0);
			Modules.CHAT.setEnabled(false);
		});
		context.waitTicks(3);
		showChatMarker(context, 10);
		double[] disabled = markerBounds(context, "chat-disabled");
		check(java.util.Arrays.equals(disabled, vanilla), "채팅 요소를 껐는데 바닐라와 다릅니다: " + describe(vanilla) + " → " + describe(disabled));

		// 6) 편집 화면: 채팅은 혼자서만 선택되고 옮길 수 없다.
		context.runOnClient(minecraft -> {
			Modules.CHAT.scale.reset();
			minecraft.gui.getChat().clearMessages(false);

			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.CHAT || element == Modules.FPS || element == Modules.CPS);
			}
		});
		context.setScreen(() -> new HudEditScreen(null));
		context.runOnClient(minecraft -> {
			Modules.FPS.setPosition(minecraft, width - 70, 20, width, height);
			Modules.CPS.setPosition(minecraft, width - 70, 50, width, height);
		});
		context.waitTicks(3);
		int[] chatBox = elementBox(context, Modules.CHAT);
		check(chatBox[0] == 0 && chatBox[1] + chatBox[3] == chatBottom && chatBox[2] > 100 && chatBox[3] > 30, "채팅 요소 상자가 채팅창 자리가 아닙니다: " + java.util.Arrays.toString(chatBox));
		check(chatBox[0] + chatBox[2] < width - 75, "이 검사는 채팅창이 FPS·CPS 를 놓은 자리와 겹치지 않아야 합니다: " + java.util.Arrays.toString(chatBox));
		double insideX = chatBox[2] / 2.0;
		double insideY = chatBox[1] + chatBox[3] / 2.0;

		input.holdControl();
		input.pressKey(GLFW.GLFW_KEY_A);
		input.releaseControl();
		check(selection(context).equals(Set.of(Modules.FPS, Modules.CPS)), "Ctrl+A 에 채팅이 들어갔거나 다른 요소가 빠졌습니다: " + names(selection(context)));

		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(width - 2, 2, 0);
			minecraft.screen.mouseDragged(1, chatBottom - 2, 0, 0, 0);
			minecraft.screen.mouseReleased(1, chatBottom - 2, 0);
		});
		check(selection(context).equals(Set.of(Modules.FPS, Modules.CPS)), "화면 전체를 박스로 골랐는데 선택이 다릅니다: " + names(selection(context)));

		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(insideX, insideY, 0);
			minecraft.screen.mouseReleased(insideX, insideY, 0);
		});
		check(selection(context).equals(Set.of(Modules.CHAT)), "채팅창 안을 눌렀는데 채팅만 선택되지 않았습니다: " + names(selection(context)));
		context.takeScreenshot("hud-edit-chat-selected");

		// 채팅을 고른 채로 방향키를 누르거나 채팅창 안에서 끌어도 아무것도 움직이지 않는다.
		int[] fpsBefore = position(context, Modules.FPS);
		int[] cpsBefore = position(context, Modules.CPS);
		input.pressKey(GLFW.GLFW_KEY_RIGHT);
		check(selection(context).equals(Set.of(Modules.CHAT)), "방향키를 눌렀더니 채팅 선택이 풀렸습니다");
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(insideX, insideY, 0);
			minecraft.screen.mouseDragged(insideX + 40, insideY - 20, 0, 0, 0);
			minecraft.screen.mouseReleased(insideX + 40, insideY - 20, 0);
		});
		check(java.util.Arrays.equals(elementBox(context, Modules.CHAT), chatBox), "채팅창이 움직였습니다: " + java.util.Arrays.toString(elementBox(context, Modules.CHAT)));
		check(java.util.Arrays.equals(position(context, Modules.FPS), fpsBefore) && java.util.Arrays.equals(position(context, Modules.CPS), cpsBefore), "채팅을 끌었는데 다른 요소가 움직였습니다");
		check(!selection(context).contains(Modules.CHAT), "채팅창 안에서 끌어 박스 선택을 했는데 채팅이 선택에 남았습니다: " + names(selection(context)));

		// Ctrl+클릭으로는 채팅이 선택에 섞이지 않는다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(width - 67, 23, 0);
			minecraft.screen.mouseReleased(width - 67, 23, 0);
		});
		check(selection(context).equals(Set.of(Modules.FPS)), "FPS 를 눌렀는데 선택이 다릅니다: " + names(selection(context)));
		input.holdControl();
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(insideX, insideY, 0);
			minecraft.screen.mouseReleased(insideX, insideY, 0);
		});
		input.releaseControl();
		check(selection(context).equals(Set.of(Modules.FPS)), "Ctrl+클릭으로 채팅이 선택에 섞였습니다: " + names(selection(context)));

		// 채팅을 고른 뒤 다른 요소를 Ctrl+클릭하면 채팅은 선택에서 빠진다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(insideX, insideY, 0);
			minecraft.screen.mouseReleased(insideX, insideY, 0);
		});
		input.holdControl();
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(width - 67, 53, 0);
			minecraft.screen.mouseReleased(width - 67, 53, 0);
		});
		input.releaseControl();
		check(selection(context).equals(Set.of(Modules.CPS)), "채팅과 다른 요소가 함께 선택됐습니다: " + names(selection(context)));

		// 채팅창 자리에 놓인 다른 요소는 채팅보다 위에 있다: 눌러서 고르고 끌 수 있고, 휠도 채팅이 아니라 그 요소에 걸린다.
		context.runOnClient(minecraft -> {
			Modules.SCOREBOARD.setEnabled(true);
			Modules.SCOREBOARD.setPosition(minecraft, chatBox[0] + 4, chatBox[1] + 4, width, height);
		});
		context.waitTicks(3);
		int[] boardBox = elementBox(context, Modules.SCOREBOARD);
		check(boardBox[0] >= chatBox[0] && boardBox[1] >= chatBox[1] && boardBox[0] + boardBox[2] + 12 <= chatBox[0] + chatBox[2] && boardBox[1] + boardBox[3] + 6 <= chatBox[1] + chatBox[3],
			"이 검사는 스코어보드 예시가 채팅창 안에 들어가야 합니다: " + java.util.Arrays.toString(boardBox) + " / 채팅 " + java.util.Arrays.toString(chatBox));
		double boardX = boardBox[0] + boardBox[2] / 2.0;
		double boardY = boardBox[1] + boardBox[3] / 2.0;
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(boardX, boardY, 0);
			minecraft.screen.mouseDragged(boardX + 12, boardY + 6, 0, 0, 0);
			minecraft.screen.mouseReleased(boardX + 12, boardY + 6, 0);
		});
		int[] draggedBoard = elementBox(context, Modules.SCOREBOARD);
		check(selection(context).equals(Set.of(Modules.SCOREBOARD)), "채팅창 자리에 놓인 스코어보드를 눌렀는데 그것이 선택되지 않았습니다: " + names(selection(context)));
		check(Math.abs(draggedBoard[0] - boardBox[0] - 12) <= 1 && Math.abs(draggedBoard[1] - boardBox[1] - 6) <= 1,
			"채팅창 자리에 놓인 스코어보드가 끌리지 않았습니다: " + java.util.Arrays.toString(boardBox) + " → " + java.util.Arrays.toString(draggedBoard));
		context.runOnClient(minecraft -> minecraft.screen.mouseScrolled(boardX + 12, boardY + 6, 0, 1));
		check(Math.abs(Modules.SCOREBOARD.scale.get() - 1.1) < 0.02 && exactly(Modules.CHAT.scale.get(), 1.0),
			"채팅창 자리에 놓인 스코어보드 위에서 휠을 굴렸는데 크기가 다릅니다: 스코어보드 " + Modules.SCOREBOARD.scale.get() + ", 채팅 " + Modules.CHAT.scale.get());
		context.takeScreenshot("hud-edit-scoreboard-over-chat");
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(width - 67, 23, 0);
			minecraft.screen.mouseReleased(width - 67, 23, 0);
			Modules.SCOREBOARD.resetPosition();
			Modules.SCOREBOARD.scale.reset();
			Modules.SCOREBOARD.setEnabled(false);
		});

		// 오른쪽 위 손잡이를 끌면 왼쪽 아래를 기준으로 커지고, 채팅만 선택된다.
		context.runOnClient(minecraft -> {
			minecraft.screen.mouseClicked(chatBox[0] + chatBox[2], chatBox[1], 0);
			minecraft.screen.mouseDragged(chatBox[2] * 1.2, chatBox[1] - chatBox[3] * 0.2, 0, 0, 0);
		});
		check(context.computeOnClient(minecraft -> ((HudEditScreen) minecraft.screen).isResizing()), "채팅의 오른쪽 위 손잡이가 잡히지 않았습니다");
		context.runOnClient(minecraft -> minecraft.screen.mouseReleased(chatBox[2] * 1.2, chatBox[1] - chatBox[3] * 0.2, 0));
		int[] resized = elementBox(context, Modules.CHAT);
		check(Math.abs(Modules.CHAT.scale.get() - 1.2) < 0.02, "채팅이 꼭짓점을 끈 만큼 커지지 않았습니다: " + Modules.CHAT.scale.get());
		check(resized[0] == 0 && resized[1] + resized[3] == chatBottom, "채팅을 키웠는데 왼쪽 아래가 움직였습니다: " + java.util.Arrays.toString(resized));
		check(selection(context).equals(Set.of(Modules.CHAT)), "채팅 손잡이를 끌었는데 채팅만 선택되지 않았습니다: " + names(selection(context)));
		// 왼쪽 위에는 손잡이가 없다: 그 자리를 눌러도 크기 조절이 시작되지 않는다.
		context.runOnClient(minecraft -> minecraft.screen.mouseClicked(resized[0], resized[1], 0));
		check(!context.computeOnClient(minecraft -> ((HudEditScreen) minecraft.screen).isResizing()), "채팅의 왼쪽 위에서 크기 조절이 시작됐습니다");
		context.runOnClient(minecraft -> minecraft.screen.mouseReleased(resized[0], resized[1], 0));

		// 휠도 채팅 배율을 바꾸고, 위치 초기화는 채팅을 건드리지 않는다.
		context.runOnClient(minecraft -> minecraft.screen.mouseScrolled(insideX, insideY, 0, 1));
		check(Math.abs(Modules.CHAT.scale.get() - 1.3) < 0.02, "휠로 채팅 배율이 바뀌지 않았습니다: " + Modules.CHAT.scale.get());
		context.clickScreenButton("cafeteria.hud_edit.reset");
		int[] afterReset = elementBox(context, Modules.CHAT);
		check(Math.abs(Modules.CHAT.scale.get() - 1.3) < 0.02 && afterReset[0] == 0 && afterReset[1] + afterReset[3] == chatBottom, "위치 초기화가 채팅을 건드렸습니다");
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);

		context.runOnClient(minecraft -> {
			Modules.CHAT.scale.reset();
			minecraft.gui.getChat().clearMessages(false);

			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
		context.waitTicks(3);
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

	/**
	 * 안개 제거: 공중에 통로를 만들어 26.5칸 앞 끝에 연두색 벽을 세우고, 통로를 용암·물로 채운 채 화면 한가운데의 색을 잰다.
	 * 끄면 벽이 안개에 묻히고, 켜면 공기 중에서 본 색 그대로여야 한다 (물속 무늬가 남아 있으면 파란색이 10 가까이 올라간다).
	 * 통로를 바다 랜턴으로 만들어 블록 밝기가 최대로 고정되므로 공기·물·용암 속에서 벽의 밝기가 같다.
	 */
	private static void testNoFog(ClientGameTestContext context, TestInput input, TestServerContext server, TestClientWorldContext clientWorld) {
		check(!Modules.NO_FOG.isEnabled(), "안개 제거는 기본으로 꺼져 있어야 합니다");
		String here = "execute at @a run ";
		String channel = "fill ~ ~0.5 ~-26 ~ ~2.5 ~ minecraft:";

		// 블록 한가운데에서 북쪽을 보고, 20칸 위에 만든 바닥으로 올라간다. 땅 위의 주민·돼지가 끼어들지 않는다.
		server.runCommand("effect give @a minecraft:fire_resistance 120 0 true");
		server.runCommand("execute as @a at @s align xyz run tp @s ~0.5 ~ ~0.5 180 0");
		server.runCommand(here + "fill ~-1 ~19.5 ~-27 ~1 ~19.5 ~1 minecraft:sea_lantern");
		server.runCommand("execute as @a at @s run tp @s ~ ~20 ~ 180 0");
		server.runCommand(here + "fill ~-1 ~0.5 ~-27 ~-1 ~2.5 ~1 minecraft:sea_lantern");
		server.runCommand(here + "fill ~1 ~0.5 ~-27 ~1 ~2.5 ~1 minecraft:sea_lantern");
		server.runCommand(here + "fill ~ ~0.5 ~1 ~ ~2.5 ~1 minecraft:sea_lantern");
		server.runCommand(here + "fill ~ ~0.5 ~-27 ~ ~2.5 ~-27 minecraft:lime_concrete");
		// 조준점과 물속·용암의 입자가 화면 한가운데를 가리지 않게 한다.
		ParticleStatus particles = context.computeOnClient(minecraft -> {
			ParticleStatus before = minecraft.options.particles().get();
			minecraft.options.particles().set(ParticleStatus.MINIMAL);
			minecraft.options.hideGui = true;
			return before;
		});
		context.waitTicks(5);
		clientWorld.waitForChunksRender();
		int[] wall = centerColor(context, "fog-wall-air");
		check(isLime(wall), "안개 검사용 벽이 화면 가운데에 보이지 않습니다: " + Arrays.toString(wall));

		// 1) 용암 속. 몸에 붙은 불꽃이 화면 한가운데까지 올라오므로 낮은 화염으로 내려 둔다.
		context.runOnClient(minecraft -> {
			Modules.LOW_FIRE.setEnabled(true);
			Modules.LOW_FIRE.amount.set(100.0);
		});
		server.runCommand(here + channel + "lava");
		context.waitTicks(5);
		clientWorld.waitForChunksRender();
		int[] lavaFog = centerColor(context, "fog-lava-normal");
		check(lavaFog[0] > lavaFog[1] + 60, "용암 속인데 벽이 안개에 묻히지 않았습니다: " + Arrays.toString(lavaFog));
		context.runOnClient(minecraft -> Modules.NO_FOG.setEnabled(true));
		nudgeCamera(context, input);
		int[] lavaClear = centerColor(context, "fog-lava-clear");
		check(sameColor(lavaClear, wall), "용암 속에서 벽이 보이지 않습니다: " + Arrays.toString(lavaClear) + " / 공기 중 " + Arrays.toString(wall));
		checkEffectFogKept(context, server, MobEffects.BLINDNESS, "blindness", "용암 속 실명");
		checkEffectFogKept(context, server, MobEffects.DARKNESS, "darkness", "용암 속 어둠");
		context.runOnClient(minecraft -> Modules.NO_FOG.lava.set(false));
		context.waitTicks(3);
		int[] lavaOff = centerColor(context, "fog-lava-switch-off");
		check(!isLime(lavaOff), "용암 스위치를 껐는데 용암 속이 맑게 보입니다: " + Arrays.toString(lavaOff));
		context.runOnClient(minecraft -> {
			Modules.NO_FOG.lava.reset();
			Modules.NO_FOG.setEnabled(false);
			Modules.LOW_FIRE.setEnabled(false);
			Modules.LOW_FIRE.amount.reset();
		});

		// 2) 물속. 물속 시야는 들어간 뒤 시간이 지날수록 넓어지므로, 끈 상태는 들어가자마자 잰다.
		server.runCommand(here + channel + "water");
		context.waitTicks(5);
		clientWorld.waitForChunksRender();
		int[] waterFog = centerColor(context, "fog-water-normal");
		check(waterFog[2] > waterFog[1] + 20, "물속인데 벽이 안개에 묻히지 않았습니다: " + Arrays.toString(waterFog));
		context.runOnClient(minecraft -> Modules.NO_FOG.setEnabled(true));
		nudgeCamera(context, input);
		int[] waterClear = centerColor(context, "fog-water-clear");
		check(isLime(waterClear), "물속에서 벽이 보이지 않습니다: " + Arrays.toString(waterClear));
		check(sameColor(waterClear, wall), "물속 무늬가 남아 있습니다: " + Arrays.toString(waterClear) + " / 공기 중 " + Arrays.toString(wall));
		checkEffectFogKept(context, server, MobEffects.BLINDNESS, "blindness", "물속 실명");
		checkEffectFogKept(context, server, MobEffects.DARKNESS, "darkness", "물속 어둠");
		context.runOnClient(minecraft -> Modules.NO_FOG.water.set(false));
		context.waitTicks(3);
		int[] waterOff = centerColor(context, "fog-water-switch-off");
		check(!isLime(waterOff), "물속 스위치를 껐는데 물속이 맑게 보입니다: " + Arrays.toString(waterOff));
		context.runOnClient(minecraft -> Modules.NO_FOG.water.reset());
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 안개 검사 색 - 공기 {} / 용암 {} → {} / 물 {} → {}",
			Arrays.toString(wall), Arrays.toString(lavaFog), Arrays.toString(lavaClear), Arrays.toString(waterFog), Arrays.toString(waterClear));

		// 3) 거리 안개. 바닐라가 쓰게 될 값을 직접 확인한다 (렌더 거리 192칸이면 바닐라는 172.8 ~ 192).
		server.runCommand(here + channel + "air");
		context.waitTicks(10);
		FogParameters cleared = fog(context, FogRenderer.FogMode.FOG_TERRAIN);
		check(cleared.start() >= NoFogModule.FAR_START, "거리 안개가 없어지지 않았습니다: " + cleared);
		FogParameters sky = fog(context, FogRenderer.FogMode.FOG_SKY);
		check(sky.end() <= 192.0F, "하늘의 안개까지 없어졌습니다: " + sky);
		context.runOnClient(minecraft -> Modules.NO_FOG.distance.set(false));
		FogParameters kept = fog(context, FogRenderer.FogMode.FOG_TERRAIN);
		check(kept.end() <= 192.0F, "거리 스위치를 껐는데 안개가 없어졌습니다: " + kept);
		context.runOnClient(minecraft -> Modules.NO_FOG.distance.reset());

		context.runOnClient(minecraft -> {
			Modules.NO_FOG.setEnabled(false);
			Modules.NO_FOG.resetSettings();
			minecraft.options.hideGui = false;
			minecraft.options.particles().set(particles);
		});
		server.runCommand("effect clear @a minecraft:fire_resistance");
		server.runCommand("execute as @a at @s run tp @s ~ ~-20 ~");
		server.runCommand(here + "fill ~-1 ~19.5 ~-27 ~1 ~22.5 ~1 minecraft:air");
		context.waitTicks(5);
	}

	/** 스크린샷을 찍어 화면 한가운데 5x5 픽셀의 평균 색(빨강, 초록, 파랑)을 돌려준다. */
	private static int[] centerColor(ClientGameTestContext context, String name) {
		BufferedImage image = readImage(context.takeScreenshot(name));
		int[] sum = new int[3];

		for (int y = -2; y <= 2; y++) {
			for (int x = -2; x <= 2; x++) {
				int rgb = image.getRGB(image.getWidth() / 2 + x, image.getHeight() / 2 + y);
				sum[0] += rgb >> 16 & 0xFF;
				sum[1] += rgb >> 8 & 0xFF;
				sum[2] += rgb & 0xFF;
			}
		}

		return new int[] {sum[0] / 25, sum[1] / 25, sum[2] / 25};
	}

	/**
	 * 카메라를 아주 조금(1픽셀) 돌렸다가 되돌린다. Sodium 은 카메라가 움직여야 그릴 청크 목록을 다시 만들어서,
	 * 가만히 선 채로 안개만 걷히면 안개 밖에 있던 청크가 바로 나타나지 않는다 (0.6.13 에서 확인).
	 * 화면을 닫은 뒤의 첫 이동은 바닐라가 무시해서 한쪽으로만 돌아갈 수도 있다. 벽 색이 고르므로 재는 값은 같다.
	 */
	private static void nudgeCamera(ClientGameTestContext context, TestInput input) {
		input.moveCursor(1.0, 0.0);
		context.waitTicks(2);
		input.moveCursor(-1.0, 0.0);
		context.waitTicks(8);
	}

	/**
	 * 안개 검사용 벽(연두색 콘크리트)의 색인지. 통로 끝이라 모서리 그늘이 져서 [44, 79, 11] 쯤으로 보인다.
	 * 안개 색(주황, 파랑)이나 하늘색과는 겹치지 않는다.
	 */
	private static boolean isLime(int[] color) {
		return color[1] > color[0] + 20 && color[1] > color[2] + 40;
	}

	private static boolean sameColor(int[] actual, int[] expected) {
		return Math.abs(actual[0] - expected[0]) <= 4 && Math.abs(actual[1] - expected[1]) <= 4 && Math.abs(actual[2] - expected[2]) <= 4;
	}

	/** 이 효과를 받은 동안에는 안개 제거가 켜져 있어도 바닐라의 안개 그대로인지 확인한다 (실명은 끝 거리 5칸, 어둠은 15칸 이상). */
	private static void checkEffectFogKept(ClientGameTestContext context, TestServerContext server, Holder<MobEffect> effect, String id, String label) {
		server.runCommand("effect give @a minecraft:" + id + " 30 0 true");
		context.waitFor(minecraft -> minecraft.player.hasEffect(effect), 200);
		FogParameters kept = fog(context, FogRenderer.FogMode.FOG_TERRAIN);
		check(kept.end() <= 192.0F, label + " 효과의 안개까지 없어졌습니다: " + kept);
		server.runCommand("effect clear @a minecraft:" + id);
		context.waitFor(minecraft -> !minecraft.player.hasEffect(effect), 200);
	}

	/** 지금 카메라가 있는 곳에서 바닐라가 쓰게 될 안개 값 (렌더 거리 192칸 기준). */
	private static FogParameters fog(ClientGameTestContext context, FogRenderer.FogMode mode) {
		return context.computeOnClient(minecraft ->
			FogRenderer.setupFog(minecraft.gameRenderer.getMainCamera(), mode, new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), 192.0F, false, 1.0F));
	}

	/**
	 * 인벤토리 HUD: 핫바를 뺀 27칸을 보여 준다. 그 칸이 모두 비어 있으면 숨고, 편집 화면에서는 예시 아이템을 보여 준다.
	 * 칸 배경은 밝은 화면에서 밝기로, 아이템 그림은 실명 효과로 화면을 검게 만든 뒤에 색으로 잰다.
	 */
	private static void testInventoryHud(ClientGameTestContext context, TestServerContext server) {
		// 기본값은 새로 만든 요소에서 본다. Modules.INVENTORY 는 앞 검사들이 이미 꺼 두므로 그 상태로는 기본값을 알 수 없다.
		check(!new InventoryHud(0.0, 0.0).isEnabled(), "인벤토리 HUD 는 기본으로 꺼져 있어야 합니다");
		check(!Modules.INVENTORY.isEnabled(), "앞 검사가 인벤토리 HUD 를 켜 둔 채로 끝났습니다");

		// 앞 검사가 아이템을 남겼으면 비운다. 이미 비어 있을 때 명령어를 보내면 실패 문구만 서버 로그에 남는다.
		if (!context.computeOnClient(minecraft -> minecraft.player.getInventory().isEmpty())) {
			server.runCommand("clear @a");
			context.waitTicks(5);
		}

		check(!context.computeOnClient(Modules.INVENTORY::hasContent), "인벤토리가 비어 있는데 인벤토리 HUD 가 표시됩니다");
		checkInventoryDefaultLayout(context);

		// 핫바의 아이템은 세지 않는다.
		server.runCommand("item replace entity @a hotbar.0 with minecraft:redstone_block");
		context.waitTicks(5);
		check(context.computeOnClient(minecraft -> minecraft.player.getInventory().getItem(0).is(Items.REDSTONE_BLOCK)), "핫바에 넣은 아이템이 보이지 않아 비교할 수 없습니다");
		check(!context.computeOnClient(Modules.INVENTORY::hasContent), "핫바에만 아이템이 있는데 인벤토리 HUD 가 표시됩니다");

		// 인벤토리 9번 칸(명령어로는 inventory.0)이 HUD 의 0번 칸이다.
		server.runCommand("item replace entity @a inventory.0 with minecraft:redstone_block 5");
		context.waitTicks(5);
		check(context.computeOnClient(Modules.INVENTORY::hasContent), "인벤토리에 아이템이 있는데 인벤토리 HUD 가 비어 있습니다");

		// 여기부터는 스크린샷으로 본다. 떠 있는 알림을 치우고, 다른 HUD 가 겹치지 않게 인벤토리 HUD 만 켠다.
		context.runOnClient(minecraft -> {
			minecraft.getToastManager().clear();

			for (HudElement element : Modules.HUD) {
				element.setEnabled(element == Modules.INVENTORY);
			}
		});

		// 칸 배경은 밝은 화면에서 잰다: 칸 안쪽만 어두워지고 칸 사이의 틈은 그대로다. 설정을 끄면 배경이 사라진다.
		check(Modules.INVENTORY.background.get(), "인벤토리 HUD 의 배경은 기본으로 켜져 있어야 합니다");
		int[] origin = position(context, Modules.INVENTORY);
		// 가운데 줄의 빈 칸(14번) 한가운데와, 그 칸과 오른쪽 칸(15번) 사이의 틈.
		double cellX = origin[0] + InventoryHud.slotX(14) + 8;
		double cellY = origin[1] + InventoryHud.slotY(14) + 8;
		double gapX = origin[0] + InventoryHud.slotX(15) - 1;
		context.waitTicks(3);
		BufferedImage backed = readImage(context.takeScreenshot("inventory-hud-background"));
		context.runOnClient(minecraft -> Modules.INVENTORY.background.set(false));
		context.waitTicks(3);
		BufferedImage plain = readImage(context.takeScreenshot("inventory-hud-no-background"));
		context.runOnClient(minecraft -> Modules.INVENTORY.background.reset());
		double cellPlain = brightnessAt(context, plain, cellX, cellY);
		double cellBacked = brightnessAt(context, backed, cellX, cellY);
		double gapPlain = brightnessAt(context, plain, gapX, cellY);
		double gapBacked = brightnessAt(context, backed, gapX, cellY);
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 인벤토리 HUD 칸 배경의 밝기(배경 끔 → 켬) — 칸 안 {} → {}, 칸 사이 {} → {}",
			round(cellPlain), round(cellBacked), round(gapPlain), round(gapBacked));
		check(cellPlain >= 30, "칸 뒤의 화면이 너무 어두워서 칸 배경을 잴 수 없습니다: 밝기 " + round(cellPlain));
		check(cellBacked <= cellPlain - 12, "인벤토리 HUD 의 칸 배경이 그려지지 않았습니다: 밝기 " + round(cellPlain) + " → " + round(cellBacked));
		check(Math.abs(gapBacked - gapPlain) <= 8, "칸 사이의 틈까지 배경이 칠해졌습니다: 밝기 " + round(gapPlain) + " → " + round(gapBacked));

		// 아이템 그림은 색으로 가려낸다. 월드의 색이 섞이지 않게 실명 효과로 화면을 검게 만든다.
		// 입자가 재는 자리를 덮지 않게 입자 설정을 "최소"로 두고, 이미 떠 있던 것(수명 최대 40틱)이 사라질 때까지 기다린다.
		ParticleStatus particles = context.computeOnClient(minecraft -> {
			ParticleStatus before = minecraft.options.particles().get();
			minecraft.options.particles().set(ParticleStatus.MINIMAL);
			return before;
		});
		server.runCommand("effect give @a minecraft:blindness 120 0 true");
		context.waitTicks(50);
		int[] first = inventorySlotArea(context, 0);
		int[] sixth = inventorySlotArea(context, 5);
		BufferedImage shown = readImage(context.takeScreenshot("inventory-hud"));
		int item = colorfulPixels(shown, first[0], first[1], first[2], first[2]);
		int blank = colorfulPixels(shown, sixth[0], sixth[1], sixth[2], sixth[2]);

		context.runOnClient(minecraft -> Modules.INVENTORY.setEnabled(false));
		context.waitTicks(3);
		BufferedImage hidden = readImage(context.takeScreenshot("inventory-hud-off"));
		int off = colorfulPixels(hidden, first[0], first[1], first[2], first[2]);

		// 편집 화면: 인벤토리가 비어 있어도 위치를 잡을 수 있게 예시 아이템(앞의 다섯 칸)이 보인다.
		server.runCommand("clear @a");
		context.waitTicks(5);
		context.runOnClient(minecraft -> Modules.INVENTORY.setEnabled(true));
		check(!context.computeOnClient(Modules.INVENTORY::hasContent), "인벤토리를 비웠는데 인벤토리 HUD 가 표시됩니다");
		context.setScreen(() -> new HudEditScreen(null));
		context.waitTicks(3);
		check(context.computeOnClient(Modules.INVENTORY::hasContent), "편집 화면에서 빈 인벤토리 HUD 가 예시로 표시되지 않습니다");
		BufferedImage editing = readImage(context.takeScreenshot("inventory-hud-edit"));
		int sample = colorfulPixels(editing, first[0], first[1], first[2], first[2]) + brightPixels(editing, first[0], first[1], first[2], first[2]);
		int sampleBlank = colorfulPixels(editing, sixth[0], sixth[1], sixth[2], sixth[2]) + brightPixels(editing, sixth[0], sixth[1], sixth[2], sixth[2]);
		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		check(!context.computeOnClient(Modules.INVENTORY::hasContent), "편집 화면을 닫았는데 빈 인벤토리 HUD 가 표시됩니다");

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 인벤토리 HUD 칸 자리의 색 있는 픽셀 — 0번 칸 {}, 5번 칸 {}, 끈 뒤 0번 칸 {} / 편집 화면의 밝거나 색 있는 픽셀 — 0번 칸 {}, 5번 칸 {}",
			item, blank, off, sample, sampleBlank);
		check(item >= first[2] * first[2] / 4, "인벤토리 HUD 의 0번 칸에 아이템이 그려지지 않았습니다: " + item + "픽셀");
		check(blank == 0, "비어 있는 5번 칸 자리에 그림이 있습니다: " + blank + "픽셀");
		check(off == 0, "인벤토리 HUD 를 껐는데 0번 칸 자리에 그림이 남아 있습니다: " + off + "픽셀");
		check(sample > 0, "편집 화면에 인벤토리 HUD 의 예시 아이템이 보이지 않습니다");
		check(sampleBlank == 0, "편집 화면에서 예시가 없는 5번 칸 자리에 그림이 있습니다: " + sampleBlank + "픽셀");

		// 켜짐 상태, 크기, 위치가 저장되고 다시 읽힌다.
		context.runOnClient(minecraft -> {
			Modules.INVENTORY.scale.set(1.5);
			Modules.INVENTORY.setPosition(minecraft, 100, 50, 400, 200);
			ConfigManager.save();

			Modules.INVENTORY.setEnabled(false);
			Modules.INVENTORY.scale.reset();
			Modules.INVENTORY.resetPosition();
			ConfigManager.load();
		});
		check(Modules.INVENTORY.isEnabled(), "저장한 인벤토리 HUD 켜짐 상태를 다시 읽지 못했습니다");
		check(exactly(Modules.INVENTORY.scale.get(), 1.5), "저장한 인벤토리 HUD 크기를 다시 읽지 못했습니다: " + Modules.INVENTORY.scale.get());
		int[] saved = context.computeOnClient(minecraft -> new int[] {Modules.INVENTORY.x(minecraft, 400), Modules.INVENTORY.y(minecraft, 200)});
		check(Math.abs(saved[0] - 100) <= 1 && Math.abs(saved[1] - 50) <= 1, "저장한 인벤토리 HUD 위치를 다시 읽지 못했습니다: " + saved[0] + ", " + saved[1]);

		// 인벤토리는 위에서 이미 비웠다. 나머지를 기본 상태로 되돌리고, 그 상태를 설정 파일에도 남긴다.
		context.runOnClient(minecraft -> {
			Modules.INVENTORY.scale.reset();
			Modules.INVENTORY.resetPosition();

			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}

			minecraft.options.particles().set(particles);
			ConfigManager.save();
		});
		server.runCommand("effect clear @a minecraft:blindness");
		context.waitTicks(40);
	}

	/**
	 * 기본 배치: 모든 요소가 기본 위치·기본 크기일 때 인벤토리 HUD 가 기본으로 켜져 있는 다른 요소와 겹치지 않아야 한다.
	 * 편집 화면에서 잰다. 거기서는 비어 있는 요소(갑옷, 포션, 타이틀 등)도 예시 내용의 크기로 자리를 차지한다.
	 * 갑옷을 입지 않았고 효과도 걸려 있지 않을 때 부른다 (그래야 예시 크기로 잰다).
	 */
	private static void checkInventoryDefaultLayout(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			// 알림(토스트)도 오른쪽 위에 뜬다. 스크린샷에서 인벤토리 HUD 를 가리지 않게 치운다.
			minecraft.getToastManager().clear();

			for (HudElement element : Modules.HUD) {
				element.resetPosition();
				element.scale.reset();
			}

			Modules.INVENTORY.setEnabled(true);
		});
		context.setScreen(() -> new HudEditScreen(null));
		context.waitTicks(3);
		int[] inventory = elementBox(context, Modules.INVENTORY);
		List<String> overlaps = inventoryOverlaps(context);
		context.takeScreenshot("inventory-hud-default-layout");

		// 이 검사가 겹침을 실제로 잡아내는지: 인벤토리 HUD 를 키스트로크 자리로 옮기면 키스트로크와 겹친다고 나와야 한다.
		int[] keystrokes = elementBox(context, Modules.KEYSTROKES);
		context.runOnClient(minecraft -> Modules.INVENTORY.setPosition(minecraft, keystrokes[0], keystrokes[1], minecraft.screen.width, minecraft.screen.height));
		List<String> moved = inventoryOverlaps(context);
		context.runOnClient(minecraft -> Modules.INVENTORY.resetPosition());

		context.clickScreenButton("gui.done");
		context.waitFor(minecraft -> minecraft.screen == null);
		context.runOnClient(minecraft -> Modules.INVENTORY.setEnabled(false));
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 기본 배치 — 인벤토리 HUD 상자 {}, 겹치는 요소(겹친 크기) {} / 키스트로크 자리로 옮겼을 때 {}",
			Arrays.toString(inventory), overlaps, moved);
		check(moved.stream().anyMatch(overlap -> overlap.startsWith("keystrokes ")), "인벤토리 HUD 를 키스트로크 자리로 옮겼는데 겹침을 찾지 못했습니다: " + moved);
		// 보스바만은 겹친다. 바닐라가 화면 위쪽 가운데에 너비 182로 그리는 자리라서, GUI 너비가 510 보다 좁으면
		// 그 오른쪽 끝이 오른쪽 위에 있는 인벤토리 HUD 의 왼쪽 끝과 겹친다. 이 검사의 화면(427x240)에서는 40x14 이고, 다른 크기로 겹치면 실패한다.
		overlaps.remove("bossbar 40x14");
		check(overlaps.isEmpty(), "기본 위치의 인벤토리 HUD 가 기본으로 켜져 있는 요소와 겹칩니다 (요소와 겹친 크기): " + overlaps + " / 인벤토리 HUD 상자 " + Arrays.toString(inventory));
	}

	/** 인벤토리 HUD 의 상자가 기본으로 켜져 있는 다른 요소의 상자와 겹친 곳들. "요소 id 너비x높이" (GUI 좌표). */
	private static List<String> inventoryOverlaps(ClientGameTestContext context) {
		int[] inventory = elementBox(context, Modules.INVENTORY);
		List<String> overlaps = new ArrayList<>();

		for (HudElement element : Modules.HUD) {
			if (!defaultHudEnabled(element)) {
				continue;
			}

			int[] box = elementBox(context, element);
			int width = Math.min(inventory[0] + inventory[2], box[0] + box[2]) - Math.max(inventory[0], box[0]);
			int height = Math.min(inventory[1] + inventory[3], box[1] + box[3]) - Math.max(inventory[1], box[1]);

			if (width > 0 && height > 0) {
				overlaps.add(element.id() + " " + width + "x" + height);
			}
		}

		return overlaps;
	}

	/** 인벤토리 HUD(크기 100%)의 index 번째 칸에 아이템이 그려지는 자리를 스크린샷 픽셀로 돌려준다 (왼쪽, 위, 한 변의 길이). */
	private static int[] inventorySlotArea(ClientGameTestContext context, int index) {
		int[] position = position(context, Modules.INVENTORY);
		int scale = context.computeOnClient(minecraft -> (int) minecraft.getWindow().getGuiScale());
		return new int[] {(position[0] + InventoryHud.slotX(index)) * scale, (position[1] + InventoryHud.slotY(index)) * scale, 16 * scale};
	}

	/**
	 * 컨테이너 미리보기: Shift 를 누른 채 내용물이 든 셜커 상자·상자에 커서를 올리면 툴팁에 내용물이 9칸 너비 격자로 나온다.
	 * 자료(칸, 줄 수)는 클라이언트에서 직접 만든 아이템으로 보고, 실제 그림은 인벤토리 화면의 스크린샷으로 본다.
	 */
	private static void testContainerPreview(ClientGameTestContext context, TestInput input, TestServerContext server) {
		// 기본값은 새로 만든 모듈에서 본다.
		check(new ContainerPreviewModule().isEnabled(), "컨테이너 미리보기는 기본으로 켜져 있어야 합니다");
		check(Modules.CONTAINER_PREVIEW.isEnabled(), "앞 검사가 컨테이너 미리보기를 꺼 둔 채로 끝났습니다");

		ItemStack diamonds = new ItemStack(Items.DIAMOND, 3);
		ItemStack shulker = containerItem(Items.SHULKER_BOX, diamonds, 0);
		ItemStack tooBig = containerItem(Items.SHULKER_BOX, diamonds, 0, 60);
		ItemStack loot = shulker.copy();
		loot.set(DataComponents.CONTAINER_LOOT, new SeededContainerLoot(BuiltInLootTables.SIMPLE_DUNGEON, 0L));

		// Shift 를 누르지 않으면 바닐라 그대로다. 글자 줄 수는 적어 두었다가 아래에서 Shift 를 누른 쪽과 비교한다.
		check(tooltipImage(context, shulker).isEmpty(), "Shift 를 누르지 않았는데 셜커 상자의 미리보기가 나옵니다");
		int plainLines = tooltipLineCount(context, shulker);
		int tooBigLines = tooltipLineCount(context, tooBig);
		int lootLines = tooltipLineCount(context, loot);

		input.holdShift();
		ContainerPreviewTooltip basic = containerPreview(context, shulker);
		check(basic != null, "Shift 를 눌렀는데 셜커 상자의 미리보기가 나오지 않습니다");
		check(basic.rows() == 3 && basic.slots().size() == 27, "칸이 적은 셜커 상자의 미리보기는 3줄 27칸이어야 합니다: " + basic.rows() + "줄 " + basic.slots().size() + "칸");
		check(basic.slots().get(0).is(Items.DIAMOND) && basic.slots().get(0).getCount() == 3, "미리보기의 0번 칸이 다이아몬드 3개가 아닙니다: " + basic.slots().get(0));
		check(basic.slots().get(1).isEmpty(), "미리보기의 1번 칸이 비어 있지 않습니다: " + basic.slots().get(1));

		// 줄 수는 마지막으로 찬 칸까지 보이도록 늘어난다 (3~6줄).
		ContainerPreviewTooltip fourRows = containerPreview(context, containerItem(Items.SHULKER_BOX, diamonds, 0, 30));
		check(fourRows != null && fourRows.rows() == 4, "30번 칸까지 찬 컨테이너의 미리보기가 4줄이 아닙니다: " + fourRows);
		ContainerPreviewTooltip sixRows = containerPreview(context, containerItem(Items.SHULKER_BOX, diamonds, 0, 53));
		check(sixRows != null && sixRows.rows() == 6 && sixRows.slots().size() == 54, "53번 칸까지 찬 컨테이너의 미리보기가 6줄 54칸이 아닙니다: " + sixRows);

		// 54번 칸 이후에도 내용물이 있으면 격자에 다 못 그리므로 바닐라 글자 툴팁을 그대로 둔다.
		check(tooltipImage(context, containerItem(Items.SHULKER_BOX, diamonds, 0, 54)).isEmpty(), "54번 칸에 내용물이 있는데 미리보기가 나옵니다");
		check(tooltipImage(context, tooBig).isEmpty(), "60번 칸에 내용물이 있는데 미리보기가 나옵니다");
		check(tooltipLineCount(context, tooBig) == tooBigLines, "격자에 다 못 그리는 컨테이너의 글자 줄이 Shift 를 누르자 바뀌었습니다: " + tooBigLines + " → " + tooltipLineCount(context, tooBig));

		// 숨김 표시가 붙은 아이템과 아직 열지 않은 전리품 상자의 내용은 드러내지 않는다. 표시만 다른 위의 셜커 상자는 미리보기가 나왔다.
		ItemStack hiddenAdditional = shulker.copy();
		hiddenAdditional.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
		check(tooltipImage(context, hiddenAdditional).isEmpty(), "추가 툴팁 숨김 표시가 붙은 셜커 상자에 미리보기가 나옵니다");
		ItemStack hidden = shulker.copy();
		hidden.set(DataComponents.HIDE_TOOLTIP, Unit.INSTANCE);
		check(tooltipImage(context, hidden).isEmpty(), "툴팁 숨김 표시가 붙은 셜커 상자에 미리보기가 나옵니다");
		check(tooltipImage(context, loot).isEmpty(), "아직 열지 않은 전리품 셜커 상자에 미리보기가 나옵니다");
		check(tooltipLineCount(context, loot) == lootLines, "전리품 셜커 상자의 글자 줄이 Shift 를 누르자 바뀌었습니다: " + lootLines + " → " + tooltipLineCount(context, loot));

		// 내용물이 없는 것과 컨테이너가 아닌 것.
		check(tooltipImage(context, new ItemStack(Items.SHULKER_BOX)).isEmpty(), "빈 셜커 상자에 미리보기가 나옵니다");
		check(tooltipImage(context, new ItemStack(Items.DIAMOND_SWORD)).isEmpty(), "다이아몬드 검에 미리보기가 나옵니다");

		// 꾸러미는 바닐라의 그림 그대로다. 컨테이너 내용물까지 넣어서 미리보기 조건이 맞게 해도 바닐라 그림이 우선한다.
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(diamonds.copy())));
		check(tooltipImage(context, bundle).orElse(null) instanceof BundleTooltip, "내용물이 든 꾸러미의 툴팁 그림이 바닐라와 다릅니다: " + tooltipImage(context, bundle));
		ItemStack bundleWithContainer = containerItem(Items.BUNDLE, diamonds, 0);
		bundleWithContainer.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(diamonds.copy())));
		check(context.computeOnClient(minecraft -> Modules.CONTAINER_PREVIEW.preview(bundleWithContainer).isPresent()), "컨테이너 내용물을 넣은 꾸러미가 미리보기 조건에 맞지 않아 비교할 수 없습니다");
		check(tooltipImage(context, bundleWithContainer).orElse(null) instanceof BundleTooltip, "바닐라가 그림을 준 아이템인데 미리보기로 바뀌었습니다: " + tooltipImage(context, bundleWithContainer));

		// 셜커 상자가 아닌 컨테이너(상자)와, 내용물이 다시 컨테이너인 경우.
		ContainerPreviewTooltip chest = containerPreview(context, containerItem(Items.CHEST, diamonds, 0));
		check(chest != null && chest.slots().get(0).is(Items.DIAMOND), "내용물이 든 상자의 미리보기가 나오지 않습니다: " + chest);
		ContainerPreviewTooltip nested = containerPreview(context, containerItem(Items.CHEST, shulker, 0));
		check(nested != null && nested.slots().get(0).is(Items.SHULKER_BOX), "셜커 상자가 든 상자의 미리보기가 나오지 않습니다: " + nested);

		// 격자가 내용물을 보여 주는 동안에는 같은 내용을 적은 글자 줄이 빠진다.
		int shiftLines = tooltipLineCount(context, shulker);
		check(shiftLines < plainLines, "Shift 를 눌러도 셜커 상자의 내용물 글자 줄이 남아 있습니다: " + plainLines + " → " + shiftLines);
		// 검색 색인을 만드는 스레드처럼 렌더 스레드가 아닌 곳에서는 Shift 를 읽지 않고 바닐라 줄을 그대로 낸다.
		int offThreadLines = CompletableFuture.supplyAsync(() -> shulker.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL).size()).join();
		check(offThreadLines == plainLines, "다른 스레드에서 만든 글자 줄이 Shift 없이 만든 것과 다릅니다: " + plainLines + " → " + offThreadLines);

		// 모듈을 끄면 Shift 를 눌러도 바닐라 그대로다.
		context.runOnClient(minecraft -> Modules.CONTAINER_PREVIEW.setEnabled(false));
		check(tooltipImage(context, shulker).isEmpty(), "모듈을 껐는데 미리보기가 나옵니다");
		check(tooltipLineCount(context, shulker) == plainLines, "모듈을 껐는데 셜커 상자의 글자 줄이 바뀌었습니다: " + plainLines + " → " + tooltipLineCount(context, shulker));
		context.runOnClient(minecraft -> Modules.CONTAINER_PREVIEW.setEnabled(true));

		// Shift 를 떼면 다시 바닐라 그대로다.
		input.releaseShift();
		check(tooltipImage(context, shulker).isEmpty(), "Shift 를 뗐는데 미리보기가 남아 있습니다");
		check(tooltipLineCount(context, shulker) == plainLines, "Shift 를 뗐는데 셜커 상자의 글자 줄이 돌아오지 않았습니다: " + plainLines + " → " + tooltipLineCount(context, shulker));

		// 여기부터는 화면으로 본다. 인벤토리의 첫 칸에 레드스톤 블록 7개가 든 셜커 상자를 넣고 그 칸에 커서를 올린다.
		// 앞 검사가 아이템을 남겼으면 비운다. 이미 비어 있을 때 명령어를 보내면 실패 문구만 서버 로그에 남는다.
		if (!context.computeOnClient(minecraft -> minecraft.player.getInventory().isEmpty())) {
			server.runCommand("clear @a");
			context.waitTicks(5);
		}

		server.runCommand("item replace entity @a inventory.0 with minecraft:shulker_box[container=[{slot:0,item:{id:\"minecraft:redstone_block\",count:7}}]]");
		context.waitTicks(5);
		check(context.computeOnClient(minecraft -> minecraft.player.getInventory().getItem(9).is(Items.SHULKER_BOX)), "인벤토리에 넣은 셜커 상자가 보이지 않아 비교할 수 없습니다");
		context.runOnClient(minecraft -> {
			minecraft.getToastManager().clear();
			minecraft.setScreen(new InventoryScreen(minecraft.player));
		});
		context.waitForScreen(InventoryScreen.class);
		// 인벤토리 창(176x166)은 화면 가운데에 있고, 첫 번째 인벤토리 칸은 창 안 (8, 84) 에서 시작한다. 그 칸의 가운데를 가리킨다.
		int scale = context.computeOnClient(minecraft -> (int) minecraft.getWindow().getGuiScale());
		int[] slotCenter = context.computeOnClient(minecraft -> new int[] {(minecraft.screen.width - 176) / 2 + 8 + 8, (minecraft.screen.height - 166) / 2 + 84 + 8});
		input.setCursorPos(slotCenter[0] * scale, slotCenter[1] * scale);
		context.waitTicks(3);
		// 이 실행에서 격자를 그린 적이 아직 없다. Shift 없이 커서만 올린 지금도 그려지지 않아야 한다.
		int[] before = context.computeOnClient(minecraft -> new int[] {ContainerPreviewTooltip.lastX(), ContainerPreviewTooltip.lastY()});
		check(before[0] == -1 && before[1] == -1, "Shift 를 누르지 않았는데 미리보기 격자가 그려졌습니다: " + Arrays.toString(before));

		input.holdShift();
		context.waitTicks(3);
		BufferedImage shown = readImage(context.takeScreenshot("container-preview"));
		int[] grid = context.computeOnClient(minecraft -> new int[] {ContainerPreviewTooltip.lastX(), ContainerPreviewTooltip.lastY()});
		input.releaseShift();
		context.waitTicks(3);
		BufferedImage plain = readImage(context.takeScreenshot("container-preview-off"));

		// 정리: 화면을 닫고 인벤토리를 비운다 (Shift 는 위에서 뗐고 모듈은 켜 둔 그대로다).
		context.runOnClient(minecraft -> minecraft.screen.onClose());
		context.waitFor(minecraft -> minecraft.screen == null);
		server.runCommand("clear @a");
		context.waitTicks(5);

		check(grid[0] >= 0 && grid[1] >= 0, "Shift 를 누른 채 셜커 상자에 커서를 올렸는데 미리보기 격자가 그려지지 않았습니다: " + Arrays.toString(grid));
		// 격자의 칸은 18픽셀 간격이고 아이템은 칸 안쪽 (1, 1) 에 16x16 으로 그려진다. 툴팁 배경과 칸 배경은 색이 거의 없다.
		int cell = 16 * scale;
		int first = colorfulPixels(shown, (grid[0] + 1) * scale, (grid[1] + 1) * scale, cell, cell);
		int ninth = colorfulPixels(shown, (grid[0] + 8 * 18 + 1) * scale, (grid[1] + 1) * scale, cell, cell);
		int released = colorfulPixels(plain, (grid[0] + 1) * scale, (grid[1] + 1) * scale, cell, cell);
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 컨테이너 미리보기 — 격자 위치 {}, 색 있는 픽셀: 0번 칸 {}, 8번 칸 {}, Shift 를 뗀 뒤 0번 칸 자리 {} / 글자 줄 수: 평소 {}, Shift {}",
			Arrays.toString(grid), first, ninth, released, plainLines, shiftLines);
		check(first >= cell * cell / 4, "미리보기 격자의 0번 칸에 아이템이 그려지지 않았습니다: " + first + "픽셀");
		check(ninth == 0, "비어 있는 8번 칸 자리에 그림이 있습니다: " + ninth + "픽셀");
		check(released == 0, "Shift 를 뗐는데 0번 칸 자리에 그림이 남아 있습니다: " + released + "픽셀");
	}

	/** slots 에 적은 칸마다 content 가 들어 있는 컨테이너 아이템을 클라이언트에서 직접 만든다. */
	private static ItemStack containerItem(Item item, ItemStack content, int... slots) {
		List<ItemStack> items = new ArrayList<>(Collections.nCopies(Arrays.stream(slots).max().orElse(-1) + 1, ItemStack.EMPTY));

		for (int slot : slots) {
			items.set(slot, content);
		}

		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
		return stack;
	}

	/** 렌더 스레드에서 물어본 툴팁 그림. Shift 가 눌렸는지는 렌더 스레드에서만 읽힌다. */
	private static Optional<TooltipComponent> tooltipImage(ClientGameTestContext context, ItemStack stack) {
		return context.computeOnClient(minecraft -> stack.getTooltipImage());
	}

	/** 툴팁 그림이 컨테이너 미리보기면 그 자료, 아니면 null. */
	private static ContainerPreviewTooltip containerPreview(ClientGameTestContext context, ItemStack stack) {
		return tooltipImage(context, stack).orElse(null) instanceof ContainerPreviewTooltip preview ? preview : null;
	}

	/** 렌더 스레드에서 만든 툴팁 글자 줄 수. */
	private static int tooltipLineCount(ClientGameTestContext context, ItemStack stack) {
		return context.computeOnClient(minecraft -> stack.getTooltipLines(Item.TooltipContext.EMPTY, null, TooltipFlag.NORMAL).size());
	}

	/**
	 * 체력바: 대상의 머리 위 기준점을 화면 좌표로 옮겨 HUD 단계에서 그린다.
	 * 그려진 자리는 렌더러가 알려 주는 값(lastDrawn)으로 찾고, 실제 그림은 그 자리의 스크린샷 픽셀 색으로 본다.
	 * 스크린샷은 찍을 때 한 프레임을 새로 그리므로, 찍은 직후의 lastDrawn 이 바로 그 그림에 그려진 자리다.
	 * 검사 처음에 세워 둔 주민을 쓴다. 플레이어는 블록 한가운데에서 남쪽을 보고 주민은 정확히 남쪽 3칸에 둔 채로 검사하고, 끝나면 둘 다 되돌린다.
	 */
	private static void testHealthBar(ClientGameTestContext context, TestInput input, TestServerContext server) {
		// 기본값은 새로 만든 모듈에서 본다.
		HealthBarModule fresh = new HealthBarModule();
		check(!fresh.isEnabled() && fresh.target.is("players"), "체력바는 기본으로 꺼져 있고 대상은 플레이어여야 합니다");
		check(!Modules.HEALTH_BAR.isEnabled() && Modules.HEALTH_BAR.target.is("players"), "앞 검사가 체력바를 기본 상태가 아닌 채로 두고 끝났습니다");

		double[] playerHome = context.computeOnClient(minecraft -> new double[] {
			minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(), minecraft.player.getYRot(), minecraft.player.getXRot()
		});
		int villagerId = context.computeOnClient(minecraft -> nearestEntity(minecraft, Villager.class).getId());
		double[] villagerHome = context.computeOnClient(minecraft -> {
			Entity villager = minecraft.level.getEntity(villagerId);
			return new double[] {villager.getX(), villager.getY(), villager.getZ()};
		});
		server.runCommand("execute as @a at @s align xz run tp @s ~0.5 ~ ~0.5 0 5");
		villagerCommand(server, "tp %s ~ ~ ~3");

		// 앞의 전투 검사가 주민을 때려 두었으면 체력을 채워 놓고 시작한다 (이미 가득일 때 보내면 실패 문구만 서버 로그에 남는다).
		if (context.computeOnClient(minecraft -> entityHealth(minecraft, villagerId)) < 20.0F) {
			villagerCommand(server, "data merge entity %s {Health:20f}");
		}

		// 다른 HUD 와 알림을 치우고, 주민이 옮긴 자리에 다 올 때까지 기다린다.
		context.runOnClient(minecraft -> {
			minecraft.getToastManager().clear();
			Modules.HUD.forEach(element -> element.setEnabled(false));
		});
		context.waitTicks(10);
		check(context.computeOnClient(minecraft -> entityHealth(minecraft, villagerId)) == 20.0F, "주민의 체력을 채우지 못해 비교할 수 없습니다");

		// 꺼져 있으면 아무것도 그리지 않고, 켜도 대상이 플레이어인 동안에는 주민에게 뜨지 않는다.
		check(healthBars(context).isEmpty(), "체력바가 꺼져 있는데 그려졌습니다: " + healthBars(context));
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.setEnabled(true));
		context.waitTicks(5);
		check(healthBar(context, villagerId) == null, "대상이 플레이어인데 주민에게 체력바가 떴습니다");

		// 대상을 생물로 바꾸면 주민에게 뜬다. 체력이 가득이라 바가 오른쪽 끝까지 초록이다.
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.target.set("living"));
		context.waitTicks(5);
		BufferedImage fullShot = readImage(context.takeScreenshot("health-bar"));
		HealthBarRenderer.Drawn full = healthBar(context, villagerId);
		check(full != null, "대상을 생물로 바꿨는데 주민의 체력바가 그려지지 않았습니다");
		int[] fullFill = colorAt(context, fullShot, fillCenterX(full), barCenterY(full));
		int[] fullEnd = colorAt(context, fullShot, full.barRight() - 2, barCenterY(full));
		check(exactly(full.fill(), 1.0), "체력이 가득인데 채운 비율이 1 이 아닙니다: " + full.fill());
		check(isGreen(fullFill) && isGreen(fullEnd), "체력이 가득인 체력바가 초록이 아닙니다: 채운 쪽 가운데 " + Arrays.toString(fullFill) + ", 오른쪽 끝 " + Arrays.toString(fullEnd));

		checkHealthBarPlacement(context, input, server, villagerId);

		// 체력을 깎으면(20 → 4) 채운 폭이 줄고 빨강이 된다. 가득일 때 초록이던 오른쪽 끝은 빈 바탕이 된다.
		// 맞은 직후의 붉은 기가 가신 뒤에 찍는다.
		villagerCommand(server, "damage %s 16");
		context.waitTicks(20);
		BufferedImage lowShot = readImage(context.takeScreenshot("health-bar-low"));
		HealthBarRenderer.Drawn low = healthBar(context, villagerId);
		check(low != null, "체력을 깎은 주민의 체력바가 그려지지 않았습니다");
		int[] lowFill = colorAt(context, lowShot, fillCenterX(low), barCenterY(low));
		int[] lowEnd = colorAt(context, lowShot, low.barRight() - 2, barCenterY(low));
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 체력바 — 가득: {} 채운 쪽 {} 오른쪽 끝 {} / 깎은 뒤: {} 채운 쪽 {} 오른쪽 끝 {}",
			full, Arrays.toString(fullFill), Arrays.toString(fullEnd), low, Arrays.toString(lowFill), Arrays.toString(lowEnd));
		check(Math.abs(low.fill() - 0.2) <= 0.05, "체력이 4/20 인데 채운 비율이 0.2 가 아닙니다: " + low.fill());
		check(isRed(lowFill), "체력이 적은 체력바의 채운 쪽이 빨강이 아닙니다: " + Arrays.toString(lowFill));
		check(!isGreen(lowEnd) && !isRed(lowEnd), "체력이 적은데 바의 오른쪽 끝까지 채워져 있습니다: " + Arrays.toString(lowEnd));

		checkHealthBarOverTitle(context, server, villagerId);
		checkHealthBarVisibility(context, server, villagerId);
		checkHealthBarPlayer(context, villagerId);

		// 여기부터는 다시 생물이 대상이다.
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.target.set("living"));
		context.waitTicks(5);
		check(healthBar(context, villagerId) != null, "대상을 다시 생물로 바꿨는데 주민의 체력바가 그려지지 않습니다");
		checkHealthBarSizeSettings(context, villagerId);
		checkHealthBarMount(context, server);

		// 모듈을 끄면 그리지 않는다. 끄기 직전에는 주민에게 떠 있다.
		check(healthBar(context, villagerId) != null, "모듈을 끄기 전인데 주민의 체력바가 그려지지 않습니다");
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.setEnabled(false));
		context.waitTicks(3);
		check(healthBars(context).isEmpty(), "체력바를 껐는데 그려졌습니다: " + healthBars(context));

		// 주민의 체력과 자리, 플레이어의 자리와 고개, 설정, HUD 를 원래대로 돌려놓는다.
		villagerCommand(server, "data merge entity %s {Health:20f}");
		villagerCommand(server, String.format(Locale.ROOT, "tp %%s %.6f %.6f %.6f", villagerHome[0], villagerHome[1], villagerHome[2]));
		server.runCommand(String.format(Locale.ROOT, "tp @a %.6f %.6f %.6f %.3f %.3f", playerHome[0], playerHome[1], playerHome[2], playerHome[3], playerHome[4]));
		context.runOnClient(minecraft -> {
			Modules.HEALTH_BAR.resetSettings();

			for (HudElement element : Modules.HUD) {
				element.setEnabled(defaultHudEnabled(element));
			}
		});
		context.waitTicks(10);
	}

	/**
	 * 체력바가 머리 위 기준점에 놓이는지. 기대 좌표는 그리는 코드를 부르지 않고 카메라 방향과 시야각으로 따로 구한다.
	 * 배경을 끄면 전체 사각형의 위쪽이 곧 이름 줄의 위쪽(기준점)이다. 줌으로 시야각이 바뀐 상태에서도 한 번 더 본다.
	 */
	private static void checkHealthBarPlacement(ClientGameTestContext context, TestInput input, TestServerContext server, int villagerId) {
		check(Modules.HEALTH_BAR.showName.get(), "체력바의 이름 줄은 기본으로 켜져 있어야 합니다");
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.background.set(false));
		context.waitTicks(3);
		double middle = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth()) / 2.0;
		double[] expected = expectedHealthBarPoint(context, villagerId, 1.0, 0.0);
		HealthBarRenderer.Drawn plain = healthBar(context, villagerId);
		check(plain != null, "배경을 끈 체력바가 그려지지 않았습니다");

		// 줌을 하면 보이는 범위가 좁아진다. 줌을 해도 기준점이 화면 안에 남도록 고개를 기준점보다 3도 아래까지 든다.
		double elevation = context.computeOnClient(minecraft -> {
			Entity villager = minecraft.level.getEntity(villagerId);
			Vec3 offset = villager.position().add(0.0, villager.getBbHeight() + 0.5, 0.0).subtract(minecraft.gameRenderer.getMainCamera().getPosition());
			return Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance()));
		});
		turnPlayer(context, server, 0.0, 3.0 - elevation);
		HealthBarRenderer.Drawn before = healthBar(context, villagerId);
		boolean smooth = Modules.ZOOM.smooth.get();
		context.runOnClient(minecraft -> Modules.ZOOM.smooth.set(false));
		input.holdKey(CafeteriaClient.zoomKey);
		context.waitTicks(5);
		double factor = zoomFactor(context);
		double[] zoomedExpected = expectedHealthBarPoint(context, villagerId, factor, 0.0);
		HealthBarRenderer.Drawn zoomed = healthBar(context, villagerId);
		input.releaseKey(CafeteriaClient.zoomKey);
		context.waitTicks(5);
		context.runOnClient(minecraft -> {
			Modules.ZOOM.smooth.set(smooth);
			Modules.HEALTH_BAR.background.reset();
		});
		turnPlayer(context, server, 0.0, 5.0);

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 체력바 자리 — 따로 계산한 기준점 {} / 그려진 것 {} — 줌 {}배: 따로 계산한 기준점 {} / 줌 전 {} / 줌 뒤 {}",
			Arrays.toString(expected), plain, factor, Arrays.toString(zoomedExpected), before, zoomed);
		// 주민이 정면(x 좌표가 같음)에 있으므로 기준점은 화면 가로 한가운데다.
		check(Math.abs(expected[0] - middle) <= 1, "정면에 있는 주민의 기준점을 따로 계산했는데 화면 가운데가 아닙니다: " + Arrays.toString(expected));
		check(Math.abs(centerX(plain) - middle) <= 2, "정면에 있는 주민의 체력바가 화면 가운데에 있지 않습니다: " + plain);
		check(Math.abs(centerX(plain) - expected[0]) <= 2 && Math.abs(plain.top() - expected[1]) <= 3,
			"체력바가 따로 계산한 기준점에 있지 않습니다: " + plain + " / 기대 " + Arrays.toString(expected));
		check(near(factor, Modules.ZOOM.factor.get()), "줌이 걸리지 않아 비교할 수 없습니다: " + factor);
		check(before != null && zoomed != null, "고개를 든 뒤 체력바가 그려지지 않았습니다: 줌 전 " + before + ", 줌 뒤 " + zoomed);
		check(Math.abs(centerX(zoomed) - zoomedExpected[0]) <= 2 && Math.abs(zoomed.top() - zoomedExpected[1]) <= 3,
			"줌한 화면에서 체력바가 따로 계산한 기준점에 있지 않습니다: " + zoomed + " / 기대 " + Arrays.toString(zoomedExpected));
		check(Math.abs(zoomed.top() - before.top()) > 10, "줌을 해도 체력바의 자리가 그대로입니다: " + before.top() + " → " + zoomed.top());
	}

	/**
	 * 엔티티의 체력바 기준점(키보다 0.5칸 위)이 화면의 어디에 오는지를 그리는 코드와 따로 구한다 (GUI 좌표 x, y 와 카메라 앞쪽으로의 거리).
	 * 카메라의 방향 벡터와 시야각만 쓴다. zoom 은 줌 배율(없으면 1), lift 는 기준점을 더 올리는 높이(블록, 없으면 0).
	 * 가만히 서 있을 때만 맞는다 (걷는 흔들림과 달리기 시야각 보정은 넣지 않았다).
	 */
	private static double[] expectedHealthBarPoint(ClientGameTestContext context, int entityId, double zoom, double lift) {
		return context.computeOnClient(minecraft -> {
			Entity entity = minecraft.level.getEntity(entityId);
			Camera camera = minecraft.gameRenderer.getMainCamera();
			Vec3 offset = entity.position().add(0.0, entity.getBbHeight() + 0.5 + lift, 0.0).subtract(camera.getPosition());
			double depth = offset.dot(new Vec3(camera.getLookVector()));
			double up = offset.dot(new Vec3(camera.getUpVector()));
			double right = -offset.dot(new Vec3(camera.getLeftVector()));
			double tangent = Math.tan(Math.toRadians(minecraft.options.fov().get() / zoom) / 2.0);
			double aspect = (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight();
			double width = minecraft.getWindow().getGuiScaledWidth();
			double height = minecraft.getWindow().getGuiScaledHeight();
			return new double[] {width / 2.0 + right / (depth * tangent * aspect) * width / 2.0, height / 2.0 - up / (depth * tangent) * height / 2.0, depth};
		});
	}

	/**
	 * 칭호보다 앞: 주민에게 보이는 이름표를 주고, 카메라와 주민 머리 위 사이에 불투명한 자홍색 글자 표시 엔티티를 세워 체력바 자리를 덮는다.
	 * 월드 안에 그린 것은 그 뒤에 가려지지만, 체력바는 HUD 단계에서 그리므로 그대로 보여야 한다.
	 * 표시 항목을 끄면 전체 폭이 줄어드는 것도 여기서 본다. 이름이 체력 줄보다 넓어야 이름을 껐을 때 폭이 주는데, 여기서 붙이는 이름표가 그만큼 길다.
	 */
	private static void checkHealthBarOverTitle(ClientGameTestContext context, TestServerContext server, int villagerId) {
		villagerCommand(server, "data merge entity %s {CustomName:'\"체력바 검사용으로 붙인 긴 칭호\"',CustomNameVisible:1b}");
		// 글자 표시는 놓은 자리에서 위쪽으로 그려진다 (3배 크기의 한 줄은 폭 1.9칸, 높이 0.75칸).
		// 카메라에서 1.5칸 앞, 체력바를 보는 시선 위에 그 가운데가 오게 둔다. 글자도 배경과 같은 색이라 전체가 한 가지 색이다.
		// 정렬(alignment)은 기본값이지만 적어 둔다. 빼면 바닐라가 소환할 때 "Not a string" 오류를 로그에 남긴다.
		server.runCommand("execute as @a at @s run summon minecraft:text_display ~ ~1.54 ~1.5 {billboard:\"center\",alignment:\"center\",background:-65281,brightness:{sky:15,block:15},"
			+ "text:'{\"text\":\"MMMM\",\"color\":\"#FF00FF\"}',transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[3f,3f,3f]}}");
		context.waitTicks(10);
		BufferedImage covered = readImage(context.takeScreenshot("health-bar-over-title"));
		HealthBarRenderer.Drawn titled = healthBar(context, villagerId);
		check(titled != null, "이름표를 붙인 주민의 체력바가 그려지지 않았습니다");
		int[] fill = colorAt(context, covered, fillCenterX(titled), barCenterY(titled));
		int[] above = colorAt(context, covered, centerX(titled), titled.top() - 4);

		// 체력바를 끄면 같은 자리에 글자 표시가 보인다 (글자 표시가 체력바 자리를 실제로 덮고 있었다는 확인).
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.setEnabled(false));
		context.waitTicks(3);
		int[] uncovered = colorAt(context, readImage(context.takeScreenshot("health-bar-title-only")), fillCenterX(titled), barCenterY(titled));
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.setEnabled(true));
		context.waitTicks(5);

		// 이름, 체력 수치, 방어도를 차례로 끄면 그때마다 전체 폭이 줄어든다.
		float all = healthBarWidth(context, villagerId);
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.showName.set(false));
		context.waitTicks(3);
		float withoutName = healthBarWidth(context, villagerId);
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.showNumber.set(false));
		context.waitTicks(3);
		float withoutNumber = healthBarWidth(context, villagerId);
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.showArmor.set(false));
		context.waitTicks(3);
		float withoutArmor = healthBarWidth(context, villagerId);

		context.runOnClient(minecraft -> {
			Modules.HEALTH_BAR.showName.reset();
			Modules.HEALTH_BAR.showNumber.reset();
			Modules.HEALTH_BAR.showArmor.reset();
		});
		server.runCommand("kill @e[type=minecraft:text_display]");
		villagerCommand(server, "data remove entity %s CustomName");
		villagerCommand(server, "data merge entity %s {CustomNameVisible:0b}");
		context.waitTicks(10);

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 칭호 앞의 체력바 — {} 채운 쪽 {} 사각형 위 {} / 체력바를 끄면 채운 쪽 자리 {} / 전체 폭: 모두 켬 {}, 이름 끔 {}, 수치도 끔 {}, 방어도도 끔 {}",
			titled, Arrays.toString(fill), Arrays.toString(above), Arrays.toString(uncovered), all, withoutName, withoutNumber, withoutArmor);
		check(isMagenta(above), "글자 표시 엔티티가 체력바 자리를 덮고 있지 않아 비교할 수 없습니다 (사각형 바로 위): " + Arrays.toString(above));
		check(isMagenta(uncovered), "글자 표시 엔티티가 체력바 자리를 덮고 있지 않아 비교할 수 없습니다 (체력바를 껐을 때): " + Arrays.toString(uncovered));
		check(isRed(fill), "체력바가 글자 표시 엔티티에 가려졌습니다: " + Arrays.toString(fill));
		check(withoutName < all - 1, "이름을 꺼도 체력바의 전체 폭이 줄지 않습니다: " + all + " → " + withoutName);
		check(withoutNumber < withoutName - 1, "체력 수치를 꺼도 체력바의 전체 폭이 줄지 않습니다: " + withoutName + " → " + withoutNumber);
		check(withoutArmor < withoutNumber - 1, "방어도를 꺼도 체력바의 전체 폭이 줄지 않습니다: " + withoutNumber + " → " + withoutArmor);
	}

	/**
	 * 체력바를 띄우지 않는 조건: 벽 뒤, 투명화, 카메라 뒤, 표시 거리 밖. 조건이 풀리면 다시 뜬다.
	 * 대상이 카메라와 같은 자리에 있을 때도 죽거나 엉뚱한 곳에 그리지 않아야 한다.
	 */
	private static void checkHealthBarVisibility(ClientGameTestContext context, TestServerContext server, int villagerId) {
		// 벽: 플레이어와 주민 사이(1.5~2.5칸 앞)에 눈 높이와 머리 위를 모두 가리는 돌 벽. 둘 다 블록 한가운데에 서 있어서 벽에 끼지 않는다.
		String wall = "execute as @a at @s run fill ~-1 ~1 ~2 ~1 ~3 ~2 minecraft:";
		server.runCommand(wall + "stone");
		context.waitTicks(10);
		boolean behindWall = healthBar(context, villagerId) != null;
		server.runCommand(wall + "air");
		context.waitTicks(10);
		check(!behindWall, "벽 뒤에 있는 주민에게 체력바가 떴습니다");
		check(healthBar(context, villagerId) != null, "벽을 치웠는데 체력바가 다시 뜨지 않습니다");

		villagerCommand(server, "effect give %s minecraft:invisibility 30 0 true");
		context.waitTicks(10);
		boolean invisible = healthBar(context, villagerId) != null;
		villagerCommand(server, "effect clear %s minecraft:invisibility");
		context.waitTicks(10);
		check(!invisible, "투명해진 주민에게 체력바가 떴습니다");
		check(healthBar(context, villagerId) != null, "투명화가 풀렸는데 체력바가 다시 뜨지 않습니다");

		// 등 뒤에 있는 대상은 그리지 않는다 (그대로 계산하면 화면 반대편에 뒤집혀 나온다).
		turnPlayer(context, server, 180.0, 5.0);
		boolean behindCamera = healthBar(context, villagerId) != null;
		turnPlayer(context, server, 0.0, 5.0);
		check(!behindCamera, "등 뒤에 있는 주민의 체력바가 그려졌습니다");
		check(healthBar(context, villagerId) != null, "다시 앞을 봤는데 체력바가 뜨지 않습니다");

		// 주민을 플레이어와 같은 자리에 둔다. 기준점은 카메라 바로 위 0.8칸이다.
		// 수평으로 보면 기준점이 시선과 직각이라 카메라 앞쪽으로의 거리(w)가 0 이다: 0 으로 나누지 않고 그리지 않아야 한다.
		// 똑바로 올려다보면 기준점이 바로 앞 0.8칸이다: 화면 한가운데에 그려져야 한다.
		double middle = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScaledWidth()) / 2.0;
		villagerCommand(server, "tp %s ~ ~ ~");
		context.waitTicks(10);
		turnPlayer(context, server, 0.0, 0.0);
		HealthBarRenderer.Drawn horizontal = healthBar(context, villagerId);
		turnPlayer(context, server, 0.0, -90.0);
		HealthBarRenderer.Drawn overhead = healthBar(context, villagerId);
		turnPlayer(context, server, 0.0, 5.0);
		villagerCommand(server, "tp %s ~ ~ ~3");
		context.waitTicks(10);
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 주민을 플레이어와 같은 자리에 뒀을 때의 체력바 — 수평으로 볼 때 {} / 올려다볼 때 {}", horizontal, overhead);
		check(horizontal == null, "기준점이 카메라 바로 위(앞쪽 거리 0)에 있는데 체력바가 그려졌습니다: " + horizontal);
		check(overhead != null && Math.abs(centerX(overhead) - middle) <= 2, "바로 위에 있는 기준점을 올려다봤는데 체력바가 화면 가운데에 그려지지 않았습니다: " + overhead);
		check(healthBar(context, villagerId) != null, "주민을 다시 앞에 세웠는데 체력바가 뜨지 않습니다");

		// 표시 거리 8칸: 3칸 앞에서는 뜨고 12칸 앞에서는 뜨지 않는다.
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.distance.set(8.0));
		context.waitTicks(5);
		boolean inRange = healthBar(context, villagerId) != null;
		villagerCommand(server, "tp %s ~ ~ ~12");
		context.waitTicks(10);
		boolean outOfRange = healthBar(context, villagerId) != null;
		villagerCommand(server, "tp %s ~ ~ ~3");
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.distance.reset());
		context.waitTicks(10);
		check(inRange, "표시 거리 8칸 안(3칸)에 있는 주민에게 체력바가 뜨지 않습니다");
		check(!outOfRange, "표시 거리 8칸 밖(12칸)에 있는 주민에게 체력바가 떴습니다");
		check(healthBar(context, villagerId) != null, "주민을 다시 3칸 앞에 세웠는데 체력바가 뜨지 않습니다");
	}

	/**
	 * 대상이 플레이어일 때: 클라이언트에만 있는 가짜 플레이어를 세워서 체력 비율, 흡수 체력 줄, 방어도, 웅크린 상대의 32칸 제한을 확인한다.
	 * 서버는 이 플레이어를 모른다. 어떻게 끝나든 지워서 뒤 검사에 남지 않게 한다.
	 */
	private static void checkHealthBarPlayer(ClientGameTestContext context, int villagerId) {
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.target.set("players"));
		int fakeId = context.computeOnClient(minecraft -> {
			RemotePlayer fake = new RemotePlayer(minecraft.level, new GameProfile(UUID.randomUUID(), "Tester"));
			// 정면 4칸, 주민과 겹치지 않게 옆(서쪽)으로 1칸.
			fake.moveTo(minecraft.player.getX() - 1.0, minecraft.player.getY(), minecraft.player.getZ() + 4.0);
			fake.setHealth(10.0F);
			// 흡수 체력은 최대 흡수 체력까지만 들어가는데 그 기본값이 0 이다. 바닐라에서는 흡수 효과가 이 값을 올린다.
			fake.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(4.0);
			fake.setAbsorptionAmount(4.0F);
			fake.getAttribute(Attributes.ARMOR).setBaseValue(12.0);
			minecraft.level.addEntity(fake);
			return fake.getId();
		});

		try {
			context.waitTicks(5);
			BufferedImage shown = readImage(context.takeScreenshot("health-bar-player"));
			HealthBarRenderer.Drawn bar = healthBar(context, fakeId);
			check(bar != null, "대상이 플레이어인데 가짜 플레이어에게 체력바가 뜨지 않습니다");
			check(healthBar(context, villagerId) == null, "대상이 플레이어인데 주민에게 체력바가 떴습니다");
			check(Math.abs(bar.fill() - 0.5) <= 0.05, "체력이 10/20 인데 채운 비율이 0.5 가 아닙니다: " + bar.fill());
			// 흡수 체력 줄은 바 맨 위의 한 줄(바 높이의 5분의 1)이다. 그 줄의 가운데와, 같은 가로 자리에서 바 가운데(체력을 채운 색)를 비교한다.
			// 체력이 절반이면 채운 색도 노랑 계열이라 색 이름만으로는 가릴 수 없다. 흡수 줄이 더 밝고 파랑이 더 많다 (255, 210, 74 / 230, 194, 41).
			double line = (bar.barBottom() - bar.barTop()) / 5.0;
			int[] absorption = colorAt(context, shown, bar.barLeft() + 2, bar.barTop() + line / 2);
			int[] filled = colorAt(context, shown, bar.barLeft() + 2, barCenterY(bar));
			float withArmor = bar.right() - bar.left();
			context.runOnClient(minecraft -> Modules.HEALTH_BAR.showArmor.set(false));
			context.waitTicks(3);
			float withoutArmor = healthBarWidth(context, fakeId);
			context.runOnClient(minecraft -> Modules.HEALTH_BAR.showArmor.reset());
			CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 플레이어의 체력바 — {} 흡수 체력 줄 {} 그 아래 채운 쪽 {} / 전체 폭: 방어도 켬 {}, 끔 {}",
				bar, Arrays.toString(absorption), Arrays.toString(filled), withArmor, withoutArmor);
			check(absorption[0] > 180 && absorption[1] > 180 && absorption[2] < 140, "흡수 체력 줄이 노랑이 아닙니다: " + Arrays.toString(absorption));
			check(absorption[2] >= filled[2] + 20, "흡수 체력 줄이 그 아래의 채운 색과 구별되지 않습니다: " + Arrays.toString(absorption) + " / " + Arrays.toString(filled));
			check(withoutArmor < withArmor - 1, "방어도를 꺼도 플레이어 체력바의 전체 폭이 줄지 않습니다: " + withArmor + " → " + withoutArmor);

			checkHealthBarOverlap(context, villagerId, fakeId);

			// 웅크린 상대: 바닐라가 이름표를 숨기는 32칸 밖에서는 표시 거리가 더 길어도 띄우지 않는다.
			// 앞 20칸, 위 30칸의 공중에 둔다 (카메라에서 약 35칸). 평지 월드라 사이에 블록이 없다.
			context.runOnClient(minecraft -> {
				Modules.HEALTH_BAR.distance.set(64.0);
				minecraft.level.getEntity(fakeId).moveTo(minecraft.player.getX(), minecraft.player.getY() + 30.0, minecraft.player.getZ() + 20.0);
			});
			context.waitTicks(5);
			HealthBarRenderer.Drawn high = healthBar(context, fakeId);
			context.runOnClient(minecraft -> minecraft.level.getEntity(fakeId).setShiftKeyDown(true));
			context.waitTicks(5);
			boolean sneaking = healthBar(context, fakeId) != null;
			context.runOnClient(minecraft -> minecraft.level.getEntity(fakeId).setShiftKeyDown(false));
			context.waitTicks(5);
			boolean standing = healthBar(context, fakeId) != null;
			check(high != null, "표시 거리 64칸 안(약 35칸)에 서 있는 플레이어에게 체력바가 뜨지 않습니다");
			// 고개를 들지 않았으므로 이 플레이어는 화면 위쪽 밖에 있다. 화면 밖의 대상은 화면 안이 아니라 화면 밖 좌표에 놓여야 한다.
			check(high.bottom() < 0, "화면 위쪽 밖에 있는 플레이어의 체력바가 화면 안에 놓였습니다: " + high);
			check(!sneaking, "32칸 밖에서 웅크린 플레이어에게 체력바가 떴습니다");
			check(standing, "다시 일어선 플레이어에게 체력바가 뜨지 않습니다");
		} finally {
			context.runOnClient(minecraft -> {
				minecraft.level.removeEntity(fakeId, Entity.RemovalReason.DISCARDED);
				Modules.HEALTH_BAR.distance.reset();
				Modules.HEALTH_BAR.showArmor.reset();
			});
		}

		context.waitTicks(3);
		check(context.computeOnClient(minecraft -> minecraft.level.getEntity(fakeId)) == null, "가짜 플레이어가 지워지지 않았습니다");
	}

	/**
	 * 겹친 체력바: 가까운 것이 먼 것의 글자까지 덮어야 한다. 가짜 플레이어를 주민(3칸 앞)과 나 사이 1.5칸 앞에 세우면 그 바가 주민의 이름 줄 위에 놓인다.
	 * GUI 는 깊이 검사를 하고 그림자 있는 글자는 본 글자가 조금 앞에 그려지므로, 두 체력바를 같은 깊이에 그리면 먼 쪽의 흰 글자가 가까운 쪽의 바를 뚫고 보인다.
	 * 가까운 쪽의 바 사각형은 불투명한 어두운 바탕과 채움 색뿐이라, 제대로 덮였으면 그 안에 흰 픽셀이 없다.
	 * 주민에게는 바닐라 이름표가 없으므로, 그 자리에서 보이는 흰 글자는 우리가 그린 주민의 체력바뿐이다.
	 * 같은 사각형을 두 장면과 비교한다: 둘이 같은 깊이를 쓰게 만든 장면(흰 글자가 뚫고 보여야 한다)과 먼 쪽만 그린 장면(흰 글자가 있어야 한다).
	 * 들어올 때와 나갈 때 대상은 플레이어다.
	 */
	private static void checkHealthBarOverlap(ClientGameTestContext context, int villagerId, int fakeId) {
		context.runOnClient(minecraft -> {
			minecraft.level.getEntity(fakeId).moveTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ() + 1.5);
			Modules.HEALTH_BAR.target.set("living");
		});
		context.waitTicks(5);
		BufferedImage both = readImage(context.takeScreenshot("health-bar-overlap"));
		HealthBarRenderer.Drawn near = healthBar(context, fakeId);
		HealthBarRenderer.Drawn far = healthBar(context, villagerId);

		// 대조 1: 두 체력바가 같은 깊이를 쓰면 먼 쪽의 글자가 뚫고 보이고, 아래의 픽셀 검사가 그것을 잡아낸다.
		// 깊이를 따로 받는 체력바는 MAX_LAYERS 개까지라서, 그만큼의 대상을 더 가까이에 두면 가장 먼 둘(주민과 가짜 플레이어)이 맨 뒤 깊이를 같이 쓴다.
		// 클라이언트에만 있는 돼지를 머리 위 20칸에 겹쳐 둔다. 카메라 앞쪽으로는 약 0.9칸이라 둘보다 가깝고, 화면에서는 위쪽 밖이라 장면을 가리지 않는다.
		List<Integer> crowd = context.computeOnClient(minecraft -> {
			List<Integer> ids = new ArrayList<>();

			for (int index = 0; index < HealthBarRenderer.MAX_LAYERS; index++) {
				Pig pig = new Pig(EntityType.PIG, minecraft.level);
				pig.setSilent(true);
				pig.moveTo(minecraft.player.getX(), minecraft.player.getY() + 20.0, minecraft.player.getZ() + 2.6);
				minecraft.level.addEntity(pig);
				ids.add(pig.getId());
			}

			return ids;
		});
		BufferedImage sameDepth;
		int crowded;

		try {
			context.waitTicks(5);
			sameDepth = readImage(context.takeScreenshot("health-bar-overlap-same-depth"));
			crowded = healthBars(context).size();
		} finally {
			context.runOnClient(minecraft -> crowd.forEach(id -> minecraft.level.removeEntity(id, Entity.RemovalReason.DISCARDED)));
		}

		// 대조 2: 가까운 쪽을 등 뒤로 치우면 먼 쪽(주민)만 남는다. 같은 자리에 먼 쪽의 흰 글자가 실제로 있는지 본다.
		context.runOnClient(minecraft -> minecraft.level.getEntity(fakeId).moveTo(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ() - 3.0));
		context.waitTicks(5);
		BufferedImage farOnly = readImage(context.takeScreenshot("health-bar-overlap-far-only"));
		List<HealthBarRenderer.Drawn> remaining = healthBars(context);
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.target.set("players"));
		check(near != null && far != null, "겹쳐 세운 플레이어와 주민의 체력바가 둘 다 그려지지 않았습니다: 플레이어 " + near + ", 주민 " + far);

		// 가까운 쪽의 바 사각형에서 가장자리 1픽셀을 뺀 안쪽 (GUI 좌표: x, y, 너비, 높이).
		int left = Mth.ceil(near.barLeft() + 1);
		int top = Mth.ceil(near.barTop() + 1);
		int[] inside = {left, top, Mth.floor(near.barRight() - 1) - left, Mth.floor(near.barBottom() - 1) - top};
		int uncovered = whitePixels(context, farOnly, inside);
		int bleeding = whitePixels(context, sameDepth, inside);
		int covered = whitePixels(context, both, inside);
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 겹친 체력바 — 가까운 것 {} / 먼 것 {} / 가까운 바 사각형 안쪽 {} 의 흰 픽셀: 먼 것만 그렸을 때 {}, 둘이 같은 깊이를 쓸 때 {} (그린 체력바 {}개), 둘 다 제 깊이에 그렸을 때 {} / 돼지를 지운 뒤 그린 체력바 {}개",
			near, far, Arrays.toString(inside), uncovered, bleeding, crowded, covered, remaining.size());
		check(remaining.stream().noneMatch(drawn -> drawn.entityId() == fakeId || crowd.contains(drawn.entityId())),
			"등 뒤로 옮긴 플레이어나 지운 돼지의 체력바가 아직 그려져서 비교할 수 없습니다: " + remaining.size() + "개");
		check(uncovered >= 50, "먼 체력바의 글자가 가까운 체력바의 바 자리에 걸치지 않아 비교할 수 없습니다: 흰 픽셀 " + uncovered + "개");
		check(crowded >= HealthBarRenderer.MAX_LAYERS + 2, "더 가까이에 둔 돼지들의 체력바가 다 그려지지 않아 두 체력바가 같은 깊이를 쓰지 않았습니다: " + crowded + "개");
		check(bleeding >= 50, "같은 깊이를 쓰는 두 체력바에서 먼 쪽의 글자가 뚫고 보이지 않습니다. 이 픽셀 검사로는 그 경우를 잡아낼 수 없습니다: 흰 픽셀 " + bleeding + "개");
		check(covered == 0, "먼 체력바의 글자가 가까운 체력바의 바를 뚫고 보입니다: 흰 픽셀 " + covered + "개");
	}

	/**
	 * 크기와 높이 조정 설정. 같은 대상을 같은 카메라로 보면서 설정만 바꾼다.
	 * 배경을 끄면 전체 사각형의 위쪽이 곧 기준점이라, 높이 조정으로 올라간 양을 따로 계산한 기준점과 바로 비교할 수 있다.
	 */
	private static void checkHealthBarSizeSettings(ClientGameTestContext context, int villagerId) {
		context.runOnClient(minecraft -> Modules.HEALTH_BAR.background.set(false));
		context.waitTicks(3);
		HealthBarRenderer.Drawn normal = healthBar(context, villagerId);
		double[] expected = expectedHealthBarPoint(context, villagerId, 1.0, 0.0);

		context.runOnClient(minecraft -> Modules.HEALTH_BAR.scale.set(2.0));
		context.waitTicks(3);
		HealthBarRenderer.Drawn doubled = healthBar(context, villagerId);

		// 높이 조정 1칸: 기준점이 1칸 올라간다. 카메라에서 조금 멀어지므로 화면에서 움직이는 양은 따로 투영해서 구한다.
		context.runOnClient(minecraft -> {
			Modules.HEALTH_BAR.scale.reset();
			Modules.HEALTH_BAR.offset.set(1.0);
		});
		context.waitTicks(3);
		HealthBarRenderer.Drawn raised = healthBar(context, villagerId);
		double[] raisedExpected = expectedHealthBarPoint(context, villagerId, 1.0, 1.0);

		context.runOnClient(minecraft -> {
			Modules.HEALTH_BAR.offset.reset();
			Modules.HEALTH_BAR.background.reset();
		});
		context.waitTicks(3);

		check(normal != null && doubled != null && raised != null, "설정을 바꾼 뒤 체력바가 그려지지 않았습니다: 기본 " + normal + ", 크기 2배 " + doubled + ", 높이 조정 1칸 " + raised);
		float width = normal.barRight() - normal.barLeft();
		float height = normal.barBottom() - normal.barTop();
		float doubledWidth = doubled.barRight() - doubled.barLeft();
		float doubledHeight = doubled.barBottom() - doubled.barTop();
		double moved = normal.top() - raised.top();
		double expectedMove = expected[1] - raisedExpected[1];
		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 체력바 설정 — 바 크기 {}x{} → 크기 2배 {}x{} / 높이 조정 1칸: 위로 {} (따로 계산한 값 {})",
			width, height, doubledWidth, doubledHeight, moved, expectedMove);
		check(Math.abs(doubledWidth - width * 2) <= 1 && Math.abs(doubledHeight - height * 2) <= 1,
			"크기를 2배로 했는데 바의 너비와 높이가 2배가 아닙니다: " + width + "x" + height + " → " + doubledWidth + "x" + doubledHeight);
		check(moved > 0 && Math.abs(moved - expectedMove) <= 1, "높이 조정 1칸으로 올라간 양이 따로 계산한 값과 다릅니다: " + moved + " / 기대 " + expectedMove);
	}

	/**
	 * 내가 탄 엔티티에는 띄우지 않는다. 기준점이 카메라 바로 아래에 놓여서, 띄우면 화면을 덮을 만큼 크게 그려진다.
	 * 돼지를 새로 소환해서 타고 똑바로 내려다본다. 그러면 기준점이 카메라 바로 앞 0.5칸이라, 타고 있다는 것 말고는 그리지 않을 이유가 없다.
	 * 끝나면 돼지를 없앤다.
	 */
	private static void checkHealthBarMount(ClientGameTestContext context, TestServerContext server) {
		String pig = "@e[type=minecraft:pig,tag=cafeteria_mount,limit=1]";
		double[] home = context.computeOnClient(minecraft -> new double[] {minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()});
		// 앞 오른쪽 가까이에 세운다. 검사 처음에 세워 둔 돼지보다 가까워서 "가장 가까운 돼지"가 이 돼지다. 없앨 때 고기를 떨어뜨리지 않게 해 둔다.
		server.runCommand("execute as @a at @s run summon minecraft:pig ~-2 ~ ~2 {NoAI:1b,Silent:1b,DeathLootTable:\"minecraft:empty\",Tags:[\"cafeteria_mount\"]}");
		context.waitTicks(10);
		int pigId = context.computeOnClient(minecraft -> nearestEntity(minecraft, Pig.class).getId());
		boolean before = healthBar(context, pigId) != null;

		// 탄 채로 tp 하면 내려지므로 고개는 rotate 로 돌린다.
		server.runCommand("execute as @a run ride @s mount " + pig);
		server.runCommand("execute as @a run rotate @s 0 90");
		context.waitTicks(10);
		boolean riding = context.computeOnClient(minecraft -> minecraft.player.getVehicle() != null && minecraft.player.getVehicle().getId() == pigId);
		double depth = expectedHealthBarPoint(context, pigId, 1.0, 0.0)[2];
		HealthBarRenderer.Drawn mounted = healthBar(context, pigId);

		server.runCommand("execute as @a run ride @s dismount");
		server.runCommand(String.format(Locale.ROOT, "tp @a %.6f %.6f %.6f 0 5", home[0], home[1], home[2]));
		context.waitTicks(10);
		boolean after = healthBar(context, pigId) != null;

		// 돼지는 보이지 않는 곳(월드 아래)으로 옮긴 뒤 없앤다. 쓰러지는 모습과 연기 입자가 화면에 남지 않는다.
		server.runCommand("execute as " + pig + " at @s run tp @s ~ -200 ~");
		server.runCommand("kill " + pig);
		context.waitTicks(30);

		CafeteriaClient.LOGGER.info("CAFETERIA GAMETEST: 탄 돼지의 체력바 — 타기 전 {}, 탄 채로 내려다볼 때 {} (기준점까지 카메라 앞으로 {}칸), 내린 뒤 {}", before, mounted, depth, after);
		check(before, "소환한 돼지에게 체력바가 뜨지 않아 비교할 수 없습니다");
		check(riding, "돼지에 타지 못해 비교할 수 없습니다");
		check(depth > 0.05, "탄 돼지의 기준점이 카메라 앞에 있지 않아 비교할 수 없습니다: " + depth);
		check(mounted == null, "내가 탄 돼지에게 체력바가 그려졌습니다: " + mounted);
		check(after, "돼지에서 내렸는데 체력바가 다시 뜨지 않습니다");
		check(context.computeOnClient(minecraft -> minecraft.level.getEntity(pigId)) == null, "검사에 쓴 돼지가 지워지지 않았습니다");
	}

	/** 플레이어 자리에서 명령어를 실행한다. %s 자리에는 플레이어에게 가장 가까운 주민(검사 처음에 세워 둔 것)을 가리키는 선택자가 들어간다. */
	private static void villagerCommand(TestServerContext server, String command) {
		server.runCommand("execute as @a at @s run " + command.replace("%s", "@e[type=minecraft:villager,limit=1,sort=nearest]"));
	}

	/** 플레이어를 제자리에서 돌려 세운다 (yaw 0 이 남쪽, pitch 는 아래쪽이 +). 화면에 반영될 때까지 기다린다. */
	private static void turnPlayer(ClientGameTestContext context, TestServerContext server, double yaw, double pitch) {
		server.runCommand(String.format(Locale.ROOT, "execute as @a at @s run tp @s ~ ~ ~ %.2f %.2f", yaw, pitch));
		context.waitTicks(5);
	}

	/** 플레이어에게 가장 가까운, 이 종류의 엔티티. */
	private static Entity nearestEntity(Minecraft minecraft, Class<? extends Entity> type) {
		Entity nearest = null;

		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (type.isInstance(entity) && (nearest == null || entity.distanceToSqr(minecraft.player) < nearest.distanceToSqr(minecraft.player))) {
				nearest = entity;
			}
		}

		check(nearest != null, "검사에 쓸 엔티티가 보이지 않습니다: " + type.getSimpleName());
		return nearest;
	}

	private static float entityHealth(Minecraft minecraft, int entityId) {
		return ((LivingEntity) minecraft.level.getEntity(entityId)).getHealth();
	}

	/** 마지막으로 그린 프레임의 체력바 전부. */
	private static List<HealthBarRenderer.Drawn> healthBars(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> HealthBarRenderer.lastDrawn());
	}

	/** 마지막으로 그린 프레임에서 이 엔티티의 체력바. 그려지지 않았으면 null. */
	private static HealthBarRenderer.Drawn healthBar(ClientGameTestContext context, int entityId) {
		return healthBars(context).stream().filter(drawn -> drawn.entityId() == entityId).findFirst().orElse(null);
	}

	/** 마지막으로 그린 프레임에서 이 엔티티의 체력바 전체 폭 (GUI 좌표). */
	private static float healthBarWidth(ClientGameTestContext context, int entityId) {
		HealthBarRenderer.Drawn bar = healthBar(context, entityId);
		check(bar != null, "체력바가 그려지지 않아 폭을 잴 수 없습니다");
		return bar.right() - bar.left();
	}

	private static double centerX(HealthBarRenderer.Drawn bar) {
		return (bar.left() + bar.right()) / 2.0;
	}

	/** 체력바에서 채워진 쪽의 가로 가운데 (GUI 좌표). */
	private static double fillCenterX(HealthBarRenderer.Drawn bar) {
		return bar.barLeft() + (bar.barRight() - bar.barLeft()) * bar.fill() / 2.0;
	}

	private static double barCenterY(HealthBarRenderer.Drawn bar) {
		return (bar.barTop() + bar.barBottom()) / 2.0;
	}

	/** 스크린샷에서 GUI 좌표 (x, y) 에 있는 픽셀의 색 (빨강, 초록, 파랑). */
	private static int[] colorAt(ClientGameTestContext context, BufferedImage image, double x, double y) {
		double scale = context.computeOnClient(minecraft -> minecraft.getWindow().getGuiScale());
		int rgb = image.getRGB(Mth.clamp((int) (x * scale), 0, image.getWidth() - 1), Mth.clamp((int) (y * scale), 0, image.getHeight() - 1));
		return new int[] {rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF};
	}

	/** 초록이 빨강·파랑보다 60 이상 큰 색 (체력바의 초록 63, 210, 63). 하늘과 풀밭은 여기에 들지 않는다. */
	private static boolean isGreen(int[] color) {
		return color[1] >= color[0] + 60 && color[1] >= color[2] + 60;
	}

	/** 빨강이 초록·파랑보다 60 이상 큰 색 (체력바의 빨강 224, 56, 43). */
	private static boolean isRed(int[] color) {
		return color[0] >= color[1] + 60 && color[0] >= color[2] + 60;
	}

	private static boolean isMagenta(int[] color) {
		return color[0] > 200 && color[2] > 200 && color[1] < 80;
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

		// 바닐라 HUD 요소: 옮긴 상태와 자리, 1% 단위 크기가 저장되고 다시 읽힌다.
		context.runOnClient(minecraft -> {
			Modules.TITLE.setPosition(minecraft, 30, 40, 400, 200);
			Modules.CHAT.scale.set(1.37);
			ConfigManager.save();

			Modules.TITLE.resetPosition();
			Modules.CHAT.scale.reset();
			ConfigManager.load();
		});
		check(Modules.TITLE.isMoved(), "타이틀을 옮긴 상태를 다시 읽지 못했습니다");
		int titleX = context.computeOnClient(minecraft -> Modules.TITLE.x(minecraft, 400));
		int titleY = context.computeOnClient(minecraft -> Modules.TITLE.y(minecraft, 200));
		check(Math.abs(titleX - 30) <= 1 && Math.abs(titleY - 40) <= 1, "저장한 타이틀 위치를 다시 읽지 못했습니다: " + titleX + ", " + titleY);
		check(exactly(Modules.CHAT.scale.get(), 1.37), "저장한 채팅 배율(1% 단위)을 다시 읽지 못했습니다: " + Modules.CHAT.scale.get());

		// 1.3.0 까지의 설정 파일(새 요소 항목이 없고 크기가 10% 단위)도 그대로 읽히고, 새 요소는 기본 상태로 남는다.
		try {
			context.runOnClient(minecraft -> {
				Modules.TITLE.resetPosition();
				Modules.CHAT.scale.reset();
				Modules.FPS.scale.reset();
				Files.writeString(file, "{\"modules\":{\"fps\":{\"enabled\":true,\"settings\":{\"scale\":1.3},\"x\":0.25,\"y\":0.5}}}", StandardCharsets.UTF_8);
				ConfigManager.load();
			});
		} catch (java.io.IOException e) {
			throw new AssertionError("설정 파일을 쓸 수 없습니다: " + file, e);
		}

		check(exactly(Modules.FPS.scale.get(), 1.3), "예전 설정 파일의 크기를 읽지 못했습니다: " + Modules.FPS.scale.get());
		int oldX = context.computeOnClient(minecraft -> Modules.FPS.x(minecraft, 400) * 4 - (400 - Modules.FPS.width(minecraft)));
		check(Math.abs(oldX) <= 4, "예전 설정 파일의 HUD 위치를 읽지 못했습니다");
		check(!Modules.TITLE.isMoved() && Modules.TITLE.isEnabled() && exactly(Modules.TITLE.scale.get(), 1.0), "예전 설정 파일을 읽은 뒤 타이틀 요소가 기본 상태가 아닙니다");
		check(Modules.CHAT.isEnabled() && exactly(Modules.CHAT.scale.get(), 1.0), "예전 설정 파일을 읽은 뒤 채팅 요소가 기본 상태가 아닙니다");
		context.runOnClient(minecraft -> Modules.FPS.scale.reset());

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

	/** 계산 오차만 허용하는 비교 (1% 단위 크기처럼 near 보다 촘촘해야 하는 값). */
	private static boolean exactly(double actual, double expected) {
		return Math.abs(actual - expected) < 1.0e-6;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
