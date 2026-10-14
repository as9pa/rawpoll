package dev.as9pa.rawpoll;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = RawPoll.MODID, name = RawPoll.NAME, version = RawPoll.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class RawPoll {
    public static final String MODID = "rawpoll";
    public static final String NAME = "rawpoll";
    public static final String VERSION = "1.0.0";

    /** Compile-time kill switch for turning legacy mouse messages off while grabbed. */
    static final boolean NOLEGACY_ENABLED = true;

    static final Logger LOGGER = LogManager.getLogger(NAME);

    private boolean noLegacyWhileGrabbed = true;

    /** Logs a "[rawpoll diag]" line once per second while in game. Off by default. */
    static boolean debugLog;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            Configuration config = new Configuration(event.getSuggestedConfigurationFile());
            config.load();
            noLegacyWhileGrabbed = config.getBoolean("noLegacyWhileGrabbed", Configuration.CATEGORY_GENERAL, true,
                    "Turn off legacy mouse messages while the cursor is grabbed in game (fixes FPS loss at high polling"
                            + " rates). Buttons and wheel then come from raw input.");
            debugLog = config.getBoolean("debugLog", Configuration.CATEGORY_GENERAL, false,
                    "Log frame timing and raw input stats once per second while in game.");
            if (config.hasChanged()) {
                config.save();
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read config; using defaults", t);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (!event.getSide().isClient()) {
            return;
        }
        String os = System.getProperty("os.name", "");
        if (!os.startsWith("Windows")) {
            LOGGER.warn("Raw input is only supported on Windows (os.name={}); using vanilla mouse input", os);
            return;
        }
        try {
            if (!RawInputThread.start()) {
                LOGGER.warn("Raw input thread failed to start; using vanilla mouse input");
                return;
            }
            if (NOLEGACY_ENABLED && noLegacyWhileGrabbed) {
                if (RawPollHelper.initNoLegacy()) {
                    LOGGER.info("Legacy mouse messages will be off while the cursor is grabbed");
                }
            } else {
                LOGGER.info("noLegacyWhileGrabbed is disabled; legacy mouse messages stay on");
            }
            Minecraft.getMinecraft().mouseHelper = new RawPollHelper();
            // RenderTickEvent fires on the FML bus in 1.8.9.
            FMLCommonHandler.instance().bus().register(new RawPollDiag());
            LOGGER.info("Raw input is active: mouse deltas now come from Windows Raw Input");
        } catch (Throwable t) {
            LOGGER.warn("Could not enable raw input; using vanilla mouse input", t);
        }
    }
}
