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
import org.enthusia.playtime.activity.ActivityTracker;
import org.enthusia.playtime.activity.SessionManager;
import org.enthusia.playtime.bedrock.BedrockSupport;
import org.enthusia.playtime.gui.PlaytimeGui;
import org.enthusia.playtime.gui.PlaytimeGuiHolder;
import org.enthusia.playtime.skin.HeadCache;

import java.util.*;

public final class AdminPlayersGui implements PlaytimeGui {

    private final PlayTimePlugin plugin;
    private final Player viewer;
    private final ActivityTracker tracker;
    private final SessionManager sessionManager;
    private final boolean bedrock;
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
        this.tracker = plugin.getActivityTracker();
        this.sessionManager = plugin.getSessionManager();
        BedrockSupport bs = plugin.getBedrockSupport();
        this.bedrock = bs != null && bs.isBedrock(viewer);
        this.inventory = Bukkit.createInventory(new PlaytimeGuiHolder(this), 54,
                ChatColor.DARK_AQUA + "Admin - Online Players");
        render();
    }

    private void render() {
        inventory.clear();

        if (!bedrock) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fm = filler.getItemMeta();
            fm.setDisplayName(" ");
            filler.setItemMeta(fm);
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        inventory.setItem(SLOT_FILTER_ALL, filterItem(Filter.ALL));
        inventory.setItem(SLOT_FILTER_ACTIVE, filterItem(Filter.ACTIVE));
        inventory.setItem(SLOT_FILTER_IDLE, filterItem(Filter.IDLE));
        inventory.setItem(SLOT_FILTER_AFK, filterItem(Filter.AFK));
        inventory.setItem(SLOT_FILTER_SUS, filterItem(Filter.SUSPICIOUS));

        List<Integer> slots = buildEntrySlots();
        long now = System.currentTimeMillis();

        List<Player> candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        List<Player> filtered = new ArrayList<>();
        for (Player p : candidates) {
            ActivityState st = tracker.getState(p.getUniqueId(), now);
            if (matchesFilter(st)) {
                filtered.add(p);
            }
        }

        int pageSize = slots.size();
        int from = (page - 1) * pageSize;
        int to = Math.min(filtered.size(), from + pageSize);
        if (from >= filtered.size()) {
            page = 1;
            from = 0;
            to = Math.min(filtered.size(), pageSize);
        }

        int idx = 0;
        for (int i = from; i < to; i++) {
            Player target = filtered.get(i);
            int slot = slots.get(idx++);
            inventory.setItem(slot, entryItem(target, now));
        }

        // Nav
        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta pm = prev.getItemMeta();
        pm.setDisplayName(ChatColor.YELLOW + "Previous page (" + Math.max(page - 1, 1) + ")");
        prev.setItemMeta(pm);
        inventory.setItem(SLOT_PREV, prev);

        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nm = next.getItemMeta();
        nm.setDisplayName(ChatColor.YELLOW + "Next page (" + (page + 1) + ")");
        next.setItemMeta(nm);
        inventory.setItem(SLOT_NEXT, next);

        ItemStack back = new ItemStack(Material.OAK_DOOR);
        ItemMeta bm = back.getItemMeta();
        bm.setDisplayName(ChatColor.AQUA + "Back to admin menu");
        back.setItemMeta(bm);
        inventory.setItem(SLOT_BACK, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        cm.setDisplayName(ChatColor.RED + "Close");
        close.setItemMeta(cm);
        inventory.setItem(SLOT_CLOSE, close);
    }

    private ItemStack filterItem(Filter f) {
        Material mat;
        ChatColor color;
        String name;
        switch (f) {
            case ACTIVE -> {
                mat = Material.LIME_DYE;
                color = ChatColor.GREEN;
                name = "Active";
            }
            case IDLE -> {
                mat = Material.YELLOW_DYE;
                color = ChatColor.YELLOW;
                name = "Idle";
            }
            case AFK -> {
                mat = Material.RED_DYE;
                color = ChatColor.RED;
                name = "AFK";
            }
            case SUSPICIOUS -> {
                mat = Material.MAGENTA_DYE;
                color = ChatColor.LIGHT_PURPLE;
                name = "Suspicious";
            }
            default -> {
                mat = Material.BOOK;
                color = ChatColor.AQUA;
                name = "All";
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean selected = (this.filter == f);
        meta.setDisplayName((selected ? ChatColor.BOLD.toString() : "") + color + name);
        List<String> lore = new ArrayList<>();
        if (selected) {
            lore.add(ChatColor.GREEN + "Selected filter");
        } else {
            lore.add(ChatColor.YELLOW + "Click to filter by " + name + ".");
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
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

    private List<Integer> buildEntrySlots() {
        List<Integer> slots = new ArrayList<>();
        // Rows 2,3,4 (index 2..4), cols 1..7
        for (int row = 2; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private ItemStack entryItem(Player target, long now) {
        ActivityState state = tracker.getState(target.getUniqueId(), now);
        long sessionMillis = sessionManager.getCurrentSessionMillis(target.getUniqueId(), now);

        HeadCache cache = plugin.getHeadCache();
        ItemStack head = (cache != null)
                ? cache.createHead(target.getUniqueId())
                : new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();

        meta.setDisplayName(ChatColor.AQUA + target.getName());

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Status: " + colorForState(state) + state.name());
        lore.add(ChatColor.GRAY + "Session: " + ChatColor.YELLOW + formatDuration(sessionMillis));
        lore.add(ChatColor.DARK_GRAY + "UUID: " + target.getUniqueId().toString().substring(0, 8) + "...");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        head.setItemMeta(meta);
        return head;
    }

    private ChatColor colorForState(ActivityState state) {
        return switch (state) {
            case ACTIVE -> ChatColor.GREEN;
            case IDLE -> ChatColor.YELLOW;
            case AFK -> ChatColor.RED;
            case SUSPICIOUS -> ChatColor.LIGHT_PURPLE;
        };
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "0m";
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            if (minutes > 0) return hours + "h " + minutes + "m";
            return hours + "h";
        }
        if (minutes > 0) {
            if (seconds > 0) return minutes + "m " + seconds + "s";
            return minutes + "m";
        }
        return seconds + "s";
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

        // Filters
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

        // Nav
        if (slot == SLOT_PREV) {
            if (page > 1) page--;
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
        // nothing special
    }
}
