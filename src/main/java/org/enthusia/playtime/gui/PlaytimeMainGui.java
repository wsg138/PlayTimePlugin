package org.enthusia.playtime.gui;

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
import org.enthusia.playtime.data.PlaytimeRepository;
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.skin.HeadCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeMainGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final boolean bedrock;
    private final PlaytimeRepository repository;
    private final Inventory inventory;

    private final int SLOT_STATS = 13;
    private final int SLOT_LEADERBOARD = 21;
    private final int SLOT_CLOSE = 23;

    public PlaytimeMainGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.repository = plugin.getRepository();
        BedrockSupport bs = plugin.getBedrockSupport();
        this.bedrock = bs != null && bs.isBedrock(viewer);

        int rows = 3; // 27 slots, simple and centered on both
        int size = rows * 9;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), size,
                ChatColor.DARK_AQUA + "Your Playtime");

        render();
    }

    private void render() {
        inventory.clear();

        // Filler (skip for Bedrock players because stained glass looks off)
        if (!bedrock) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        // Stats head in the middle
        UUID uuid = viewer.getUniqueId();
        Optional<PlaytimeSnapshot> opt = repository.getLifetime(uuid);

        HeadCache cache = plugin.getHeadCache();
        ItemStack statsItem = (cache != null)
                ? cache.createHead(uuid)
                : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = statsItem.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + viewer.getName() + ChatColor.YELLOW + "'s playtime");

        List<String> lore = new ArrayList<>();
        if (opt.isEmpty()) {
            lore.add(ChatColor.RED + "No playtime recorded yet.");
        } else {
            PlaytimeSnapshot snap = opt.get();
            lore.add(ChatColor.GRAY + "Total: " + ChatColor.AQUA + formatMinutes(snap.totalMinutes));
            lore.add(ChatColor.GRAY + "Active: " + ChatColor.GREEN + formatMinutes(snap.activeMinutes));
            lore.add(ChatColor.GRAY + "AFK: " + ChatColor.RED + formatMinutes(snap.afkMinutes));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Use the book below");
        lore.add(ChatColor.YELLOW + "to view leaderboards.");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        statsItem.setItemMeta(meta);

        inventory.setItem(SLOT_STATS, statsItem);

        // Leaderboard button
        ItemStack lb = new ItemStack(Material.BOOK);
        ItemMeta lm = lb.getItemMeta();
        lm.setDisplayName(ChatColor.AQUA + "Leaderboards");
        List<String> lbLore = new ArrayList<>();
        lbLore.add(ChatColor.GRAY + "View top players by playtime.");
        lbLore.add("");
        lbLore.add(ChatColor.YELLOW + "Left-click: " + ChatColor.WHITE + "Total, all time");
        lm.setLore(lbLore);
        lm.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        lb.setItemMeta(lm);
        inventory.setItem(SLOT_LEADERBOARD, lb);

        // Close button
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(cm);
        inventory.setItem(SLOT_CLOSE, close);
    }

    private static String formatMinutes(long minutes) {
        if (minutes <= 0) return "0m";
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours <= 0) {
            return mins + "m";
        }
        if (mins == 0) {
            return hours + "h";
        }
        return hours + "h " + mins + "m";
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
        int slot = event.getRawSlot(); // top inventory slots only

        if (slot == SLOT_LEADERBOARD) {
            // Open leaderboard GUI default: total/all/page1
            new LeaderboardGui(plugin, viewer, "TOTAL", "ALL", 1).open();
        } else if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
        // nothing yet
    }
}
