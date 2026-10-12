package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.Database.Listing;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Der Spieler-Shop: alle Angebote, 45 pro Seite, unten die Knöpfe. */
public final class PlayerShopMenu extends Menu {

    private static final int PER_PAGE = 45;

    public PlayerShopMenu(CoinShopPlugin plugin, Player viewer, int page) {
        super(plugin, 6, plugin.messages().get("gui.player.title", "page", page + 1));
        Messages msg = plugin.messages();
        var shop = plugin.playerShop();

        int total = plugin.database().countListings();
        List<Listing> listings = plugin.database().getListings(page * PER_PAGE, PER_PAGE);

        if (listings.isEmpty()) {
            set(22, ItemUtil.icon(Material.BARRIER, msg.get("gui.player.empty-name"), List.of()), null);
        }

        for (int i = 0; i < listings.size(); i++) {
            Listing listing = listings.get(i);
            boolean own = listing.seller().equals(viewer.getUniqueId());

            long timeLeft = listing.expires() - System.currentTimeMillis();
            var lore = own
                    ? msg.getList("gui.player.own-listing-lore", "price", listing.price())
                    : msg.getList("gui.player.listing-lore", "price", listing.price(),
                            "seller", listing.sellerName(), "time", ItemUtil.formatDuration(timeLeft));
            ItemStack display = ItemUtil.withExtraLore(listing.item(), lore);

            set(i, display, (player, click) -> {
                shop.buy(player, listing.id());
                new PlayerShopMenu(plugin, player, page).open(player);
            });
        }

        // ---- Untere Reihe (Felder 45-53) ----
        if (page > 0) {
            set(45, ItemUtil.icon(Material.ARROW, msg.get("gui.player.prev-name"), List.of()),
                    (player, click) -> new PlayerShopMenu(plugin, player, page - 1).open(player));
        }
        set(46, backButton(), (player, click) -> new MainMenu(plugin).open(player));

        set(48, ItemUtil.icon(Material.CHEST, msg.get("gui.player.mine-name"), msg.getList("gui.player.mine-lore")),
                (player, click) -> new MyListingsMenu(plugin, player).open(player));

        set(49, ItemUtil.icon(Material.WRITABLE_BOOK, msg.get("gui.player.sell-name"),
                        msg.getList("gui.player.sell-lore",
                                "fee_percent", shop.feePercent(),
                                "min_fee", shop.minFee(),
                                "tax_percent", shop.taxPercent())),
                (player, click) -> shop.startPricePrompt(player));

        set(50, balanceIcon(viewer), null);

        if ((page + 1) * PER_PAGE < total) {
            set(53, ItemUtil.icon(Material.ARROW, msg.get("gui.player.next-name"), List.of()),
                    (player, click) -> new PlayerShopMenu(plugin, player, page + 1).open(player));
        }

        fillEmpty();
    }
}
