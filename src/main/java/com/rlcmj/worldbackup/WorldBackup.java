package com.rlcmj.worldbackup;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;

@Mod(
    modid = WorldBackup.MOD_ID,
    name = WorldBackup.NAME,
    version = WorldBackup.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    clientSideOnly = true,
    guiFactory = "com.rlcmj.worldbackup.client.WorldBackupGuiFactory"
)
public final class WorldBackup {
    public static final String MOD_ID = "worldbackup";
    public static final String NAME = "World Backup";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MOD_ID.equals(event.getModID())) {
            ConfigManager.sync(MOD_ID, Config.Type.INSTANCE);
        }
    }
}
