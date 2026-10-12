package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Sorgt dafür, dass man Items aus unseren Menüs NICHT herausnehmen kann und dass Klicks
 * die richtige Aktion auslösen.
 */
public final class MenuListener implements Listener {

    private final CoinShopPlugin plugin;

    public MenuListener(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return; // kein CoinShop-Menü
        }

        // JEDEN Klick sperren (auch Shift-Klick, Zahlentasten, Doppelklick ...),
        // damit nichts aus dem Menü genommen oder hineingelegt werden kann.
        event.setCancelled(true);

        // Nur Klicks auf das Menü selbst (oben) lösen Aktionen aus
        if (event.getClickedInventory() != top) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getSlot();
        ClickType click = event.getClick();
        // Die Aktion erst einen Moment später ausführen (sicherer beim Öffnen neuer Menüs).
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Ist das Menü noch offen? Sonst ignorieren.
            if (player.getOpenInventory().getTopInventory().getHolder() == menu) {
                menu.handleClick(player, slot, click);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
