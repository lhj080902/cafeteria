package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.module.setting.ColorSetting;
import io.github.lhj080902.cafeteria.module.setting.ModeSetting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 채팅 시간. 채팅창에 올라오는 메시지 왼쪽에 받은 시각을 붙인다. 켠 뒤에 올라온 메시지부터 적용된다. */
public class ChatTimeModule extends Module {
	private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter HOUR_MINUTE_SECOND = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
	private static final DateTimeFormatter TWELVE_HOUR_KOREAN = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
	private static final DateTimeFormatter TWELVE_HOUR_ENGLISH = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	public final ModeSetting format = add(new ModeSetting("chat_time_format", "hm24", "hm24", "hms24", "hm12"));
	public final ColorSetting color = add(new ColorSetting("chat_time_color", 0xFFAAAAAA));
	public final BooleanSetting brackets = add(new BooleanSetting("chat_time_brackets", true));

	public ChatTimeModule() {
		super("chat_time", true);
	}

	/** 채팅창에 넣을 메시지에 지금 시각을 붙인다. 모듈이 꺼져 있으면 그대로 돌려준다. */
	public GuiMessage decorate(GuiMessage message) {
		if (!isEnabled()) {
			return message;
		}

		return new GuiMessage(message.addedTime(), withTime(message.content(), LocalTime.now()), message.signature(), message.tag());
	}

	public Component withTime(Component content, LocalTime time) {
		String text = formatTime(time);

		if (brackets.get()) {
			text = "[" + text + "]";
		}

		// 빈 컴포넌트를 뿌리로 써서, 시간 글자의 색이 원래 메시지로 번지지 않게 한다.
		return Component.empty()
			.append(Component.literal(text + " ").withStyle(style -> style.withColor(TextColor.fromRgb(color.get() & 0xFFFFFF))))
			.append(content);
	}

	public String formatTime(LocalTime time) {
		if (format.is("hms24")) {
			return HOUR_MINUTE_SECOND.format(time);
		}

		if (format.is("hm12")) {
			return (isKorean() ? TWELVE_HOUR_KOREAN : TWELVE_HOUR_ENGLISH).format(time);
		}

		return HOUR_MINUTE.format(time);
	}

	private static boolean isKorean() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.getLanguageManager().getSelected().startsWith("ko");
	}
}
