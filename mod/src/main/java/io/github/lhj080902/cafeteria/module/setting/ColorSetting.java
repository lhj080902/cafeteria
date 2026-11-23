package io.github.lhj080902.cafeteria.module.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** ARGB 색상 설정. 설정 파일에는 "#AARRGGBB" 문자열로 저장한다. */
public class ColorSetting extends Setting<Integer> {
	/** 설정 화면에서 클릭할 때마다 순서대로 바뀌는 기본 색상표. */
	public static final int[] PALETTE = {
		0xFFFFFFFF, 0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF00AA00,
		0xFF55FFFF, 0xFF5555FF, 0xFFAA55FF, 0xFFFF55FF, 0xFF000000
	};

	public ColorSetting(String id, int defaultValue) {
		super(id, defaultValue);
	}

	public float red() {
		return (get() >> 16 & 0xFF) / 255.0F;
	}

	public float green() {
		return (get() >> 8 & 0xFF) / 255.0F;
	}

	public float blue() {
		return (get() & 0xFF) / 255.0F;
	}

	public float alpha() {
		return (get() >>> 24) / 255.0F;
	}

	/** 색상표의 다음 색으로 바꾼다. 현재 색이 색상표에 없으면 첫 번째 색으로 간다. */
	public void cycle() {
		int index = -1;

		for (int i = 0; i < PALETTE.length; i++) {
			if (PALETTE[i] == get()) {
				index = i;
				break;
			}
		}

		set(PALETTE[(index + 1) % PALETTE.length]);
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(String.format("#%08X", get()));
	}

	@Override
	public void fromJson(JsonElement json) {
		if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
			return;
		}

		String text = json.getAsString();

		if (text.matches("#[0-9a-fA-F]{8}")) {
			set((int) Long.parseLong(text.substring(1), 16));
		}
	}
}
