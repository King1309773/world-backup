package com.rlcmj.worldbackup;

import com.rlcmj.worldbackup.runtime.BackupCoordinator;
import com.rlcmj.worldbackup.runtime.ClientEventHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    public static final String VERSION = "1.0.1";
    public static final Logger LOGGER = LogManager.getLogger(NAME);
    private final BackupCoordinator backupCoordinator = new BackupCoordinator();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new ClientEventHandler(backupCoordinator));
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        net.minecraft.server.MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server != null && !server.isDedicatedServer()) {
            backupCoordinator.onServerStopping(server);
        }
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MOD_ID.equals(event.getModID())) {
            ConfigManager.sync(MOD_ID, Config.Type.INSTANCE);
        }
    }
}
