package io.github.lhj080902.cafeteria.mixin;

import io.github.lhj080902.cafeteria.Modules;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ShulkerBoxBlock.class)
public abstract class ShulkerBoxBlockMixin {
	/** 컨테이너 미리보기: 격자가 내용물을 보여 주는 동안에는 같은 내용을 적은 글자 줄("다이아몬드 x3" 등)을 넣지 않는다. */
	@Inject(method = "appendHoverText", at = @At("HEAD"), cancellable = true, require = 0)
	private void cafeteria$hideContentLines(ItemStack stack, Item.TooltipContext context, List<Component> lines, TooltipFlag flag, CallbackInfo ci) {
		if (Modules.CONTAINER_PREVIEW.replacesText(stack)) {
			ci.cancel();
		}
	}
}
