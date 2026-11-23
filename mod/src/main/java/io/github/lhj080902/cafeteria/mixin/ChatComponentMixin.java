package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
			shift = At.Shift.AFTER))
	private GuiMessage cafeteria$addTime(GuiMessage message) {
		return Modules.CHAT_TIME.decorate(message);
	}

	/** 무한 채팅: 화면에 보이는 줄(줄바꿈된 줄 단위)의 보관 한도 100줄을 늘린다. */
	@ModifyConstant(method = "addMessageToDisplayQueue", constant = @Constant(intValue = 100))
	private int cafeteria$moreDisplayedLines(int limit) {
		return Modules.INFINITE_CHAT.limit(limit);
	}

	/** 무한 채팅: 메시지 원본의 보관 한도 100개를 늘린다 (창 크기를 바꿀 때 이 목록으로 다시 줄바꿈한다). */
	@ModifyConstant(method = "addMessageToQueue", constant = @Constant(intValue = 100))
	private int cafeteria$moreStoredMessages(int limit) {
		return Modules.INFINITE_CHAT.limit(limit);
	}
}
