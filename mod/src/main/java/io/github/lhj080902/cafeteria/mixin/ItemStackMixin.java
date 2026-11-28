package io.github.lhj080902.cafeteria.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	/**
	 * 컨테이너 미리보기: 내용물이 든 컨테이너 아이템의 툴팁에 내용물 격자를 넣는다.
	 * 이미 툴팁 그림이 있는 아이템(꾸러미, 다른 모드가 그림을 준 아이템)은 그대로 둔다.
	 */
	@ModifyReturnValue(method = "getTooltipImage", at = @At("RETURN"), require = 0)
	private Optional<TooltipComponent> cafeteria$previewContainer(Optional<TooltipComponent> original) {
		if (original.isPresent()) {
			return original;
		}

		return Modules.CONTAINER_PREVIEW.preview((ItemStack) (Object) this).map(TooltipComponent.class::cast);
	}
}
