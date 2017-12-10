package de.robotricker.transportpipes;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import de.robotricker.transportpipes.api.PipeAPI;
import de.robotricker.transportpipes.api.TransportPipesContainer;
import de.robotricker.transportpipes.pipeitems.PipeItem;
import de.robotricker.transportpipes.pipes.BlockLoc;
import de.robotricker.transportpipes.pipes.Duct;
import de.robotricker.transportpipes.pipes.DuctType;
import de.robotricker.transportpipes.pipes.extractionpipe.ExtractionPipeInv;
import de.robotricker.transportpipes.pipes.goldenpipe.GoldenPipeInv;
import de.robotricker.transportpipes.pipes.types.Pipe;
import de.robotricker.transportpipes.pipeutils.ContainerBlockUtils;
import de.robotricker.transportpipes.pipeutils.CraftUtils;
import de.robotricker.transportpipes.pipeutils.LWCAPIUtils;
import de.robotricker.transportpipes.pipeutils.LogisticsAPIUtils;
import de.robotricker.transportpipes.pipeutils.SkyblockAPIUtils;
import de.robotricker.transportpipes.pipeutils.commands.CreativeCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.DeletePipesCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.ReloadConfigCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.ReloadPipesCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.SaveCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.SettingsCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.TPSCommandExecutor;
import de.robotricker.transportpipes.pipeutils.commands.UpdateCommandExecutor;
import de.robotricker.transportpipes.pipeutils.config.GeneralConf;
import de.robotricker.transportpipes.pipeutils.config.LocConf;
import de.robotricker.transportpipes.pipeutils.config.RecipesConf;
import de.robotricker.transportpipes.pipeutils.hitbox.HitboxListener;
import de.robotricker.transportpipes.protocol.ArmorStandProtocol;
import de.robotricker.transportpipes.protocol.PipePacketManager;
import de.robotricker.transportpipes.rendersystem.RenderSystem;
import de.robotricker.transportpipes.rendersystem.modelled.utils.ModelledPipeRenderSystem;
import de.robotricker.transportpipes.rendersystem.vanilla.utils.VanillaPipeRenderSystem;
import de.robotricker.transportpipes.saving.SavingManager;
import de.robotricker.transportpipes.settings.SettingsInv;
import de.robotricker.transportpipes.settings.SettingsUtils;
import de.robotricker.transportpipes.update.UpdateManager;
import io.sentry.Sentry;
import io.sentry.event.UserBuilder;

public class TransportPipes extends JavaPlugin {

	public static TransportPipes instance;

	// x << 34 | y << 26 | z
	private Map<World, Map<BlockLoc, Duct>> registeredDucts;
	private Map<World, Map<BlockLoc, TransportPipesContainer>> registeredContainers;

	private UpdateManager updateManager;
	public ContainerBlockUtils containerBlockUtils;
	public SavingManager savingManager;
	public SettingsUtils settingsUtils;
	public PipeThread pipeThread;
	public ArmorStandProtocol armorStandProtocol;
	public PipePacketManager pipePacketManager;

	// configs
	public LocConf locConf;
	public GeneralConf generalConf;
	public RecipesConf recipesConf;

