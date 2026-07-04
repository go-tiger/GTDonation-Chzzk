package dev.gotiger.gTDonationChzzk.command;

import dev.gotiger.gTDonationChzzk.GTDonationChzzk;
import dev.gotiger.gTDonationChzzk.listener.PlayerListener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class ChzzkCommand implements CommandExecutor, TabCompleter {

    private final GTDonationChzzk plugin;
    private final PlayerListener playerListener;

    public ChzzkCommand(GTDonationChzzk plugin, PlayerListener playerListener) {
        this.plugin = plugin;
        this.playerListener = playerListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있는 명령어입니다.");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("unlink")) {
            player.sendMessage("§e사용법: /chzzk unlink");
            return true;
        }

        if (!playerListener.isLinked(player.getUniqueId())) {
            player.sendMessage("§c연동된 치지직 계정이 없습니다.");
            return true;
        }

        playerListener.disconnectSession(player.getUniqueId());
        playerListener.unregister(player.getUniqueId());
        player.sendMessage("§a치지직 계정 연동이 해제되었습니다.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("unlink");
        }
        return Collections.emptyList();
    }
}
