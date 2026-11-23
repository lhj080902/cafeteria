package io.github.lhj080902.cafeteria.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.lhj080902.cafeteria.module.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 켜고 끌 수 있는 기능 하나(프리룩, 줌, 히트박스 ...). */
public abstract class Module {
	private final String id;
	private final boolean defaultEnabled;
	private final List<Setting<?>> settings = new ArrayList<>();
	private boolean enabled;

	protected Module(String id, boolean defaultEnabled) {
		this.id = id;
		this.defaultEnabled = defaultEnabled;
		this.enabled = defaultEnabled;
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public String id() {
		return id;
	}

	public Component name() {
		return Component.translatable("cafeteria.module." + id);
	}

	public Component description() {
		return Component.translatable("cafeteria.module." + id + ".desc");
	}

	public List<Setting<?>> settings() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	/** 클라이언트 틱마다(초당 20번) 호출된다. */
	public void tick(Minecraft minecraft) {
	}

	public void resetSettings() {
		settings.forEach(Setting::reset);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("enabled", enabled);
		JsonObject values = new JsonObject();

		for (Setting<?> setting : settings) {
			values.add(setting.id(), setting.toJson());
		}

		json.add("settings", values);
		return json;
	}

	public void fromJson(JsonObject json) {
		JsonElement enabledJson = json.get("enabled");
		enabled = enabledJson != null && enabledJson.isJsonPrimitive() && enabledJson.getAsJsonPrimitive().isBoolean()
			? enabledJson.getAsBoolean()
			: defaultEnabled;
		JsonElement values = json.get("settings");

		if (values != null && values.isJsonObject()) {
			for (Setting<?> setting : settings) {
				JsonElement value = values.getAsJsonObject().get(setting.id());

				if (value != null) {
					setting.fromJson(value);
				}
			}
		}
	}
}
