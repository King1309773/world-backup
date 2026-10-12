package com.rlcmj.worldbackup.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentTranslation;

public final class ClientMessages {
    private ClientMessages() { }

    public static void send(String key, Object... arguments) {
        Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.addScheduledTask(() -> {
            if (minecraft.player == null) return;
            if ("worldbackup.error.timeout".equals(key)) {
                Object elapsed = arguments.length == 0 ? "0.0" : arguments[0];
                minecraft.player.sendMessage(new TextComponentTranslation(
                    "worldbackup.message.failure", elapsed,
                    new TextComponentTranslation("worldbackup.error.timeout")));
            } else {
                minecraft.player.sendMessage(new TextComponentTranslation(key, arguments));
            }
        });
    }
}
