package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 채팅창은 다른 채팅 모드도 자주 손대는 곳이라, 여기 믹스인은 모두 "적용하지 못해도 게임은 켜지게"(require = 0) 해 둔다.
 * 적용하지 못하면 그 기능만 동작하지 않는다.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	/**
	 * 채팅 시간: 채팅창에 올라가는 메시지 앞에 받은 시각을 붙인다.
	 * 로그 파일에는 원래 내용이 남도록, 로그를 남긴 다음(logChatMessage 뒤)에 바꾼다.
	 */
	@ModifyVariable(
		method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/ChatComponent;logChatMessage(Lnet/minecraft/client/GuiMessage;)V",
			shift = At.Shift.AFTER),
		require = 0)
	private GuiMessage cafeteria$addTime(GuiMessage message) {
		return Modules.CHAT_TIME.decorate(message);
	}

	/**
	 * 채팅 배율: 채팅창이 쓰는 글자 배율에 채팅 요소의 크기를 곱한다.
	 * 그리기, 줄바꿈, 마우스 좌표 변환이 모두 이 값을 거치므로 글자·줄 간격과 클릭 위치가 함께 맞는다.
	 */
	@ModifyReturnValue(method = "getScale", at = @At("RETURN"), require = 0)
	private double cafeteria$scaleChat(double original) {
		return original * Modules.CHAT.factor();
	}

	/** 채팅 배율: 폭도 같은 비율로 늘려서 한 줄에 들어가는 글자 수가 그대로이게 한다. */
	@ModifyReturnValue(method = "getWidth()I", at = @At("RETURN"), require = 0)
	private int cafeteria$widenChat(int original) {
		return Mth.floor(original * Modules.CHAT.factor());
	}

	/** 무한 채팅: 화면에 보이는 줄(줄바꿈된 줄 단위)의 보관 한도 100줄을 늘린다. */
	@ModifyExpressionValue(method = "addMessageToDisplayQueue", at = @At(value = "CONSTANT", args = "intValue=100"), require = 0)
	private int cafeteria$moreDisplayedLines(int limit) {
		return Modules.INFINITE_CHAT.limit(limit);
	}

	/** 무한 채팅: 메시지 원본의 보관 한도 100개를 늘린다 (창 크기를 바꿀 때 이 목록으로 다시 줄바꿈한다). */
	@ModifyExpressionValue(method = "addMessageToQueue", at = @At(value = "CONSTANT", args = "intValue=100"), require = 0)
	private int cafeteria$moreStoredMessages(int limit) {
		return Modules.INFINITE_CHAT.limit(limit);
	}
}
