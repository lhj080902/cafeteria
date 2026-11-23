package io.github.lhj080902.cafeteria;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.hud.CombatTracker;
import io.github.lhj080902.cafeteria.hud.HudRenderer;
import io.github.lhj080902.cafeteria.module.Module;
import io.github.lhj080902.cafeteria.util.ScreenshotClipboard;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CafeteriaClient implements ClientModInitializer {
	public static final String MOD_ID = "cafeteria";
	public static final Logger LOGGER = LoggerFactory.getLogger("Cafeteria");

	private static final String KEY_CATEGORY = "key.categories.cafeteria";

	/** 키 설정 화면의 "Cafeteria Client" 항목에서 바꿀 수 있다. */
	public static KeyMapping menuKey;
	public static KeyMapping freelookKey;
	public static KeyMapping zoomKey;
	public static KeyMapping fullbrightKey;

	@Override
	public void onInitializeClient() {
		// 스크린샷을 클립보드에 넣을 수 있도록, 자바 화면 기능이 처음 쓰이기 전에 준비해 둔다.
		ScreenshotClipboard.prepare();
		menuKey = register("key.cafeteria.menu", GLFW.GLFW_KEY_RIGHT_SHIFT);
		freelookKey = register("key.cafeteria.freelook", GLFW.GLFW_KEY_LEFT_ALT);
		zoomKey = register("key.cafeteria.zoom", GLFW.GLFW_KEY_C);
		fullbrightKey = register("key.cafeteria.fullbright", GLFW.GLFW_KEY_G);
		// 한 번 눌러서 동작하는 키는 다른 키 설정과 겹쳐도 동작하도록 직접 센다.
		KeyState.track(menuKey);
		KeyState.track(freelookKey);
		KeyState.track(fullbrightKey);
		ConfigManager.load();
		ClientTickEvents.END_CLIENT_TICK.register(CafeteriaClient::tick);
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			Minecraft minecraft = Minecraft.getInstance();

			if (level.isClientSide() && player == minecraft.player) {
				CombatTracker.onAttack(minecraft, entity);
			}

			return InteractionResult.PASS;
		});
		// 채팅보다 아래 레이어에 그려서 채팅창을 가리지 않게 한다.
		HudLayerRegistrationCallback.EVENT.register(layers -> layers.attachLayerBefore(
			IdentifiedLayer.CHAT, ResourceLocation.fromNamespaceAndPath(MOD_ID, "hud"), (graphics, deltaTracker) -> HudRenderer.render(graphics)));
		// 스크린샷 안내의 [복사] 버튼이 실행하는 명령어: /cafeteria screenshot copy <파일 이름>
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("cafeteria").then(ClientCommandManager.literal("screenshot").then(ClientCommandManager.literal("copy").then(
				ClientCommandManager.argument("file", StringArgumentType.greedyString()).executes(context -> {
					Modules.SCREENSHOT.copyByName(StringArgumentType.getString(context, "file"));
					return 1;
				}))))));
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> ConfigManager.save());
		LOGGER.info("Cafeteria Client 로드 완료 (모듈 {}개)", Modules.ALL.size());
	}

	private static KeyMapping register(String name, int defaultKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, KEY_CATEGORY));
	}

	private static void tick(Minecraft minecraft) {
		while (KeyState.consumeClick(menuKey)) {
			if (minecraft.screen == null) {
				minecraft.setScreen(new CafeteriaScreen(null));
			}
		}

		while (KeyState.consumeClick(fullbrightKey)) {
			Modules.FULLBRIGHT.toggle();
			ConfigManager.save();
			// 켜졌는지 꺼졌는지 핫바 위에 잠깐 알려 준다.
			minecraft.gui.setOverlayMessage(
				Component.translatable(Modules.FULLBRIGHT.isEnabled() ? "cafeteria.message.fullbright_on" : "cafeteria.message.fullbright_off"), false);
		}

		for (Module module : Modules.ALL) {
			module.tick(minecraft);
		}

		CombatTracker.tick(minecraft);
	}
}
