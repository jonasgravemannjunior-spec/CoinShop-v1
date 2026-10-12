package de.coinshop;

import de.coinshop.Database.Claim;
import de.coinshop.Database.Listing;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Die ganze Logik des Spieler-Shops: Slots kaufen, einstellen, kaufen, abbrechen, ablaufen, abholen.
 *
 * Schutz gegen "Zwischenlager"-Missbrauch (alles in config.yml einstellbar):
 *  - Verkaufs-Slots müssen gekauft werden (jeder weitere kostet das Doppelte)
 *  - Einstellgebühr, die NIE zurückerstattet wird
 *  - Angebote laufen nach einer festen Zeit ab
 *  - Mindest- und Höchstpreis
 *  - Verkaufssteuer
 * Alle Gebühren, Steuern und Slot-Käufe landen auf dem Konto des Shops.
 */
public final class PlayerShopManager {

    private static final long MAX_SLOT_PRICE = 1_000_000_000_000_000_000L;

    private final CoinShopPlugin plugin;

    public PlayerShopManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Einstellungen aus der config.yml
    // ------------------------------------------------------------------

    public long minPrice() {
        return Math.max(1, plugin.getConfig().getLong("player-shop.min-price", 1));
    }

    public long maxPrice() {
        return Math.max(minPrice(), plugin.getConfig().getLong("player-shop.max-price", 1_000_000));
    }

    public double feePercent() {
        return plugin.getConfig().getDouble("player-shop.listing-fee-percent", 5.0);
    }

    public long minFee() {
        return Math.max(0, plugin.getConfig().getLong("player-shop.min-listing-fee", 1));
    }

    public double taxPercent() {
        return plugin.getConfig().getDouble("player-shop.sale-tax-percent", 5.0);
    }

    public long feeFor(long price) {
        return Math.max(minFee(), (long) Math.floor(price * feePercent() / 100.0));
    }

    public long taxFor(long price) {
        return (long) Math.floor(price * taxPercent() / 100.0);
    }

    // ------------------------------------------------------------------
    //  Verkaufs-Slots
    // ------------------------------------------------------------------

    /** Gratis-Slots + gekaufte Slots. */
    public int totalSlots(UUID uuid) {
        int free = Math.max(0, plugin.getConfig().getInt("player-shop.free-slots", 0));
        return free + plugin.database().getSlots(uuid);
    }

    /** Höchstzahl an Slots pro Spieler. */
    public int maxSlots() {
        return Math.max(1, plugin.getConfig().getInt("player-shop.max-slots", 32));
    }

    /** Kann dieser Spieler noch einen Slot kaufen? */
    public boolean canBuySlot(UUID uuid) {
        return totalSlots(uuid) < maxSlots();
    }

    /** Preis des NÄCHSTEN Slots: Grundpreis, dann jedes Mal mal 2 (5, 10, 20, 40 ...). */
    public long nextSlotPrice(UUID uuid) {
        long base = Math.max(1, plugin.getConfig().getLong("player-shop.slot-base-price", 5));
        long multiplier = Math.max(2, plugin.getConfig().getLong("player-shop.slot-price-multiplier", 2));
        int bought = plugin.database().getSlots(uuid);
        long price = base;
        for (int i = 0; i < bought; i++) {
            if (price > MAX_SLOT_PRICE / multiplier) {
                return MAX_SLOT_PRICE;
            }
            price *= multiplier;
        }
        return price;
    }

    public void buySlot(Player player) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();
        if (!canBuySlot(uuid)) {
            msg.send(player, "slot-max", "max", maxSlots());
            return;
        }
        long price = nextSlotPrice(uuid);

