package io.github.lhj080902.cafeteria.module.setting;

import com.google.gson.JsonElement;
import net.minecraft.network.chat.Component;

/** 모듈 하나가 가지는 설정값 하나. 값은 config/cafeteria.json 에 저장된다. */
public abstract class Setting<T> {
	private final String id;
	private final T defaultValue;
	private T value;

	protected Setting(String id, T defaultValue) {
		this.id = id;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String id() {
		return id;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = sanitize(value);
	}

	public void reset() {
		this.value = defaultValue;
	}

	public T defaultValue() {
		return defaultValue;
	}

	/** 범위를 벗어난 값이 들어오면 허용 범위로 보정한다. */
	protected T sanitize(T value) {
		return value == null ? defaultValue : value;
	}

	public Component label() {
		return Component.translatable("cafeteria.setting." + id);
	}

	public abstract JsonElement toJson();

	/** 형식이 맞지 않는 값은 무시하고 현재 값을 유지한다. */
	public abstract void fromJson(JsonElement json);
}
