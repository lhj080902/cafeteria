package io.github.lhj080902.cafeteria.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lhj080902.cafeteria.CafeteriaClient;
import io.github.lhj080902.cafeteria.Modules;
import io.github.lhj080902.cafeteria.module.Module;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 모듈 켜짐 상태와 설정을 config/cafeteria.json 에 저장하고 불러온다. */
public final class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ConfigManager() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("cafeteria.json");
	}

	public static void load() {
		Path file = file();

		if (!Files.isRegularFile(file)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);
			JsonElement modules = root == null ? null : root.get("modules");

			if (modules == null || !modules.isJsonObject()) {
				return;
			}

			for (Module module : Modules.ALL) {
				JsonElement json = modules.getAsJsonObject().get(module.id());

				if (json != null && json.isJsonObject()) {
					module.fromJson(json.getAsJsonObject());
				}
			}
		} catch (Exception e) {
			// 설정 파일이 깨졌어도 게임은 기본값으로 계속 실행한다.
			CafeteriaClient.LOGGER.warn("설정 파일을 읽지 못해 기본값을 사용합니다: {}", file, e);
		}
	}

	public static void save() {
		Path file = file();
		JsonObject modules = new JsonObject();

		for (Module module : Modules.ALL) {
			modules.add(module.id(), module.toJson());
		}

		JsonObject root = new JsonObject();
		root.add("modules", modules);

		try {
			Files.createDirectories(file.getParent());
			// 쓰는 도중 게임이 꺼져도 기존 설정이 깨지지 않도록 임시 파일에 쓴 뒤 바꿔치기한다.
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");

			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, writer);
			}

			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			CafeteriaClient.LOGGER.warn("설정 파일을 저장하지 못했습니다: {}", file, e);
		}
	}
}
