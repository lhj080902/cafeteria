package io.github.lhj080902.cafeteria.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;
	private final String suffix;

	public NumberSetting(String id, double defaultValue, double min, double max, double step, String suffix) {
		super(id, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.suffix = suffix;
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public float asFloat() {
		return get().floatValue();
	}

	@Override
	protected Double sanitize(Double value) {
		if (value == null || value.isNaN()) {
			return defaultValue();
		}

		double snapped = min + Math.round((value - min) / step) * step;
		return Math.max(min, Math.min(max, snapped));
	}

	public String format() {
		// 단위가 0.1 보다 작으면(HUD 크기의 1% 단위) 소수 둘째 자리까지 보여 준다.
		String number = step >= 1.0 ? String.valueOf(Math.round(get())) : String.format(Locale.ROOT, step < 0.1 ? "%.2f" : "%.1f", get());
		return number + suffix;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(get());
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
			set(json.getAsDouble());
		}
	}
}
