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
import org.enthusia.playtime.gui.PlaytimeGui;
import org.enthusia.playtime.gui.PlaytimeGuiHolder;

import java.util.List;
import java.util.Locale;

public final class AdminMainGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    private static final int SLOT_PLAYERS = 11;
    private static final int SLOT_ACTIVITY = 15;
    private static final int SLOT_CLOSE = 22;

    public AdminMainGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 27, ChatColor.DARK_AQUA + "Playtime Admin");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();
        inventory.setItem(SLOT_PLAYERS, buildItem(Material.PLAYER_HEAD, ChatColor.AQUA + "Online Players",
                List.of(
                        ChatColor.GRAY + "View online players, status,",
                        ChatColor.GRAY + "suspicious streaks, and session length."
                )));
        inventory.setItem(SLOT_ACTIVITY, buildItem(Material.CLOCK, ChatColor.GOLD + "Server Activity",
                List.of(
                        ChatColor.GRAY + "Totals over Today / 7d / 30d / All.",
                        ChatColor.GRAY + "See playtime, joins, and player mix."
                )));
        inventory.setItem(SLOT_CLOSE, buildItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
    }

    private void fillBackground() {
        boolean bedrock = plugin.runtime() != null && plugin.getBedrockSupport() != null && plugin.getBedrockSupport().isBedrock(viewer);
        if (bedrock) {
            return;
        }
        Material fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        if (plugin.runtime() != null) {
            try {
                fillerMaterial = Material.valueOf(plugin.runtime().config().gui().fillerMaterial().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            }
        }
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(" ");
        filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack buildItem(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(title);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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
    }
}
