package org.enthusia.playtime.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.enthusia.playtime.PlayTimePlugin;

import java.util.ArrayList;
import java.util.List;

public final class AdminPlaytimeCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.GOLD + "[Playtime] " + ChatColor.YELLOW;

    private final PlayTimePlugin plugin;

    public AdminPlaytimeCommand(PlayTimePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (!sender.hasPermission("playtime.admin.base")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You don't have permission to use admin playtime tools.");
            return true;
        }

        // Placeholder until we build the actual Admin GUI
        sender.sendMessage(PREFIX + ChatColor.RED + "Admin GUI is not implemented yet.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String alias,
                                      String[] args) {
        return new ArrayList<>();
    }
}