	@Override
	public void onEnable() {
		instance = this;

		initSentryOnCurrentThread();

		// Prepare collections
		registeredDucts = Collections.synchronizedMap(new HashMap<World, Map<BlockLoc, Duct>>());
		registeredContainers = Collections.synchronizedMap(new HashMap<World, Map<BlockLoc, TransportPipesContainer>>());

		// Prepare managers
		armorStandProtocol = new ArmorStandProtocol();
		pipePacketManager = new PipePacketManager();
		settingsUtils = new SettingsUtils();

		locConf = new LocConf();
		generalConf = new GeneralConf();
		recipesConf = new RecipesConf();

		DuctType.PIPE.addRenderSystem(new VanillaPipeRenderSystem(armorStandProtocol));
		DuctType.PIPE.addRenderSystem(new ModelledPipeRenderSystem(armorStandProtocol));

		pipeThread = new PipeThread();
		pipeThread.setDaemon(true);
		pipeThread.setPriority(Thread.MIN_PRIORITY);

		// register command executors
		final SettingsCommandExecutor settingsCmdExec = new SettingsCommandExecutor();
		final TPSCommandExecutor tpsCmdExec = new TPSCommandExecutor();
		final CreativeCommandExecutor creativeCmdExec = new CreativeCommandExecutor();
		final ReloadConfigCommandExecutor reloadConfigCmdExec = new ReloadConfigCommandExecutor();
		final ReloadPipesCommandExecutor reloadPipesCmdExec = new ReloadPipesCommandExecutor();
		final UpdateCommandExecutor updateCmdExec = new UpdateCommandExecutor();
		final SaveCommandExecutor saveCmdExec = new SaveCommandExecutor();
		final DeletePipesCommandExecutor deletePipesCmdExec = new DeletePipesCommandExecutor();

		getCommand("transportpipes").setExecutor(new CommandExecutor() {

			@Override
			public boolean onCommand(CommandSender cs, Command cmd, String label, String[] args) {

				boolean noPerm = false;

				if (args.length >= 1 && args[0].equalsIgnoreCase("tps")) {
					if (!tpsCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 1 && args[0].equalsIgnoreCase("settings")) {
					if (!settingsCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 1 && args[0].equalsIgnoreCase("creative")) {
					if (!creativeCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 1 && args[0].equalsIgnoreCase("update")) {
					if (!updateCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("config")) {
					if (!reloadConfigCmdExec.onCommand(cs, Arrays.copyOfRange(args, 2, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 2 && args[0].equalsIgnoreCase("reload") && args[1].equalsIgnoreCase("pipes")) {
					if (!reloadPipesCmdExec.onCommand(cs, Arrays.copyOfRange(args, 2, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 1 && args[0].equalsIgnoreCase("save")) {
					if (!saveCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else if (args.length >= 2 && args[0].equalsIgnoreCase("delete")) {
					if (!deletePipesCmdExec.onCommand(cs, Arrays.copyOfRange(args, 1, args.length))) {
						noPerm = true;
					}
				} else {
					cs.sendMessage(ChatColor.translateAlternateColorCodes('&', String.format(LocConf.load(LocConf.COMMANDS_HEADER), TransportPipes.instance.getDescription().getVersion())));
					cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes settings &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_SETTINGS)));
					if (cs.hasPermission("transportpipes.tps"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes tps &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_TPS)));
					if (cs.hasPermission("transportpipes.creative"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes creative &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_CREATIVE)));
					if (cs.hasPermission("transportpipes.reload"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes reload <config|pipes> &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_RELOAD)));
					if (cs.hasPermission("transportpipes.update"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes update &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_UPDATE)));
					if (cs.hasPermission("transportpipes.save"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes save &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_SAVE)));
					if (cs.hasPermission("transportpipes.delete"))
						cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6/tpipes delete <Radius> &7- " + LocConf.load(LocConf.COMMANDS_DESCRIPTION_DELETE)));
					cs.sendMessage(ChatColor.translateAlternateColorCodes('&', LocConf.load(LocConf.COMMANDS_FOOTER)));
					return true;
				}

				if (noPerm) {
					cs.sendMessage(LocConf.load(LocConf.COMMANDS_NOPERM));
				}

				return true;
			}
		});

		updateManager = new UpdateManager(this);

		// register listeners
		Bukkit.getPluginManager().registerEvents(new CraftUtils(), this);
		Bukkit.getPluginManager().registerEvents(new GoldenPipeInv(), this);
		Bukkit.getPluginManager().registerEvents(new ExtractionPipeInv(), this);
		Bukkit.getPluginManager().registerEvents(savingManager = new SavingManager(), this);
		Bukkit.getPluginManager().registerEvents(containerBlockUtils = new ContainerBlockUtils(), this);
		Bukkit.getPluginManager().registerEvents(new HitboxListener(), this);
		Bukkit.getPluginManager().registerEvents(new SettingsInv(), this);
		Bukkit.getPluginManager().registerEvents(pipePacketManager, this);
		Bukkit.getPluginManager().registerEvents(updateManager, this);
		for (RenderSystem rs : DuctType.PIPE.getRenderSystems()) {
			Bukkit.getPluginManager().registerEvents(rs, this);
			if (rs instanceof ModelledPipeRenderSystem && Bukkit.getPluginManager().isPluginEnabled("AuthMe")) {
				Bukkit.getPluginManager().registerEvents(((ModelledPipeRenderSystem) rs).new AuthMeLoginListener(), this);
			}
		}
		if (Bukkit.getPluginManager().isPluginEnabled("LogisticsApi")) {
			TransportPipes.instance.getLogger().info("LogisticsAPI found ... registering listener and ItemContainers");
			// register listener
			Bukkit.getPluginManager().registerEvents(new LogisticsAPIUtils(), this);
			// register already registered ItemContainers
			Map<Location, com.logisticscraft.logisticsapi.item.ItemContainer> containers = com.logisticscraft.logisticsapi.item.ItemManager.getContainers();
			for (Location key : containers.keySet()) {
				TransportPipesContainer tpc = LogisticsAPIUtils.wrapLogisticsAPIItemContainer(containers.get(key));
				PipeAPI.registerTransportPipesContainer(key.getBlock().getLocation(), tpc);
			}
		}
		if (Bukkit.getPluginManager().isPluginEnabled("AcidIsland")) {
			Bukkit.getPluginManager().registerEvents(new SkyblockAPIUtils(), this);
		}
		// if (Bukkit.getPluginManager().isPluginEnabled("LWC")) {
		// com.griefcraft.lwc.LWC.getInstance().getModuleLoader().registerModule(this,
		// new LWCAPIUtils());
		// }

		for (World world : Bukkit.getWorlds()) {
			for (Chunk loadedChunk : world.getLoadedChunks()) {
				containerBlockUtils.handleChunkLoadSync(loadedChunk);
			}
			savingManager.loadPipesSync(world);
		}

		CraftUtils.initRecipes();

		Bukkit.getScheduler().runTask(TransportPipes.instance, new Runnable() {

			@Override
			public void run() {
				pipeThread.setRunning(true);
				pipeThread.start();
			}
		});

	}

	public UpdateManager getUpdateManager() {
		return updateManager;
	}

	public List<RenderSystem> getPipeRenderSystems() {
		return renderSystems;
	}

	public String getFormattedWrenchName() {
		return "§c" + LocConf.load(LocConf.PIPES_WRENCH);
	}

	@Override
	public void onDisable() {
		pipeThread.setRunning(false);
		try {
			pipeThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		savingManager.savePipesSync(true);

		// despawn all pipes and items
		Map<World, Map<BlockLoc, Duct>> fullDuctMap = getFullDuctMap();
		synchronized (fullDuctMap) {
			for (Map<BlockLoc, Duct> ductMap : fullDuctMap.values()) {
				for (Duct duct : ductMap.values()) {
					if (duct instanceof Pipe) {
						Pipe pipe = (Pipe) duct;
						pipePacketManager.destroyPipe(pipe);
						Collection<PipeItem> allItems = new ArrayList<>();
						synchronized (pipe.pipeItems) {
							allItems.addAll(pipe.pipeItems.keySet());
						}
						synchronized (pipe.tempPipeItems) {
							allItems.addAll(pipe.tempPipeItems.keySet());
						}
						synchronized (pipe.tempPipeItemsWithSpawn) {
							allItems.addAll(pipe.tempPipeItemsWithSpawn.keySet());
						}
						for (PipeItem pi : allItems) {
							pipePacketManager.destroyPipeItem(pi);
						}
					}
				}
			}
		}

	}

	public Map<BlockLoc, Pipe> getPipeMap(World world) {
		Map<BlockLoc, Duct> ductMap = getDuctMap(world);
		Map<BlockLoc, Pipe> pipeMap = new TreeMap<>();
		synchronized (ductMap) {
			Iterator<BlockLoc> it = ductMap.keySet().iterator();
			while (it.hasNext()) {
				BlockLoc bl = it.next();
				if (ductMap.get(bl) instanceof Pipe) {
					pipeMap.put(bl, (Pipe) ductMap.get(bl));
				}
			}
		}
		return pipeMap;
	}

	public Map<BlockLoc, Duct> getDuctMap(World world) {
		if (registeredDucts.containsKey(world)) {
			return registeredDucts.get(world);
		}
		return null;
	}

	public Map<World, Map<BlockLoc, Duct>> getFullDuctMap() {
		return registeredDucts;
	}

	public Map<World, Map<BlockLoc, Pipe>> getFullPipeMap() {
		Map<World, Map<BlockLoc, Pipe>> fullPipeMap = new HashMap<>();
		synchronized (registeredDucts) {
			Iterator<World> it1 = registeredDucts.keySet().iterator();
			while (it1.hasNext()) {
				World world = it1.next();
				Map<BlockLoc, Duct> ductMap = registeredDucts.get(world);
				Map<BlockLoc, Pipe> pipeMap = new TreeMap<>();
				Iterator<BlockLoc> it2 = ductMap.keySet().iterator();
				while (it2.hasNext()) {
					BlockLoc bl = it2.next();
					if (ductMap.get(bl) instanceof Pipe) {
						pipeMap.put(bl, (Pipe) ductMap.get(bl));
					}
				}
				fullPipeMap.put(world, pipeMap);
			}
		}
		return fullPipeMap;
	}

	public Map<BlockLoc, TransportPipesContainer> getContainerMap(World world) {
		if (registeredContainers.containsKey(world)) {
			return registeredContainers.get(world);
		}
		return null;
	}

	public Map<World, Map<BlockLoc, TransportPipesContainer>> getFullContainerMap() {
		return registeredContainers;
	}

	public static void initSentryOnCurrentThread() {
		Sentry.init("https://2eb0fc30f86a4871a85755ecdde11679:26f44195e9ef47f38e99051f7d15594f@sentry.io/252970");
		Sentry.getContext().setUser(new UserBuilder().setUsername("RoboTricker").build());
		Sentry.getContext().addTag("thread", Thread.currentThread().getName());
		Sentry.getContext().addTag("version", TransportPipes.instance.getDescription().getVersion());

		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {

			@Override
			public void uncaughtException(Thread t, Throwable e) {
				Sentry.capture(e);
			}
		});
	}

}
