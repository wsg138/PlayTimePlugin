package org.enthusia.playtime.gui.admin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.playtime.PlayTimePlugin;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.gui.PlaytimeGui;
import org.enthusia.playtime.gui.PlaytimeGuiHolder;

import java.util.ArrayList;
import java.util.List;

public final class AdminMainGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final boolean bedrock;
    private final Inventory inventory;

    private static final int SLOT_PLAYERS = 11;
    private static final int SLOT_ACTIVITY = 15;
    private static final int SLOT_CLOSE = 22;

    public AdminMainGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        BedrockSupport bs = plugin.getBedrockSupport();
        this.bedrock = bs != null && bs.isBedrock(viewer);
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 27,
                ChatColor.DARK_AQUA + "Playtime Admin");

        render();
    }

    private void render() {
        if (!bedrock) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        // Online players
        ItemStack players = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta pm = players.getItemMeta();
        pm.setDisplayName(ChatColor.AQUA + "Online Players");
        List<String> plore = new ArrayList<>();
        plore.add(ChatColor.GRAY + "View current players, status, and session length.");
        plore.add(ChatColor.DARK_GRAY + "Filter by Active / Idle / AFK / Suspicious.");
        pm.setLore(plore);
        pm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        players.setItemMeta(pm);
        inventory.setItem(SLOT_PLAYERS, players);

        // Server activity
        ItemStack activity = new ItemStack(Material.CLOCK);
        ItemMeta am = activity.getItemMeta();
        am.setDisplayName(ChatColor.GOLD + "Server Activity");
        List<String> alore = new ArrayList<>();
        alore.add(ChatColor.GRAY + "Totals over Today / 7d / 30d / All.");
        alore.add(ChatColor.GRAY + "See active, AFK, and total playtime.");
        alore.add(ChatColor.GRAY + "Plus unique players & joins.");
        am.setLore(alore);
        am.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        activity.setItemMeta(am);
        inventory.setItem(SLOT_ACTIVITY, activity);

        // Close
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(cm);
        inventory.setItem(SLOT_CLOSE, close);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public Player getViewer() {
        return viewer;
    }

    @Override
    public void open() {
        viewer.openInventory(inventory);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == SLOT_PLAYERS) {
            new AdminPlayersGui(plugin, viewer).open();
            return;
        }
        if (slot == SLOT_ACTIVITY) {
            new AdminServerActivityGui(plugin, viewer).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        // nothing special
    }
}
