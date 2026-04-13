package org.enthusia.playtime.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class PlaytimeGuiHolder implements InventoryHolder {

    private final PlaytimeGui gui;

    public PlaytimeGuiHolder(PlaytimeGui gui) {
        this.gui = gui;
    }

    public PlaytimeGui getGui() {
        return gui;
    }

    @Override
    public Inventory getInventory() {
        return gui.getInventory();
    }
}
