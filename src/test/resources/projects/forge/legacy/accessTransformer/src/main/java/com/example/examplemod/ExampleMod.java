package com.example.examplemod;

import net.minecraftforge.fml.common.Mod;
import net.minecraft.client.Minecraft;

@Mod(modid = "examplemod")
public class ExampleMod {

    public ExampleMod() {
		Minecraft.LOGGER.info("Hello World!");
    }
}
