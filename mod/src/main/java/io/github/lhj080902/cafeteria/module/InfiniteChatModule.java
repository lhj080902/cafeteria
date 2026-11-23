package io.github.lhj080902.cafeteria.module;

/**
 * 무한 채팅. 바닐라는 채팅창에 최근 100줄만 남기고 지우는데, 이 제한을 사실상 없애서 한참 전 채팅까지 올려 볼 수 있게 한다.
 * 메모리를 끝없이 쓰지 않도록 아주 큰 상한은 둔다.
 */
public class InfiniteChatModule extends Module {
	/** 켜져 있을 때 보관하는 최대 줄 수. */
	public static final int LIMIT = 100_000;

	public InfiniteChatModule() {
		super("infinite_chat", true);
	}

	/** 채팅창이 보관할 최대 줄 수. 모듈이 꺼져 있으면 바닐라 값(100)을 그대로 쓴다. */
	public int limit(int vanilla) {
		return isEnabled() ? Math.max(vanilla, LIMIT) : vanilla;
	}
}
