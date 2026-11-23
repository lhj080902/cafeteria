package io.github.lhj080902.cafeteria.module;

import io.github.lhj080902.cafeteria.gui.CafeteriaScreen;
import io.github.lhj080902.cafeteria.module.setting.BooleanSetting;
import io.github.lhj080902.cafeteria.util.ScreenshotClipboard;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 스크린샷 도구. F2 로 스크린샷을 찍으면 채팅에 뜨는 안내 옆에 [복사] [폴더] 버튼을 붙인다.
 * [복사]를 누르면 그 스크린샷이 그림으로 클립보드에 들어가서 메신저 등에 바로 붙여 넣을 수 있다.
 */
public class ScreenshotModule extends Module {
	/** [복사] 버튼이 실행하는 클라이언트 명령어. 서버로는 가지 않는다. */
	public static final String COPY_COMMAND = "cafeteria screenshot copy ";
	/** 스크린샷 폴더 안의 PNG 파일 이름만 받는다 (폴더 구분자나 상위 폴더 표시는 허용하지 않는다). */
	private static final Pattern FILE_NAME = Pattern.compile("[\\w.\\- ()]+\\.png", Pattern.UNICODE_CHARACTER_CLASS);

	public final BooleanSetting autoCopy = add(new BooleanSetting("screenshot_auto_copy", false));

	public ScreenshotModule() {
		super("screenshot", true);
	}

	/** 스크린샷 저장 결과를 채팅에 알리는 함수를 감싸서, 저장에 성공했을 때 버튼을 붙이고 필요하면 바로 복사한다. */
	public Consumer<Component> wrap(Consumer<Component> original) {
		return message -> {
			Path file = isEnabled() ? savedFile(message) : null;

			if (file == null) {
				original.accept(message);
				return;
			}

			original.accept(withButtons(message, file));

			if (autoCopy.get()) {
				copy(file);
			}
		};
	}

	/** 바닐라의 "스크린샷을 저장했습니다" 안내에서 저장된 파일 경로를 꺼낸다. 다른 안내면 null. */
	private static Path savedFile(Component message) {
		if (!(message.getContents() instanceof TranslatableContents contents) || !"screenshot.success".equals(contents.getKey())) {
			return null;
		}

		for (Object argument : contents.getArgs()) {
			if (argument instanceof Component name) {
				ClickEvent click = name.getStyle().getClickEvent();

				if (click != null && click.getAction() == ClickEvent.Action.OPEN_FILE) {
					try {
						return Path.of(click.getValue());
					} catch (InvalidPathException e) {
						return null;
					}
				}
			}
		}

		return null;
	}

	private static Component withButtons(Component message, Path file) {
		MutableComponent result = Component.empty().append(message).append(" ")
			.append(button("cafeteria.screenshot.copy", new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COPY_COMMAND + file.getFileName())));
		Path folder = file.toAbsolutePath().getParent();

		if (folder != null) {
			result.append(" ").append(button("cafeteria.screenshot.folder", new ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toString())));
		}

		return result;
	}

	private static Component button(String key, ClickEvent click) {
		return Component.translatable(key).withStyle(style -> style
			.withColor(TextColor.fromRgb(CafeteriaScreen.ACCENT & 0xFFFFFF))
			.withClickEvent(click)
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".hint"))));
	}

	/** 스크린샷 폴더에 있는 파일을 이름으로 찾아 클립보드에 복사한다 ([복사] 버튼이 부르는 명령어). */
	public void copyByName(String fileName) {
		Minecraft minecraft = Minecraft.getInstance();
		Path file = minecraft.gameDirectory.toPath().resolve("screenshots").resolve(fileName == null ? "" : fileName.trim());

		if (fileName == null || !FILE_NAME.matcher(fileName.trim()).matches() || !Files.isRegularFile(file)) {
			minecraft.gui.getChat().addMessage(Component.translatable("cafeteria.screenshot.not_found").withStyle(ChatFormatting.RED));
			return;
		}

		copy(file);
	}

	/** 클립보드에 복사하고 결과를 핫바 위에 잠깐 알려 준다. 실패하면 이유를 채팅에 남긴다. */
	private void copy(Path file) {
		Minecraft minecraft = Minecraft.getInstance();
		ScreenshotClipboard.copy(file).whenComplete((nothing, error) -> minecraft.execute(() -> {
			if (error == null) {
				minecraft.gui.setOverlayMessage(Component.translatable("cafeteria.screenshot.copied"), false);
			} else {
				Throwable cause = error.getCause() != null ? error.getCause() : error;
				minecraft.gui.getChat().addMessage(Component.translatable("cafeteria.screenshot.copy_failed", String.valueOf(cause.getMessage())).withStyle(ChatFormatting.RED));
			}
		}));
	}
}
