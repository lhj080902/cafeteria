package io.github.lhj080902.cafeteria.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public class BooleanSetting extends Setting<Boolean> {
	public BooleanSetting(String id, boolean defaultValue) {
		super(id, defaultValue);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
			set(json.getAsBoolean());
		}
	}
}
