package de.coinshop.gui;

import de.coinshop.CoinShopPlugin;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Das Hauptmenü: Server-Shop, Spieler-Shop, Glücksrad und Investitionen. */
public final class MainMenu extends Menu {

    public MainMenu(CoinShopPlugin plugin) {
        super(plugin, 4, plugin.messages().get("gui.main.title"));
        Messages msg = plugin.messages();
        int hour = plugin.serverShop().resetHour();

        // Obere Reihe: die vier Bereiche
        set(10, ItemUtil.icon(Material.ENDER_CHEST, msg.get("gui.main.server-name"),
                        msg.getList("gui.main.server-lore", "hour", hour)),
                (player, click) -> new ServerShopMenu(plugin, player).open(player));

        set(12, ItemUtil.icon(Material.EMERALD, msg.get("gui.main.player-name"),
                        msg.getList("gui.main.player-lore")),
                (player, click) -> new PlayerShopMenu(plugin, player, 0).open(player));

        set(14, ItemUtil.icon(Material.NETHER_STAR, msg.get("gui.main.wheel-name"),
                        msg.getList("gui.main.wheel-lore", "price", plugin.wheel().price(),
                                "odds", plugin.wheel().jackpotOdds())),
                (player, click) -> new WheelMenu(plugin, player).open(player));

        set(16, ItemUtil.icon(Material.GOLD_BLOCK, msg.get("gui.main.invest-name"),
                        msg.getList("gui.main.invest-lore")),
                (player, click) -> new InvestMenu(plugin, player).open(player));

        // Untere Reihe: Schließen (Kontostand und Shop-Konto kommen beim Öffnen dazu)
        set(31, ItemUtil.icon(Material.BARRIER, msg.get("gui.close-name"), List.of()),
                (player, click) -> player.closeInventory());

        fillEmpty();
    }

    @Override
    public void open(Player player) {
        Messages msg = plugin.messages();
        // Diese beiden Anzeigen ändern sich ständig, deshalb erst beim Öffnen einsetzen
        set(29, balanceIcon(player), null);
        set(33, ItemUtil.icon(Material.GOLD_INGOT, msg.get("gui.main.shop-account-name"),
                msg.getList("gui.main.shop-account-lore", "coins", plugin.database().getShopAccount())), null);
        super.open(player);
    }
}
