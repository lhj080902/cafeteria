package io.github.lhj080902.cafeteria.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

public class PingHud extends TextHudElement {
	public PingHud(double anchorX, double anchorY) {
		super("ping", true, anchorX, anchorY);
	}

	/** 서버가 탭 목록으로 알려 주는 지연 시간(ms). 싱글플레이에서는 0이다. */
	public static int latency(Minecraft minecraft) {
		ClientPacketListener connection = minecraft.getConnection();

		if (connection == null || minecraft.player == null) {
			return 0;
		}

		PlayerInfo info = connection.getPlayerInfo(minecraft.player.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}

	@Override
	protected String text(Minecraft minecraft) {
		return latency(minecraft) + " ms";
	}

	@Override
	protected String widestText() {
		return "000 ms";
	}
}
