package io.github.lhj080902.cafeteria.util;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 스크린샷 파일을 그림으로 클립보드에 넣는다 (메신저 등에 바로 붙여 넣을 수 있게). */
public final class ScreenshotClipboard {
	private static volatile Path lastCopied;

	private ScreenshotClipboard() {
	}

	/**
	 * 게임은 자바의 화면 기능(AWT)을 "화면 없음" 상태로 켜 두는데, 그 상태에서는 클립보드에 그림을 넣을 수 없다.
	 * AWT 가 처음 쓰이기 전에(모드를 불러올 때) 이 설정을 풀어 둔다. macOS 는 AWT 와 게임 창이 충돌하므로 건드리지 않는다.
	 */
	public static void prepare() {
		if (!Minecraft.ON_OSX) {
			System.setProperty("java.awt.headless", "false");
		}
	}

	/** 마지막으로 클립보드에 넣은 스크린샷 파일. 아직 없으면 null. */
	public static Path lastCopied() {
		return lastCopied;
	}

	/** 파일을 읽어 클립보드에 넣는다. 파일 읽기와 변환이 오래 걸릴 수 있어 입출력 스레드에서 실행한다. */
	public static CompletableFuture<Void> copy(Path file) {
		return CompletableFuture.runAsync(() -> {
			try {
				copyNow(file);
				lastCopied = file;
			} catch (IOException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		}, Util.ioPool());
	}

	private static void copyNow(Path file) throws IOException {
		if (Minecraft.ON_OSX) {
			throw new IOException("macOS 에서는 아직 지원하지 않습니다");
		}

		BufferedImage image = ImageIO.read(file.toFile());

		if (image == null) {
			throw new IOException("그림 파일을 읽을 수 없습니다");
		}

		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageSelection(opaque(image)), null);
		} catch (HeadlessException | java.awt.AWTError | IllegalStateException e) {
			// 다른 모드가 AWT 를 먼저 "화면 없음" 상태로 써 버린 경우 등. Windows 에서는 시스템 도구로 한 번 더 시도한다.
			if (!isWindows() || !copyWithPowerShell(file)) {
				throw new IOException("클립보드를 쓸 수 없습니다 (" + e.getClass().getSimpleName() + ")", e);
			}
		}
	}

	/** 투명도 정보가 있는 그림은 프로그램에 따라 검게 붙는 일이 있어서, 불투명한 RGB 그림으로 바꿔 넣는다. */
	private static BufferedImage opaque(BufferedImage image) {
		BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return result;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** Windows PowerShell 로 그림을 클립보드에 넣는다. 경로는 환경 변수로 넘겨서 따옴표 문제를 피한다. */
	private static boolean copyWithPowerShell(Path file) {
		try {
			ProcessBuilder builder = new ProcessBuilder(
				"powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command",
				"Add-Type -AssemblyName System.Windows.Forms,System.Drawing; "
					+ "$image = [System.Drawing.Image]::FromFile($env:CAFETERIA_SCREENSHOT); "
					+ "[System.Windows.Forms.Clipboard]::SetImage($image); $image.Dispose()");
			builder.environment().put("CAFETERIA_SCREENSHOT", file.toAbsolutePath().toString());
			builder.redirectErrorStream(true);
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			Process process = builder.start();

			if (!process.waitFor(15, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}

			return process.exitValue() == 0;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** 클립보드에 그림 한 장을 올려 두는 전달 객체. */
	private record ImageSelection(Image image) implements Transferable {
		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] {DataFlavor.imageFlavor};
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.imageFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}

			return image;
		}
	}
}
