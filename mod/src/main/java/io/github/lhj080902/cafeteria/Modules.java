package io.github.lhj080902.cafeteria;

import io.github.lhj080902.cafeteria.hud.ArmorHud;
import io.github.lhj080902.cafeteria.hud.ComboHud;
import io.github.lhj080902.cafeteria.hud.CoordinatesHud;
import io.github.lhj080902.cafeteria.hud.CpsHud;
import io.github.lhj080902.cafeteria.hud.FpsHud;
import io.github.lhj080902.cafeteria.hud.HudElement;
import io.github.lhj080902.cafeteria.hud.KeystrokesHud;
import io.github.lhj080902.cafeteria.hud.PingHud;
import io.github.lhj080902.cafeteria.hud.PotionHud;
import io.github.lhj080902.cafeteria.hud.ReachHud;
import io.github.lhj080902.cafeteria.hud.TitleHud;
import io.github.lhj080902.cafeteria.module.ChatTimeModule;
import io.github.lhj080902.cafeteria.module.FreelookModule;
import io.github.lhj080902.cafeteria.module.FullbrightModule;
import io.github.lhj080902.cafeteria.module.HitboxModule;
import io.github.lhj080902.cafeteria.module.InfiniteChatModule;
import io.github.lhj080902.cafeteria.module.LowFireModule;
import io.github.lhj080902.cafeteria.module.MenuBackgroundModule;
import io.github.lhj080902.cafeteria.module.Module;
import io.github.lhj080902.cafeteria.module.NoTutorialModule;
import io.github.lhj080902.cafeteria.module.ScreenshotModule;
import io.github.lhj080902.cafeteria.module.ZoomModule;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/** 클라이언트에 들어 있는 모든 모듈. 믹스인에서도 바로 쓸 수 있게 정적 필드로 둔다. */
public final class Modules {
	/**
	 * 왼쪽 위에 미니맵을 띄우는 모드(Xaero's Minimap)가 있으면 HUD 기본 위치를 그 아래로 내린다.
	 * 사용자가 위치를 한 번 옮기면 저장된 위치가 우선한다.
	 */
	private static final double LEFT_TOP = FabricLoader.getInstance().isModLoaded("xaerominimap") ? 0.37 : 0.01;
	private static final double LEFT = 0.005;
	private static final double RIGHT = 0.995;
	private static final double ROW = 0.065;

	public static final FreelookModule FREELOOK = new FreelookModule();
	public static final ZoomModule ZOOM = new ZoomModule();
	public static final HitboxModule HITBOX = new HitboxModule();
	public static final FullbrightModule FULLBRIGHT = new FullbrightModule();
	public static final LowFireModule LOW_FIRE = new LowFireModule();
	public static final NoTutorialModule NO_TUTORIAL = new NoTutorialModule();
	public static final MenuBackgroundModule MENU_BACKGROUND = new MenuBackgroundModule();
	public static final ChatTimeModule CHAT_TIME = new ChatTimeModule();
	public static final InfiniteChatModule INFINITE_CHAT = new InfiniteChatModule();
	public static final ScreenshotModule SCREENSHOT = new ScreenshotModule();

	public static final FpsHud FPS = new FpsHud(LEFT, LEFT_TOP);
	public static final CpsHud CPS = new CpsHud(LEFT, LEFT_TOP + ROW);
	public static final PingHud PING = new PingHud(LEFT, LEFT_TOP + ROW * 2);
	public static final CoordinatesHud COORDINATES = new CoordinatesHud(LEFT, LEFT_TOP + ROW * 3);
	public static final PotionHud POTIONS = new PotionHud(LEFT, LEFT_TOP + ROW * 3 + 0.12);
	public static final ArmorHud ARMOR = new ArmorHud(RIGHT, 0.36);
	public static final KeystrokesHud KEYSTROKES = new KeystrokesHud(RIGHT, 0.93);
	public static final ComboHud COMBO = new ComboHud(0.5, 0.62);
	public static final ReachHud REACH = new ReachHud(0.5, 0.68);
	/** 바닐라가 그리는 HUD. 기본 자리는 바닐라가 정하고, 옮기거나 크기를 바꿨을 때만 손을 댄다. */
	public static final TitleHud TITLE = new TitleHud();

	/** 설정 화면의 "기능" 묶음. */
	public static final List<Module> FEATURES = List.of(FREELOOK, ZOOM, HITBOX, FULLBRIGHT, LOW_FIRE, NO_TUTORIAL, MENU_BACKGROUND, CHAT_TIME, INFINITE_CHAT, SCREENSHOT);
	/** 설정 화면의 "HUD" 묶음이자 화면에 그리는 순서. */
	public static final List<HudElement> HUD = List.of(FPS, CPS, PING, COORDINATES, KEYSTROKES, ARMOR, POTIONS, COMBO, REACH, TITLE);
	public static final List<Module> ALL;

	static {
		List<Module> all = new ArrayList<>(FEATURES);
		all.addAll(HUD);
		ALL = List.copyOf(all);
	}

	private Modules() {
	}
}
