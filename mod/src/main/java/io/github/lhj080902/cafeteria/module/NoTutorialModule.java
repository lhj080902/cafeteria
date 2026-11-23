package io.github.lhj080902.cafeteria.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;

/** 튜토리얼 끄기. 화면 오른쪽 위에 뜨는 "나무를 캐세요" 같은 바닐라 튜토리얼 안내를 끈다. */
public class NoTutorialModule extends Module {
	public NoTutorialModule() {
		super("no_tutorial", true);
	}

	@Override
	public void tick(Minecraft minecraft) {
		// 튜토리얼 단계는 options.txt 에 저장되므로 한 번 꺼 두면 계속 유지된다.
		if (isEnabled() && minecraft.options.tutorialStep != TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(TutorialSteps.NONE);
		}
	}
}
