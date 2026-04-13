package org.enthusia.playtime.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;

public interface PlaytimeGui {

    Inventory getInventory();

    Player getViewer();

    void open();

    void handleClick(InventoryClickEvent event);

    default void handleClose(InventoryCloseEvent event) {
        // no-op by default
    }
}
