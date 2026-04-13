package org.enthusia.playtime.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Central listener for all playtime GUIs.
 * Cancels edits and forwards clicks/close events to the proper PlaytimeGui instance.
 */
public final class GuiListener implements Listener {

    /**
     * Helper: resolve the PlaytimeGui instance from an inventory holder.
     */
    private PlaytimeGui resolveGui(InventoryClickEvent event) {
        if (event.getView().getTopInventory() == null) {
            return null;
        }

        var topHolder = event.getView().getTopInventory().getHolder();

        if (topHolder instanceof PlaytimeGuiHolder holder) {
            return holder.getGui();
        }
        if (topHolder instanceof PlaytimeGui directGui) {
            return directGui;
        }
        return null;
    }

    private PlaytimeGui resolveGui(InventoryDragEvent event) {
        if (event.getView().getTopInventory() == null) {
            return null;
        }

        var topHolder = event.getView().getTopInventory().getHolder();

        if (topHolder instanceof PlaytimeGuiHolder holder) {
            return holder.getGui();
        }
        if (topHolder instanceof PlaytimeGui directGui) {
            return directGui;
        }
        return null;
    }

    private PlaytimeGui resolveGui(InventoryCloseEvent event) {
        if (event.getView().getTopInventory() == null) {
            return null;
        }

        var topHolder = event.getView().getTopInventory().getHolder();

        if (topHolder instanceof PlaytimeGuiHolder holder) {
            return holder.getGui();
        }
        if (topHolder instanceof PlaytimeGui directGui) {
            return directGui;
        }
        return null;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        PlaytimeGui gui = resolveGui(event);
        if (gui == null) {
            return; // not one of our GUIs
        }

        // Hard-stop ALL item movement while our GUI is open
        event.setCancelled(true);

        // Only pass clicks that are actually inside the top inventory to the GUI
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < 0 || rawSlot >= topSize) {
            // Click in player inventory; we block it, but don't forward to GUI logic
            return;
        }

        gui.handleClick(event);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        PlaytimeGui gui = resolveGui(event);
        if (gui == null) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();

        // If any of the dragged slots touch the top inventory, cancel it
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        PlaytimeGui gui = resolveGui(event);
        if (gui == null) {
            return;
        }
        gui.handleClose(event);
    }
}
