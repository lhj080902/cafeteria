package io.github.lhj080902.cafeteria.hud;

import io.github.lhj080902.cafeteria.gui.HudEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

/**
 * 채팅창 배율. 바닐라 설정의 "채팅 글자 크기"에 이 요소의 크기를 곱해서 글자·줄 간격·폭·높이를 같은 비율로 바꾼다
 * (한 줄에 들어가는 글자 수는 그대로다). 실제 적용은 ChatComponentMixin 이 {@link #factor()} 를 읽어서 한다.
 * 채팅창은 옮기지 않는다. 왼쪽 아래가 고정이고, 편집 화면에서는 오른쪽 위 손잡이·휠·설정으로 크기만 바꾼다.
 */
public class ChatHud extends HudElement {
	/** 바닐라 채팅창의 아랫변은 화면 아래에서 40픽셀 위에 있다. */
	private static final int BOTTOM_MARGIN = 40;

	/** 채팅 줄바꿈에 마지막으로 반영한 배율. */
	private double applied = 1.0;

	public ChatHud() {
		super("chat", true, 0.0, 1.0);
	}

	/** 채팅에 곱할 배율. 꺼져 있으면 1 (바닐라 그대로). */
	public double factor() {
		return isEnabled() ? scale.get() : 1.0;
	}

	@Override
	public boolean movable() {
		return false;
	}

	@Override
	public boolean underlay() {
		return true;
	}

	@Override
	public boolean hasContent(Minecraft minecraft) {
		// 채팅은 바닐라가 그린다. HudRenderer 는 이 요소를 그리지 않는다.
		return false;
	}

	private static double vanillaScale(Minecraft minecraft) {
		return minecraft.options.chatScale().get();
	}

	/** 배율 100%일 때 채팅창이 차지하는 너비: 채팅 폭 설정에 좌우 여백(글자 크기에 비례)을 더한 값. */
	@Override
	protected int contentWidth(Minecraft minecraft) {
		return (int) Math.round(ChatComponent.getWidth(minecraft.options.chatWidth().get()) + 12.0 * vanillaScale(minecraft));
	}

	/** 배율 100%일 때 닫힌 채팅창의 높이: 한 번에 보이는 줄 수만큼. */
	@Override
	protected int contentHeight(Minecraft minecraft) {
		int lineHeight = (int) (9.0 * (minecraft.options.chatLineSpacing().get() + 1.0));
		int lines = ChatComponent.getHeight(minecraft.options.chatHeightUnfocused().get()) / lineHeight;
		return (int) Math.round(lines * lineHeight * vanillaScale(minecraft));
	}

	@Override
	public int x(Minecraft minecraft, int screenWidth) {
		return 0;
	}

	@Override
	public int y(Minecraft minecraft, int screenHeight) {
		return screenHeight - BOTTOM_MARGIN - height(minecraft);
	}

	@Override
	public void setPosition(Minecraft minecraft, double x, double y, int screenWidth, int screenHeight) {
		// 채팅창은 옮기지 않는다.
	}

	@Override
	public void resetPosition() {
	}

	@Override
	public void rescale(Minecraft minecraft, double value, int screenWidth, int screenHeight) {
		scale.set(value);
	}

	/** 편집 화면에서만 불린다. 채팅창 자리를 옅게 칠하고, 글자 크기를 가늠할 수 있게 예시 한 줄을 맨 위에 적는다. */
	@Override
	protected void draw(GuiGraphics graphics, Minecraft minecraft) {
		float textScale = (float) vanillaScale(minecraft);
		graphics.fill(0, 0, contentWidth(minecraft), contentHeight(minecraft), 0x40000000);
		graphics.pose().pushPose();
		graphics.pose().scale(textScale, textScale, 1.0F);
		graphics.drawString(minecraft.font, Component.translatable("cafeteria.hud.sample.chat"), 4, 1, 0xFFFFFFFF);
		graphics.pose().popPose();
	}

	/** 배율이 바뀌면 채팅 줄바꿈을 다시 한다. 꼭짓점을 끄는 동안에는 값이 계속 바뀌므로 놓은 뒤에 한 번만 한다. */
	@Override
	public void tick(Minecraft minecraft) {
		double factor = factor();

		if (factor == applied || minecraft.screen instanceof HudEditScreen screen && screen.isResizing()) {
			return;
		}

		applied = factor;
		minecraft.gui.getChat().rescaleChat();
	}
}
