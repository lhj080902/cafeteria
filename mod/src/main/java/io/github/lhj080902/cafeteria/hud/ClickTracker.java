package io.github.lhj080902.cafeteria.hud;

import java.util.ArrayDeque;

/** 최근 1초 동안의 마우스 클릭 횟수(CPS)를 센다. 클릭은 MouseHandlerMixin 이 기록한다. */
public final class ClickTracker {
	public static final int LEFT = 0;
	public static final int RIGHT = 1;

	private static final long WINDOW_MS = 1000L;
	private static final ArrayDeque<Long> LEFT_CLICKS = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT_CLICKS = new ArrayDeque<>();

	private ClickTracker() {
	}

	public static void record(int button) {
		ArrayDeque<Long> clicks = clicks(button);

		if (clicks != null) {
			clicks.addLast(System.currentTimeMillis());
		}
	}

	public static int cps(int button) {
		ArrayDeque<Long> clicks = clicks(button);

		if (clicks == null) {
			return 0;
		}

		long oldest = System.currentTimeMillis() - WINDOW_MS;

		while (!clicks.isEmpty() && clicks.peekFirst() < oldest) {
			clicks.removeFirst();
		}

		return clicks.size();
	}

	private static ArrayDeque<Long> clicks(int button) {
		return switch (button) {
			case LEFT -> LEFT_CLICKS;
			case RIGHT -> RIGHT_CLICKS;
			default -> null;
		};
	}
}
