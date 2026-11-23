package io.github.lhj080902.cafeteria;

import io.github.lhj080902.cafeteria.hud.CpsHud;
import io.github.lhj080902.cafeteria.hud.FpsHud;
import io.github.lhj080902.cafeteria.hud.HudElement;
import io.github.lhj080902.cafeteria.hud.KeystrokesHud;
import io.github.lhj080902.cafeteria.hud.PingHud;
import io.github.lhj080902.cafeteria.module.FreelookModule;
import io.github.lhj080902.cafeteria.module.FullbrightModule;
import io.github.lhj080902.cafeteria.module.HitboxModule;
import io.github.lhj080902.cafeteria.module.Module;
import io.github.lhj080902.cafeteria.module.ZoomModule;

import java.util.List;

/** 클라이언트에 들어 있는 모든 모듈. 믹스인에서도 바로 쓸 수 있게 정적 필드로 둔다. */
public final class Modules {
	public static final FreelookModule FREELOOK = new FreelookModule();
	public static final ZoomModule ZOOM = new ZoomModule();
	public static final HitboxModule HITBOX = new HitboxModule();
	public static final FullbrightModule FULLBRIGHT = new FullbrightModule();
	public static final FpsHud FPS = new FpsHud();
	public static final CpsHud CPS = new CpsHud();
	public static final PingHud PING = new PingHud();
	public static final KeystrokesHud KEYSTROKES = new KeystrokesHud();

	/** 설정 화면에 나오는 순서. */
	public static final List<Module> ALL = List.of(FREELOOK, ZOOM, HITBOX, FULLBRIGHT, FPS, CPS, PING, KEYSTROKES);
	public static final List<HudElement> HUD = List.of(FPS, CPS, PING, KEYSTROKES);

	private Modules() {
	}
}
