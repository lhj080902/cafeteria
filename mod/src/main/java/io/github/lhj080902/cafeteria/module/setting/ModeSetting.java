package io.github.lhj080902.cafeteria.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.List;

/** 정해진 선택지 중 하나를 고르는 설정. 표시 이름은 cafeteria.mode.<값> 번역 키를 쓴다. */
public class ModeSetting extends Setting<String> {
	private final List<String> options;

	public ModeSetting(String id, String defaultValue, String... options) {
		super(id, defaultValue);
		this.options = List.of(options);

		if (!this.options.contains(defaultValue)) {
			throw new IllegalArgumentException("default value is not one of the options: " + defaultValue);
		}
	}

	public List<String> options() {
		return options;
	}

	public boolean is(String option) {
		return get().equals(option);
	}

	@Override
	protected String sanitize(String value) {
		return options != null && options.contains(value) ? value : defaultValue();
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString() && options.contains(json.getAsString())) {
			set(json.getAsString());
		}
	}
}
