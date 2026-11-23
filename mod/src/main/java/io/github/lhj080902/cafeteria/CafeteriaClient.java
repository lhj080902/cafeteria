package io.github.lhj080902.cafeteria;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.lhj080902.cafeteria.config.ConfigManager;
import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.hud.HudRenderer;
import io.github.lhj080902.cafeteria.module.Module;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
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

	@Override
	public void onInitializeClient() {
		menuKey = register("key.cafeteria.menu", GLFW.GLFW_KEY_RIGHT_SHIFT);
		freelookKey = register("key.cafeteria.freelook", GLFW.GLFW_KEY_LEFT_ALT);
		zoomKey = register("key.cafeteria.zoom", GLFW.GLFW_KEY_C);
		ConfigManager.load();
		ClientTickEvents.END_CLIENT_TICK.register(CafeteriaClient::tick);
		// 채팅보다 아래 레이어에 그려서 채팅창을 가리지 않게 한다.
		HudLayerRegistrationCallback.EVENT.register(layers -> layers.attachLayerBefore(
			IdentifiedLayer.CHAT, ResourceLocation.fromNamespaceAndPath(MOD_ID, "hud"), (graphics, deltaTracker) -> HudRenderer.render(graphics)));
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> ConfigManager.save());
		LOGGER.info("Cafeteria Client 로드 완료 (모듈 {}개)", Modules.ALL.size());
	}

	private static KeyMapping register(String name, int defaultKey) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, KEY_CATEGORY));
	}

	private static void tick(Minecraft minecraft) {
		while (menuKey.consumeClick()) {
			if (minecraft.screen == null) {
				minecraft.setScreen(new CafeteriaScreen(null));
			}
		}

		for (Module module : Modules.ALL) {
			module.tick(minecraft);
		}
	}
}
