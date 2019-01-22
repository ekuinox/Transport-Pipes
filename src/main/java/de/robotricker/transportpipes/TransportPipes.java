package de.robotricker.transportpipes;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;
import java.util.stream.Collectors;

import ch.jalu.injector.Injector;
import ch.jalu.injector.InjectorBuilder;
import co.aikar.commands.PaperCommandManager;
import de.robotricker.transportpipes.commands.TPCommand;
import de.robotricker.transportpipes.config.GeneralConf;
import de.robotricker.transportpipes.config.LangConf;
import de.robotricker.transportpipes.duct.DuctRegister;
import de.robotricker.transportpipes.duct.factory.PipeFactory;
import de.robotricker.transportpipes.duct.manager.PipeManager;
import de.robotricker.transportpipes.duct.pipe.Pipe;
import de.robotricker.transportpipes.duct.types.BaseDuctType;
import de.robotricker.transportpipes.inventory.PlayerSettingsInventory;
import de.robotricker.transportpipes.items.PipeItemManager;
import de.robotricker.transportpipes.listener.DuctListener;
import de.robotricker.transportpipes.listener.PlayerListener;
import de.robotricker.transportpipes.listener.TPContainerListener;
import de.robotricker.transportpipes.listener.WorldListener;
import de.robotricker.transportpipes.log.LoggerService;
import de.robotricker.transportpipes.log.SentryService;
import de.robotricker.transportpipes.rendersystems.pipe.modelled.ModelledPipeRenderSystem;
import de.robotricker.transportpipes.rendersystems.pipe.vanilla.VanillaPipeRenderSystem;
import de.robotricker.transportpipes.saving.DiskService;
import de.robotricker.transportpipes.saving.DuctLoader;
import io.sentry.event.Breadcrumb;

public class TransportPipes extends JavaPlugin {

    private Injector injector;

    private SentryService sentry;
    private ThreadService thread;
    private DiskService diskService;

    @Override
    public void onEnable() {

        //Initialize dependency injector
        injector = new InjectorBuilder().addDefaultHandlers("de.robotricker.transportpipes").create();
        injector.register(Logger.class, getLogger());
        injector.register(Plugin.class, this);
        injector.register(JavaPlugin.class, this);
        injector.register(TransportPipes.class, this);

        //Initialize logger
        LoggerService logger = injector.getSingleton(LoggerService.class);

        //Initialize sentry
        sentry = injector.getSingleton(SentryService.class);
        if (!sentry.init("https://84937d8c6bc2435d860021667341c87c@sentry.io/1281889?stacktrace.app.packages=de.robotricker&release=" + getDescription().getVersion())) {
            logger.warning("Unable to initialize sentry!");
        }
        sentry.addTag("thread", Thread.currentThread().getName());
        sentry.injectThread(Thread.currentThread());
        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "enabling plugin");

        //Initialize thread
        thread = injector.getSingleton(ThreadService.class);
        thread.start();

        //Initialize configs
        injector.getSingleton(GeneralConf.class);
        injector.register(LangConf.class, new LangConf(this, injector.getSingleton(GeneralConf.class).getLanguage()));

        //Register pipe
        BaseDuctType<Pipe> baseDuctType = injector.getSingleton(DuctRegister.class).registerBaseDuctType("Pipe", PipeManager.class, PipeFactory.class, PipeItemManager.class);
        baseDuctType.setModelledRenderSystem(injector.newInstance(ModelledPipeRenderSystem.class));
        baseDuctType.setVanillaRenderSystem(injector.newInstance(VanillaPipeRenderSystem.class));

        //Register listeners
        TPContainerListener tpContainerListener = injector.getSingleton(TPContainerListener.class);
        Bukkit.getPluginManager().registerEvents(injector.getSingleton(PlayerListener.class), this);
        Bukkit.getPluginManager().registerEvents(injector.getSingleton(DuctListener.class), this);
        Bukkit.getPluginManager().registerEvents(injector.getSingleton(WorldListener.class), this);
        Bukkit.getPluginManager().registerEvents(tpContainerListener, this);
        Bukkit.getPluginManager().registerEvents(injector.getSingleton(PlayerSettingsInventory.class), this);

        //Register commands
        PaperCommandManager commandManager = new PaperCommandManager(this);
        commandManager.enableUnstableAPI("help");
        commandManager.registerCommand(injector.getSingleton(TPCommand.class));
        commandManager.getCommandCompletions().registerCompletion("baseDuctType", c -> injector.getSingleton(DuctRegister.class).baseDuctTypes().stream().map(BaseDuctType::getName).collect(Collectors.toList()));

        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "enabled plugin");

        diskService = injector.getSingleton(DiskService.class);

        runTaskSync(() -> {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk loadedChunk : world.getLoadedChunks()) {
                    tpContainerListener.handleChunkLoadSync(loadedChunk, true);
                }
                diskService.loadDuctsSync(world);
            }
        });
    }

    @Override
    public void onDisable() {
        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "disabling plugin");
        // Stop tpThread gracefully
        try {
            thread.stopRunning();
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        for (World world : Bukkit.getWorlds()) {
            saveWorld(world);
        }
        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "disabled plugin");
    }

    public void saveWorld(World world) {
        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "saving world " + world.getName());
        diskService.saveDuctsSync(world);
        sentry.breadcrumb(Breadcrumb.Level.INFO, "MAIN", "saved world " + world.getName());
    }

    public void runTaskSync(Runnable task) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, task);
        }
    }

    public void runTaskSyncLater(Runnable task, long delay) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskLater(this, task, delay);
        }
    }

    public void runTaskAsync(Runnable runnable, long delay) {
        thread.getTasks().put(runnable, delay);
    }

    public Injector getInjector() {
        return injector;
    }

    public long convertVersionToLong(String version) {
        long versionLong = 0;
        try {
            if (version.contains("-")) {
                for (String subVersion : version.split("-")) {
                    if (subVersion.startsWith("b")) {
                        int buildNumber = 0;
                        String buildNumberString = subVersion.substring(1);
                        if (!buildNumberString.equalsIgnoreCase("CUSTOM")) {
                            buildNumber = Integer.parseInt(buildNumberString);
                        }
                        versionLong |= buildNumber;
                    } else if (!subVersion.equalsIgnoreCase("SNAPSHOT")) {
                        versionLong |= (long) convertMainVersionStringToInt(subVersion) << 32;
                    }
                }
            } else {
                versionLong = (long) convertMainVersionStringToInt(version) << 32;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return versionLong;
    }

    private int convertMainVersionStringToInt(String mainVersion) {
        int versionInt = 0;
        if (mainVersion.contains(".")) {
            // shift for every version number 1 byte to the left
            int leftShift = (mainVersion.split("\\.").length - 1) * 8;
            for (String subVersion : mainVersion.split("\\.")) {
                byte v = Byte.parseByte(subVersion);
                versionInt |= ((int) v << leftShift);
                leftShift -= 8;
            }
        } else {
            versionInt = Byte.parseByte(mainVersion);
        }
        return versionInt;
    }

}
