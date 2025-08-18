package com.example.examplemod.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class ExampleModMixin {
	@Inject(method = "<clinit>", at = @At("HEAD"))
	private static void initInject(CallbackInfo info) {
		System.out.println("Hello from the example mod mixin!");
	}
}
