package de.coinshop;

import de.coinshop.Database.Offer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Der Server-Shop: 10 zufällige Angebote, feste Preise, täglicher Wechsel um 12:00 Uhr.
 * Beim planmäßigen Wechsel wird auch das tägliche Einkommen ausgezahlt.
 */
public final class ServerShopManager {

    private static final String META_LAST_RESET = "server_shop_last_reset";

    private final CoinShopPlugin plugin;
    private final ShopItemGenerator generator;

    public ServerShopManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
        this.generator = new ShopItemGenerator(plugin);
    }

    public List<Offer> getOffers() {
        return plugin.database().getOffers();
    }

    // ------------------------------------------------------------------
    //  Zeitplan
    // ------------------------------------------------------------------

    private ZoneId zone() {
        try {
            return ZoneId.of(plugin.getConfig().getString("server-shop.timezone", "Europe/Berlin"));
        } catch (Exception e) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    public int resetHour() {
        int hour = plugin.getConfig().getInt("server-shop.reset-hour", 12);
        return Math.max(0, Math.min(23, hour));
    }

    /** Der letzte Zeitpunkt (heute oder gestern 12:00 Uhr), an dem der Shop gewechselt haben sollte. */
    private ZonedDateTime lastScheduledReset() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime reset = now.withHour(resetHour()).withMinute(0).withSecond(0).withNano(0);
        if (reset.isAfter(now)) {
            reset = reset.minusDays(1);
        }
        return reset;
    }

    /** Wie lange es noch bis zum nächsten Wechsel dauert. */
    public Duration timeUntilReset() {
        ZonedDateTime next = lastScheduledReset().plusDays(1);
        return Duration.between(ZonedDateTime.now(zone()), next);
    }

    /** Wird alle 30 Sekunden aufgerufen: wechselt die Angebote, wenn 12:00 Uhr überschritten wurde. */
    public void checkReset() {
        try {
            long lastDone = plugin.database().getMetaLong(META_LAST_RESET, 0L);
            long shouldHave = lastScheduledReset().toInstant().toEpochMilli();
            boolean scheduled = lastDone < shouldHave;
            if (scheduled || plugin.database().getOffers().isEmpty()) {
                rotate();
                if (scheduled) {
                    // Nur beim planmäßigen Wechsel gibt es das tägliche Einkommen
                    plugin.stock().updatePrice();
                    plugin.investments().payDailyIncome();
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Server-Shop-Wechsel fehlgeschlagen: " + e);
        }
    }

    /** Erzwingt einen Wechsel (/coinsadmin resetshop) - ohne Einkommen. */
    public void forceRotate() {
        rotate();
    }

    private void rotate() {
        List<Offer> offers = generator.generate();
        plugin.database().replaceOffers(offers);
        plugin.database().setMetaLong(META_LAST_RESET, System.currentTimeMillis());
        plugin.getLogger().info("Server-Shop: " + offers.size() + " neue Angebote ausgelost.");
    }

    // ------------------------------------------------------------------
    //  Kaufen
    // ------------------------------------------------------------------

    /** Ist dieses Angebot noch genau so aktuell? (Falls der Shop gewechselt hat, während das Menü offen war.) */
    private boolean isCurrent(Offer offer) {
        for (Offer current : plugin.database().getOffers()) {
            if (current.slot() == offer.slot() && current.price() == offer.price()
                    && current.item().equals(offer.item())) {
                return true;
            }
        }
        return false;
    }

    public void buy(Player player, Offer offer) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();

        if (!isCurrent(offer)) {
            msg.send(player, "server-offer-outdated");
            return;
        }

        int limit = plugin.getConfig().getInt("server-shop.purchase-limit-per-offer", 0);
        if (limit > 0 && db.getPurchases(uuid, offer.slot()) >= limit) {
            msg.send(player, "server-limit-reached", "limit", limit);
            return;
        }

        ItemStack stack = offer.item().clone();
        if (!ItemUtil.canFit(player.getInventory(), stack)) {
            msg.send(player, "inventory-full");
            return;
        }

        // Bezahlen, Kauf zählen und den Preis aufs Shop-Konto buchen: alles oder nichts
        db.begin();
        try {
            if (!db.takeCoins(uuid, offer.price())) {
                db.rollback();
                msg.send(player, "not-enough");
                return;
            }
            db.addPurchase(uuid, offer.slot());
            db.addShopAccount(offer.price());
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Kauf im Server-Shop: " + e.getMessage());
            msg.send(player, "error");
            return;
        }

        player.getInventory().addItem(stack);
        msg.send(player, "server-bought", "amount", stack.getAmount(),
                "item", ItemUtil.name(stack), "coins", offer.price());
    }
}
