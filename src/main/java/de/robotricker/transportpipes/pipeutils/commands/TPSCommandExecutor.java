package de.robotricker.transportpipes.pipeutils.commands;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import de.robotricker.transportpipes.PipeThread;
import de.robotricker.transportpipes.TransportPipes;
import de.robotricker.transportpipes.pipes.BlockLoc;
import de.robotricker.transportpipes.pipes.types.Pipe;
import de.robotricker.transportpipes.pipeutils.config.LocConf;
import de.robotricker.transportpipes.pipeutils.hitbox.HitboxListener;
import de.robotricker.transportpipes.pipeutils.hitbox.TimingCloseable;

public class TPSCommandExecutor implements PipesCommandExecutor {

	@Override
	public boolean onCommand(CommandSender cs, String[] args) {
		if (!cs.hasPermission("transportpipes.tps")) {
			return false;
		}
		int tps = TransportPipes.instance.pipeThread.getCalculatedTps();
		ChatColor colour = ChatColor.DARK_GREEN;
		if (tps <= 1) {
			colour = ChatColor.DARK_RED;
		} else if (tps <= 3) {
			colour = ChatColor.RED;
		} else if (tps <= 4) {
			colour = ChatColor.GOLD;
		} else if (tps <= 5) {
			colour = ChatColor.GREEN;
		}

		float lastTickDiff = TransportPipes.instance.pipeThread.getLastTickDiff() / 1000f;

		cs.sendMessage(String.format(LocConf.load(LocConf.COMMANDS_HEADER), TransportPipes.instance.getDescription().getVersion()));
		cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Tick duration: " + colour + (TransportPipes.instance.pipeThread.timeTick / 10000) / 100f + "ms"));
		cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Last Tick: " + lastTickDiff));
		cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Last Action: " + TransportPipes.instance.pipeThread.getLastAction()));
		cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6TPS: " + colour + tps + " &6/ §2" + PipeThread.WANTED_TPS));

		for (World world : Bukkit.getWorlds()) {
			int worldPipes = 0;
			int worldItems = 0;
			Map<BlockLoc, Pipe> pipeMap = TransportPipes.instance.getPipeMap(world);
			if (pipeMap != null) {
				synchronized (pipeMap) {
					for (Pipe pipe : pipeMap.values()) {
						worldPipes++;
						worldItems += pipe.pipeItems.size() + pipe.tempPipeItems.size() + pipe.tempPipeItemsWithSpawn.size();
					}
				}
				cs.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6" + world.getName() + ": &e" + worldPipes + " &6" + "pipes, &e" + worldItems + " &6items"));
			}
		}

//		cs.sendMessage("Timings:");
//		for (String timing : TimingCloseable.timings.keySet()) {
//			long time = TimingCloseable.timings.get(timing);
//			long maxTime = TimingCloseable.timingsRecord.get(timing);
//			String timeS = String.format("%.3f", time / 1000000f);
//			String maxTimeS = String.format("%.3f", maxTime / 1000000f);
//			cs.sendMessage(timing + ": §6" + timeS + "§r millis (Max: §6" + maxTimeS + "§r)");
//		}

		cs.sendMessage(LocConf.load(LocConf.COMMANDS_FOOTER));

		return true;

	}

}
