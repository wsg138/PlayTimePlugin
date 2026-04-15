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
import org.enthusia.playtime.data.model.PlaytimeSnapshot;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlaytimeMainGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    private static final int SLOT_STATS = 13;
    private static final int SLOT_LEADERBOARD = 21;
    private static final int SLOT_CLOSE = 23;

    public PlaytimeMainGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 27, ChatColor.DARK_AQUA + "Your Playtime");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();

        PlaytimeRuntime runtime = plugin.runtime();
        Optional<PlaytimeSnapshot> optional = runtime == null
                ? Optional.empty()
                : runtime.readService().getLifetime(viewer.getUniqueId());

        ItemStack statsItem = runtime != null ? runtime.headCache().createHead(viewer.getUniqueId()) : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta statsMeta = statsItem.getItemMeta();
        statsMeta.setDisplayName(ChatColor.GOLD + viewer.getName() + ChatColor.YELLOW + "'s playtime");

        List<String> lore = new ArrayList<>();
        if (optional.isEmpty()) {
            lore.add(ChatColor.RED + "No playtime recorded yet.");
        } else {
            PlaytimeSnapshot snapshot = optional.get();
            lore.add(ChatColor.GRAY + "Total: " + ChatColor.AQUA + TimeFormats.formatMinutes(snapshot.totalMinutes));
            lore.add(ChatColor.GRAY + "Active: " + ChatColor.GREEN + TimeFormats.formatMinutes(snapshot.activeMinutes));
            lore.add(ChatColor.GRAY + "AFK: " + ChatColor.RED + TimeFormats.formatMinutes(snapshot.afkMinutes));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Use the book below");
        lore.add(ChatColor.YELLOW + "to view leaderboards.");
        statsMeta.setLore(lore);
        statsMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        statsItem.setItemMeta(statsMeta);
        inventory.setItem(SLOT_STATS, statsItem);

        ItemStack leaderboard = new ItemStack(Material.BOOK);
        ItemMeta leaderboardMeta = leaderboard.getItemMeta();
        leaderboardMeta.setDisplayName(ChatColor.AQUA + "Leaderboards");
        leaderboardMeta.setLore(List.of(
                ChatColor.GRAY + "View top players by playtime.",
                "",
                ChatColor.YELLOW + "Left-click: " + ChatColor.WHITE + "Total, all time"
        ));
        leaderboardMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        leaderboard.setItemMeta(leaderboardMeta);
        inventory.setItem(SLOT_LEADERBOARD, leaderboard);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(closeMeta);
        inventory.setItem(SLOT_CLOSE, close);
    }

    private void fillBackground() {
        PlaytimeRuntime runtime = plugin.runtime();
        boolean bedrock = runtime != null && plugin.getBedrockSupport() != null && plugin.getBedrockSupport().isBedrock(viewer);
        if (bedrock) {
            return;
        }

        Material fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        if (runtime != null) {
            try {
                fillerMaterial = Material.valueOf(runtime.config().gui().fillerMaterial().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            }
        }

        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(" ");
        filler.setItemMeta(fillerMeta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
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
        if (slot == SLOT_LEADERBOARD) {
            new LeaderboardGui(plugin, viewer, "TOTAL", "ALL", 1).open();
        } else if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        }
    }

    @Override
    public void handleClose(InventoryCloseEvent event) {
    }
}
