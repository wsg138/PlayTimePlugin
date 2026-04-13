package org.enthusia.playtime.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

public abstract class BaseMenu {

    protected final Player viewer;
    protected Inventory inventory;

    protected BaseMenu(Player viewer) {
        this.viewer = viewer;
    }

    public abstract String getTitle();

    public abstract int getSize(); // 9 * rows

    protected abstract void build();

    public void open() {
        this.inventory = Bukkit.createInventory(viewer, getSize(), getTitle());
        build();
        viewer.openInventory(inventory);
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Player getViewer() {
        return viewer;
    }
}
