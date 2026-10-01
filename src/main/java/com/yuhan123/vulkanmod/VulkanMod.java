package com.yuhan123.vulkanmod;

import com.yuhan123.vulkanmod.config.VulkanModConfig;
import com.yuhan123.vulkanmod.proxy.IProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION)
public class VulkanMod {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SidedProxy(modId = Reference.MOD_ID, clientSide = "com.yuhan123.vulkanmod.proxy.ClientProxy", serverSide = "com.yuhan123.vulkanmod.proxy.CommonProxy")
    public static IProxy proxy;
    /**
     * <a href="https://cleanroommc.com/wiki/forge-mod-development/event#overview">
     *     Take a look at how many FMLStateEvents you can listen to via the @Mod.EventHandler annotation here
     * </a>
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Switches now come from config/vulkanmod.properties. Load it before any
        // other class reads one, so the static flags pick up the file values.
        VulkanModConfig.load(event.getModConfigurationDirectory());
        LOGGER.info("{} {} starting; switches from {} (profiling={})",
                Reference.MOD_NAME, Reference.VERSION,
                VulkanModConfig.file(), Boolean.valueOf(VulkanModConfig.profiling()));
    }

}
