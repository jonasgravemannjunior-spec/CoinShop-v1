package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.Database.Offer;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Der Server-Shop: bis zu 10 Angebote in zwei Reihen à 5 Felder. */
public final class ServerShopMenu extends Menu {

    // Die Felder der 10 Angebote (Reihe 2 und Reihe 3 des 4-zeiligen Menüs)
    private static final int[] OFFER_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};

    public ServerShopMenu(CoinShopPlugin plugin, Player viewer) {
        super(plugin, 4, plugin.messages().get("gui.server.title"));
        Messages msg = plugin.messages();
        UUID uuid = viewer.getUniqueId();
        int limit = plugin.getConfig().getInt("server-shop.purchase-limit-per-offer", 0);

        List<Offer> offers = plugin.serverShop().getOffers();
        if (offers.isEmpty()) {
            set(13, ItemUtil.icon(Material.BARRIER, msg.get("gui.server.empty-name"), List.of()), null);
        }

        for (int i = 0; i < offers.size() && i < OFFER_SLOTS.length; i++) {
            Offer offer = offers.get(i);
            int bought = plugin.database().getPurchases(uuid, offer.slot());

            List<Component> lore = limit > 0
                    ? msg.getList("gui.server.offer-lore", "price", offer.price(), "bought", bought, "limit", limit)
                    : msg.getList("gui.server.offer-lore-unlimited", "price", offer.price());
            ItemStack display = ItemUtil.withExtraLore(offer.item(), lore);

            set(OFFER_SLOTS[i], display, (player, click) -> {
                plugin.serverShop().buy(player, offer);
                // Menü neu aufbauen, damit Kontostand und Kauf-Zähler stimmen
                new ServerShopMenu(plugin, player).open(player);
            });
        }

        // Unten: Zurück | Kontostand | Wechsel-Timer
        set(31, backButton(), (player, click) -> new MainMenu(plugin).open(player));
        set(29, balanceIcon(viewer), null);

        Duration left = plugin.serverShop().timeUntilReset();
        set(33, ItemUtil.icon(Material.CLOCK, msg.get("gui.server.timer-name"),
                msg.getList("gui.server.timer-lore", "time", ItemUtil.formatDuration(left.toMillis()))), null);

        fillEmpty();
    }
}