        db.begin();
        try {
            if (!db.takeCoins(uuid, price)) {
                db.rollback();
                msg.send(player, "slot-too-expensive", "price", price);
                return;
            }
            db.addSlot(uuid);
            db.addShopAccount(price);
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Slot-Kauf: " + e.getMessage());
            msg.send(player, "error");
            return;
        }
        if (canBuySlot(uuid)) {
            msg.send(player, "slot-bought", "slots", totalSlots(uuid), "price", nextSlotPrice(uuid));
        } else {
            msg.send(player, "slot-bought-max", "slots", totalSlots(uuid));
        }
    }

    // ------------------------------------------------------------------
    //  Item anbieten: Preis per Chat abfragen
    // ------------------------------------------------------------------

    /** Wird beim Klick auf "Item anbieten" aufgerufen. */
    public void startPricePrompt(Player player) {
        Messages msg = plugin.messages();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!checkSellable(player, hand)) {
            return;
        }
        player.closeInventory();
        msg.send(player, "ask-price-info", "amount", hand.getAmount(), "item", ItemUtil.name(hand),
                "fee", minFee());
        msg.send(player, "ask-price", "min", minPrice(), "max", maxPrice());
        plugin.prompts().start(player, text -> handlePriceInput(player, text));
    }

    /** Verarbeitet die Chat-Eingabe des Spielers (läuft im Haupt-Thread des Servers). */
    private void handlePriceInput(Player player, String text) {
        Messages msg = plugin.messages();
        if (text.equalsIgnoreCase("abbrechen")) {
            msg.send(player, "ask-cancelled");
            return;
        }
        long price;
        try {
            price = Long.parseLong(text.replace(".", "").replace(",", "").trim());
        } catch (NumberFormatException e) {
            msg.send(player, "ask-invalid");
            return;
        }
        createListing(player, price);
    }

    // ------------------------------------------------------------------
    //  Angebot erstellen
    // ------------------------------------------------------------------

    /** Prüft Item-Regeln und Slots. Sendet bei Problemen eine Nachricht und gibt false zurück. */
    private boolean checkSellable(Player player, ItemStack hand) {
        Messages msg = plugin.messages();
        if (hand.getType().isAir()) {
            msg.send(player, "hold-item");
            return false;
        }
        List<String> blacklist = plugin.getConfig().getStringList("player-shop.blacklist");
        for (String entry : blacklist) {
            if (entry.equalsIgnoreCase(hand.getType().name())) {
                msg.send(player, "item-blacklisted");
                return false;
            }
        }
        UUID uuid = player.getUniqueId();
        if (plugin.database().countListingsOf(uuid) >= totalSlots(uuid)) {
            msg.send(player, "no-free-slot", "price", nextSlotPrice(uuid));
            return false;
        }
        return true;
    }

    /** Stellt das Item aus der Haupthand zum angegebenen Gesamtpreis ein. */
    public void createListing(Player player, long price) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!checkSellable(player, hand)) {
            return;
        }
        if (price < minPrice() || price > maxPrice()) {
            msg.send(player, "price-range", "min", minPrice(), "max", maxPrice());
            return;
        }

        long fee = feeFor(price);
        long hours = Math.max(1, plugin.getConfig().getLong("player-shop.listing-duration-hours", 48));
        long expires = System.currentTimeMillis() + hours * 3_600_000L;
        ItemStack copy = hand.clone();

        // Erst Datenbank (Gebühr + Angebot, alles oder nichts), DANN das Item aus der Hand nehmen.
        db.begin();
        try {
            if (!db.takeCoins(uuid, fee)) {
                db.rollback();
                msg.send(player, "listing-fee-too-high", "fee", fee);
                return;
            }
            db.addShopAccount(fee);
            db.insertListing(uuid, player.getName(), copy, price, expires);
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Einstellen eines Angebots: " + e.getMessage());
            msg.send(player, "error");
            return;
        }

        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        msg.send(player, "listing-created", "amount", copy.getAmount(), "item", ItemUtil.name(copy),
                "price", price, "fee", fee);
    }

    // ------------------------------------------------------------------
    //  Kaufen
    // ------------------------------------------------------------------

    public void buy(Player buyer, long listingId) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        Optional<Listing> found = db.getListing(listingId);
        if (found.isEmpty()) {
            msg.send(buyer, "listing-gone");
            return;
        }
        Listing listing = found.get();

        if (listing.seller().equals(buyer.getUniqueId())) {
            msg.send(buyer, "buy-own");
            return;
        }
        if (!ItemUtil.canFit(buyer.getInventory(), listing.item())) {
            msg.send(buyer, "inventory-full");
            return;
        }

        long price = listing.price();
        long tax = taxFor(price);
        long sellerGets = price - tax;

        db.begin();
        try {
            if (!db.takeCoins(buyer.getUniqueId(), price)) {
                db.rollback();
                msg.send(buyer, "not-enough");
                return;
            }
            // Wenn das Angebot in der Zwischenzeit schon weg ist, bricht alles ab (kein Doppelkauf)
            if (!db.deleteListing(listingId)) {
                db.rollback();
                msg.send(buyer, "listing-gone");
                return;
            }
            db.addCoins(listing.seller(), sellerGets);
            if (tax > 0) {
                db.addShopAccount(tax); // Die Steuer geht aufs Konto des Shops
            }
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Kauf im Spieler-Shop: " + e.getMessage());
            msg.send(buyer, "error");
            return;
        }

        ItemStack item = listing.item();
        buyer.getInventory().addItem(item.clone());
        msg.send(buyer, "player-bought", "amount", item.getAmount(), "item", ItemUtil.name(item), "price", price);

        // Verkäufer benachrichtigen (online sofort, offline beim nächsten Beitreten)
        Player seller = Bukkit.getPlayer(listing.seller());
        if (seller != null) {
            msg.send(seller, "listing-sold", "amount", item.getAmount(), "item", ItemUtil.name(item),
                    "coins", sellerGets);
        } else {
            try {
                db.addSaleNotice(listing.seller(), item.getType(), item.getAmount(), sellerGets);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Verkaufs-Hinweis konnte nicht gespeichert werden: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    //  Eigenes Angebot abbrechen
    // ------------------------------------------------------------------

    public void cancel(Player player, long listingId) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        Optional<Listing> found = db.getListing(listingId);
        if (found.isEmpty()) {
            msg.send(player, "listing-gone");
            return;
        }
        Listing listing = found.get();
        if (!listing.seller().equals(player.getUniqueId())) {
            return; // fremdes Angebot - nichts tun
        }

        boolean fits = ItemUtil.canFit(player.getInventory(), listing.item());
        db.begin();
        try {
            if (!db.deleteListing(listingId)) {
                db.rollback();
                msg.send(player, "listing-gone");
                return;
            }
            if (!fits) {
                // Inventar voll: Item sicher in der Abholung ablegen
                db.addClaim(player.getUniqueId(), listing.item(), "cancelled");
            }
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Abbrechen eines Angebots: " + e.getMessage());
            msg.send(player, "error");
            return;
        }

        if (fits) {
            player.getInventory().addItem(listing.item().clone());
            msg.send(player, "listing-cancelled");
        } else {
            msg.send(player, "listing-cancelled-claim");
        }
    }

    // ------------------------------------------------------------------
    //  Abgelaufene Angebote (läuft jede Minute)
    // ------------------------------------------------------------------

    public void processExpired() {
        Database db = plugin.database();
        try {
            for (Listing listing : db.getExpired(System.currentTimeMillis())) {
                db.begin();
                try {
                    if (db.deleteListing(listing.id())) {
                        db.addClaim(listing.seller(), listing.item(), "expired");
                    }
                    db.commit();
                } catch (RuntimeException e) {
                    db.rollback();
                    plugin.getLogger().severe("Abgelaufenes Angebot konnte nicht verschoben werden: " + e.getMessage());
                    continue;
                }
                Player seller = Bukkit.getPlayer(listing.seller());
                if (seller != null) {
                    plugin.messages().send(seller, "listing-expired");
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler beim Prüfen abgelaufener Angebote: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Abholung
    // ------------------------------------------------------------------

    public void claim(Player player, long claimId) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        Optional<Claim> found = db.getClaim(claimId);
        if (found.isEmpty() || !found.get().owner().equals(player.getUniqueId())) {
            return;
        }
        Claim claim = found.get();
        if (!ItemUtil.canFit(player.getInventory(), claim.item())) {
            msg.send(player, "inventory-full");
            return;
        }
        if (!db.deleteClaim(claimId)) {
            return; // schon abgeholt
        }
        player.getInventory().addItem(claim.item().clone());
        msg.send(player, "claim-taken");
    }
}
