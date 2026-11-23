package io.github.lhj080902.cafeteria.test;

import io.github.lhj080902.cafeteria.CafeteriaClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버 리소스팩 호환성 테스트 (선택 실행).
 *
 * <p>리소스팩을 요구하는 전용 서버를 띄워서 접속 → 나가기 → 재접속을 되풀이하고, 그때마다 리소스를 다시 읽은 횟수와
 * 걸린 시간을 로그에 남긴다. 서버 리소스팩을 다루는 다른 모드(예: 재접속 때 다시 적용하지 않게 하는 모드)를
 * Cafeteria 와 함께 넣었을 때 제대로 동작하는지 보는 용도다.
 *
 * <p>리소스팩 주소를 시스템 속성으로 줬을 때만 실행된다. 팩은 tools/pack_server.py 로 내보낸다.
 * <pre>
 *   -Dcafeteria.test.packUrl=http://127.0.0.1:포트/pack.zip -Dcafeteria.test.packSha1=해시
 *   [-Dcafeteria.test.serverPort=포트] [-Dcafeteria.test.sameServerOnly=true]
 * </pre>
 */
public class ServerResourcePackTest implements FabricClientGameTest {
	private static final String URL_PROPERTY = "cafeteria.test.packUrl";
	private static final String SHA1_PROPERTY = "cafeteria.test.packSha1";
	private static final String PORT_PROPERTY = "cafeteria.test.serverPort";
	/** true 면 "같은 서버 재접속" 단계만 하고 끝낸다 (서버에 있다가 게임을 끈 상황을 만들 때 쓴다) */
	private static final String SAME_SERVER_ONLY_PROPERTY = "cafeteria.test.sameServerOnly";
	/** tools/pack_server.py 가 만든 팩이 바꿔 놓는 번역. 이 값이 보이면 팩이 적용된 것이다. */
	private static final String MARKER_KEY = "menu.singleplayer";
	private static final String MARKER_VALUE = "CAFETERIA PACK TEST";
	private static final int TIMEOUT_TICKS = 20 * 180;
	private static final int ROUNDS = 3;

	/** 이 테스트가 요청됐는지. 요청됐으면 기본 테스트는 건너뛴다. */
	static boolean requested() {
		return System.getProperty(URL_PROPERTY) != null;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!requested()) {
			return;
		}

		AtomicInteger reloads = new AtomicInteger();
		context.runOnClient(minecraft -> ((ReloadableResourceManager) minecraft.getResourceManager())
			.registerReloadListener((ResourceManagerReloadListener) manager -> reloads.incrementAndGet()));

		Properties properties = new Properties();
		properties.setProperty("resource-pack", System.getProperty(URL_PROPERTY));
		properties.setProperty("resource-pack-sha1", System.getProperty(SHA1_PROPERTY, ""));
		properties.setProperty("require-resource-pack", "true");

		if (System.getProperty(PORT_PROPERTY) != null) {
			properties.setProperty("server-port", System.getProperty(PORT_PROPERTY));
		}

		report(context, "게임 시작 직후", reloads, 0L);

		// 1) 같은 서버에 접속 → 나가기 → 재접속을 되풀이한다.
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
			int port = server.computeOnServer(MinecraftServer::getPort);

			for (int round = 1; round <= ROUNDS; round++) {
				visit(context, port, "같은 서버 " + round + "번째", reloads);
			}
		}

		if (Boolean.getBoolean(SAME_SERVER_ONLY_PROPERTY)) {
			CafeteriaClient.LOGGER.info("PACKTEST 완료");
			return;
		}

		// 2) 같은 팩을 쓰는 다른 서버(다른 포트)로 간다.
		if (System.getProperty(PORT_PROPERTY) != null) {
			properties.setProperty("server-port", String.valueOf(Integer.parseInt(System.getProperty(PORT_PROPERTY)) + 1));
		}

		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
			visit(context, server.computeOnServer(MinecraftServer::getPort), "다른 서버", reloads);
		}

		// 3) 싱글 월드에 들어간다. 서버 리소스팩이 싱글 월드까지 따라오면 안 된다.
		long started = System.nanoTime();

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(5);
			context.waitFor(minecraft -> minecraft.getOverlay() == null, TIMEOUT_TICKS);
			context.waitTicks(20);
			report(context, "싱글 월드 진입", reloads, started);

			if (packApplied(context)) {
				throw new AssertionError("싱글 월드에 서버 리소스팩이 그대로 남아 있습니다");
			}
		}

		CafeteriaClient.LOGGER.info("PACKTEST 완료");
	}

	/** 서버에 들어갔다가 나오면서 단계마다 기록을 남긴다. 들어갔을 때 팩이 적용돼 있지 않으면 실패. */
	private static void visit(ClientGameTestContext context, int port, String name, AtomicInteger reloads) {
		long started = System.nanoTime();
		join(context, port);
		report(context, name + " 접속", reloads, started);

		if (!packApplied(context)) {
			throw new AssertionError(name + " 접속에서 서버 리소스팩이 적용되지 않았습니다");
		}

		started = System.nanoTime();
		leave(context);
		report(context, name + " 나가기", reloads, started);
	}

	private static boolean packApplied(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> MARKER_VALUE.equals(I18n.get(MARKER_KEY)));
	}

	private static void report(ClientGameTestContext context, String step, AtomicInteger reloads, long startedNanos) {
		String elapsed = startedNanos == 0L ? "-" : String.valueOf((System.nanoTime() - startedNanos) / 1_000_000L);
		CafeteriaClient.LOGGER.info("PACKTEST {} | 리소스 다시 읽은 횟수(누적)={} | 팩 적용됨={} | 걸린 시간(ms)={}", step, reloads.get(), packApplied(context), elapsed);
	}

	/** 서버 리소스팩을 묻지 않고 받도록 설정한 서버 항목으로 접속하고, 월드에 들어갈 때까지 기다린다. */
	private static void join(ClientGameTestContext context, int port) {
		String address = "localhost:" + port;
		context.runOnClient(minecraft -> {
			ServerData server = new ServerData("Cafeteria pack test", address, ServerData.Type.OTHER);
			server.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
			ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address), server, false, null);
		});
		context.waitFor(minecraft -> minecraft.level != null && minecraft.player != null && minecraft.getOverlay() == null && minecraft.screen == null, TIMEOUT_TICKS);
		context.waitTicks(20);
	}

	private static void leave(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			minecraft.level.disconnect();
			minecraft.disconnect();
		});
		context.waitFor(minecraft -> minecraft.level == null, TIMEOUT_TICKS);
		context.setScreen(TitleScreen::new);
		// 팩을 내리느라 리소스를 다시 읽는 경우 그것이 끝날 때까지 기다린다.
		context.waitTicks(5);
		context.waitFor(minecraft -> minecraft.getOverlay() == null, TIMEOUT_TICKS);
		context.waitTicks(20);
	}
}
