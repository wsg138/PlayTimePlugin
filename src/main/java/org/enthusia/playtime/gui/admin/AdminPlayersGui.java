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
import org.enthusia.playtime.activity.ActivityState;
import org.enthusia.playtime.gui.PlaytimeGui;
import org.enthusia.playtime.gui.PlaytimeGuiHolder;
import org.enthusia.playtime.service.PlaytimeRuntime;
import org.enthusia.playtime.util.TimeFormats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AdminPlayersGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final Inventory inventory;

    private Filter filter = Filter.ALL;
    private int page = 1;

    private enum Filter {
        ALL, ACTIVE, IDLE, AFK, SUSPICIOUS
    }

    private static final int SLOT_FILTER_ALL = 1;
    private static final int SLOT_FILTER_ACTIVE = 2;
    private static final int SLOT_FILTER_IDLE = 3;
    private static final int SLOT_FILTER_AFK = 4;
    private static final int SLOT_FILTER_SUS = 5;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_NEXT = 53;

    public AdminPlayersGui(PlayTimePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 54, ChatColor.DARK_AQUA + "Admin - Online Players");
        render();
    }

    private void render() {
        inventory.clear();
        fillBackground();

        inventory.setItem(SLOT_FILTER_ALL, filterItem(Filter.ALL));
        inventory.setItem(SLOT_FILTER_ACTIVE, filterItem(Filter.ACTIVE));
        inventory.setItem(SLOT_FILTER_IDLE, filterItem(Filter.IDLE));
        inventory.setItem(SLOT_FILTER_AFK, filterItem(Filter.AFK));
        inventory.setItem(SLOT_FILTER_SUS, filterItem(Filter.SUSPICIOUS));

        PlaytimeRuntime runtime = plugin.runtime();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        long nowMillis = System.currentTimeMillis();
        List<Player> filtered = new ArrayList<>();
        for (Player player : players) {
            if (runtime != null && matchesFilter(runtime.activityTracker().getState(player.getUniqueId(), nowMillis))) {
                filtered.add(player);
            }
        }

        List<Integer> entrySlots = buildEntrySlots();
        int pageSize = entrySlots.size();
        int from = (page - 1) * pageSize;
        if (from >= filtered.size()) {
            page = 1;
            from = 0;
        }
        int to = Math.min(filtered.size(), from + pageSize);

        int slotIndex = 0;
        for (int index = from; index < to; index++) {
            inventory.setItem(entrySlots.get(slotIndex++), entryItem(filtered.get(index), nowMillis));
        }

        inventory.setItem(SLOT_PREV, buildItem(Material.ARROW, ChatColor.YELLOW + "Previous page (" + Math.max(page - 1, 1) + ")", List.of()));
        inventory.setItem(SLOT_NEXT, buildItem(Material.ARROW, ChatColor.YELLOW + "Next page (" + (page + 1) + ")", List.of()));
        inventory.setItem(SLOT_BACK, buildItem(Material.OAK_DOOR, ChatColor.AQUA + "Back to admin menu", List.of()));
        inventory.setItem(SLOT_CLOSE, buildItem(Material.BARRIER, ChatColor.RED + "Close", List.of()));
    }

    private void fillBackground() {
        boolean bedrock = plugin.runtime() != null && plugin.getBedrockSupport() != null && plugin.getBedrockSupport().isBedrock(viewer);
        if (bedrock) {
            return;
        }
        Material filler = Material.GRAY_STAINED_GLASS_PANE;
        if (plugin.runtime() != null) {
            try {
                filler = Material.valueOf(plugin.runtime().config().gui().fillerMaterial().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                filler = Material.GRAY_STAINED_GLASS_PANE;
            }
        }
        ItemStack fillerItem = new ItemStack(filler);
        ItemMeta fillerMeta = fillerItem.getItemMeta();
        fillerMeta.setDisplayName(" ");
        fillerItem.setItemMeta(fillerMeta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, fillerItem);
        }
    }

    private ItemStack filterItem(Filter filter) {
        Material material = switch (filter) {
            case ACTIVE -> Material.LIME_DYE;
            case IDLE -> Material.YELLOW_DYE;
            case AFK -> Material.RED_DYE;
            case SUSPICIOUS -> Material.MAGENTA_DYE;
            default -> Material.BOOK;
        };
        ChatColor color = switch (filter) {
            case ACTIVE -> ChatColor.GREEN;
            case IDLE -> ChatColor.YELLOW;
            case AFK -> ChatColor.RED;
            case SUSPICIOUS -> ChatColor.LIGHT_PURPLE;
            default -> ChatColor.AQUA;
        };

        boolean selected = this.filter == filter;
        return buildItem(material, (selected ? ChatColor.BOLD.toString() : "") + color + niceFilter(filter),
                List.of(selected ? ChatColor.GREEN + "Selected filter" : ChatColor.YELLOW + "Click to filter by " + niceFilter(filter) + "."));
    }

    private ItemStack entryItem(Player target, long nowMillis) {
        PlaytimeRuntime runtime = plugin.runtime();
        ActivityState state = runtime == null ? ActivityState.ACTIVE : runtime.activityTracker().getState(target.getUniqueId(), nowMillis);
        long sessionMillis = runtime == null ? 0L : runtime.sessionManager().getCurrentSessionMillis(target.getUniqueId(), nowMillis);

        ItemStack head = runtime != null ? runtime.headCache().createHead(target.getUniqueId()) : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + target.getName());
        meta.setLore(List.of(
                ChatColor.GRAY + "Status: " + colorForState(state) + state.name(),
                ChatColor.GRAY + "Session: " + ChatColor.YELLOW + TimeFormats.formatDurationMillis(sessionMillis),
                ChatColor.DARK_GRAY + "UUID: " + target.getUniqueId().toString().substring(0, 8) + "..."
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        head.setItemMeta(meta);
        return head;
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

    private List<Integer> buildEntrySlots() {
        List<Integer> slots = new ArrayList<>();
        for (int row = 2; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private boolean matchesFilter(ActivityState state) {
        return switch (filter) {
            case ALL -> true;
            case ACTIVE -> state == ActivityState.ACTIVE;
            case IDLE -> state == ActivityState.IDLE;
            case AFK -> state == ActivityState.AFK;
            case SUSPICIOUS -> state == ActivityState.SUSPICIOUS;
        };
    }

    private String niceFilter(Filter filter) {
        return switch (filter) {
            case ACTIVE -> "Active";
            case IDLE -> "Idle";
            case AFK -> "AFK";
            case SUSPICIOUS -> "Suspicious";
            default -> "All";
        };
    }

    private ChatColor colorForState(ActivityState state) {
        return switch (state) {
            case ACTIVE -> ChatColor.GREEN;
            case IDLE -> ChatColor.YELLOW;
            case AFK -> ChatColor.RED;
            case SUSPICIOUS -> ChatColor.LIGHT_PURPLE;
        };
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
        if (slot == SLOT_FILTER_ALL) {
            filter = Filter.ALL;
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_FILTER_ACTIVE) {
            filter = Filter.ACTIVE;
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_FILTER_IDLE) {
            filter = Filter.IDLE;
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_FILTER_AFK) {
            filter = Filter.AFK;
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_FILTER_SUS) {
            filter = Filter.SUSPICIOUS;
            page = 1;
            render();
            return;
        }
        if (slot == SLOT_PREV) {
            if (page > 1) {
                page--;
            }
            render();
            return;
        }
        if (slot == SLOT_NEXT) {
            page++;
            render();
            return;
        }
        if (slot == SLOT_BACK) {
            new AdminMainGui(plugin, viewer).open();
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
