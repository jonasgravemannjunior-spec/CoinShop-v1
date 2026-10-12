package de.coinshop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Die Datenbank des Plugins (eine einzelne Datei: plugins/CoinShop/coinshop.db).
 *
 * Hier wird ALLES gespeichert: Kontostände, Server-Shop-Angebote, Spieler-Angebote,
 * Abholung und Verkaufs-Benachrichtigungen. Dadurch gehen beim Neustart keine Daten verloren.
 *
 * Wichtig für die Sicherheit (keine Duplikate / kein Coin-Verlust):
 * Alles, was zusammengehört (z. B. "Coins abziehen UND Angebot löschen"), läuft in einer
 * Transaktion: begin() ... commit(). Geht etwas schief, macht rollback() alles rückgängig.
 */
public final class Database {

    // ---- Kleine Datenpakete ("records"), die wir aus der Datenbank bekommen ----
    public record Offer(int slot, ItemStack item, long price) { }
    public record Ownership(String itemId, UUID owner, String ownerName) { }
    public record StockHolding(long shares, long invested) { }
    public record Listing(long id, UUID seller, String sellerName, ItemStack item, long price, long expires) { }
    public record Claim(long id, UUID owner, ItemStack item, String reason) { }
    public record SaleNotice(Material material, int amount, long coins) { }
    public record TopEntry(String name, long coins) { }

    /** Wird geworfen, wenn die Datenbank einen Fehler meldet. */
    public static final class DbException extends RuntimeException {
        public DbException(Throwable cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection conn;
    private final Logger log;

    public Database(File file, Logger log) throws SQLException {
        this.log = log;
        try {
            Class.forName("org.sqlite.JDBC"); // SQLite-Treiber (bringt der Server mit)
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite-Treiber wurde nicht gefunden", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            // Kontostände
            st.execute("CREATE TABLE IF NOT EXISTS balances ("
                    + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0)");
            // Allerlei Merkzettel (z. B. wann der Server-Shop zuletzt gewechselt hat)
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            // Die 10 aktuellen Angebote des Server-Shops
            st.execute("CREATE TABLE IF NOT EXISTS server_offers ("
                    + "slot INTEGER PRIMARY KEY, material TEXT NOT NULL, amount INTEGER NOT NULL, price INTEGER NOT NULL)");
            // Wie oft hat welcher Spieler welches Server-Angebot gekauft?
            st.execute("CREATE TABLE IF NOT EXISTS server_purchases ("
                    + "uuid TEXT NOT NULL, slot INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (uuid, slot))");
            // Angebote im Spieler-Shop (das Item steckt als Bytes in der Spalte "item")
            st.execute("CREATE TABLE IF NOT EXISTS listings ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, "
                    + "item BLOB NOT NULL, price INTEGER NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL)");
            // Abholung: Items aus abgelaufenen/abgebrochenen Angeboten
            st.execute("CREATE TABLE IF NOT EXISTS claims ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, item BLOB NOT NULL, "
                    + "reason TEXT NOT NULL, created INTEGER NOT NULL)");
            // Verkäufe, die passiert sind, während der Verkäufer offline war
            st.execute("CREATE TABLE IF NOT EXISTS sale_notices ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, material TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, coins INTEGER NOT NULL)");
            // Server-Shop-Angebote (neue Version: das ganze Item steckt als Bytes drin, damit auch
            // verzauberte Rüstung und Bücher gespeichert werden können)
            st.execute("CREATE TABLE IF NOT EXISTS server_offers2 ("
                    + "slot INTEGER PRIMARY KEY, item BLOB NOT NULL, price INTEGER NOT NULL)");
            // Gekaufte Verkaufs-Slots im Spieler-Shop
            st.execute("CREATE TABLE IF NOT EXISTS player_slots ("
                    + "uuid TEXT PRIMARY KEY, slots INTEGER NOT NULL)");
            // Investitionen: wem gehört welches Item aus welchem Set? (item_id = "setname:nummer")
            st.execute("CREATE TABLE IF NOT EXISTS investments ("
                    + "item_id TEXT PRIMARY KEY, owner TEXT NOT NULL, bought INTEGER NOT NULL)");
            // Tägliches Einkommen, das ein Spieler noch nicht "gesehen" hat (z. B. weil er offline war)
            st.execute("CREATE TABLE IF NOT EXISTS income_pending ("
                    + "uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL)");
            // Börse: Anteile pro Spieler (invested = wofür der Spieler sie insgesamt gekauft hat)
            st.execute("CREATE TABLE IF NOT EXISTS stock_holdings ("
                    + "uuid TEXT PRIMARY KEY, shares INTEGER NOT NULL, invested INTEGER NOT NULL)");
            // Börse: Kursverlauf (ein Eintrag pro Tag)
            st.execute("CREATE TABLE IF NOT EXISTS stock_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, price INTEGER NOT NULL, at INTEGER NOT NULL)");
        }
    }

    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warning("Datenbank konnte nicht sauber geschlossen werden: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Transaktionen
    // =====================================================================

    public void begin() {
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public void commit() {
        try {
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warning("Rollback fehlgeschlagen: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Kleine Hilfsmethoden, damit wir nicht überall dasselbe schreiben müssen
    // =====================================================================

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Führt ein INSERT/UPDATE/DELETE aus und gibt zurück, wie viele Zeilen betroffen waren. */
    private int update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Führt eine Abfrage aus und wandelt jede Zeile mit dem "mapper" um. */
    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    T value = mapper.map(rs);
                    if (value != null) {
                        result.add(value);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return result;
    }

    private ItemStack readItem(ResultSet rs, String column) throws SQLException {
        try {
            return ItemStack.deserializeBytes(rs.getBytes(column));
        } catch (Exception e) {
            log.warning("Ein gespeichertes Item konnte nicht gelesen werden und wird übersprungen: " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    //  Kontostände
    // =====================================================================

    /** Legt den Spieler an (falls neu) und merkt sich seinen aktuellen Namen. Startguthaben: 0. */
    public void ensurePlayer(UUID uuid, String name) {
        update("INSERT INTO balances (uuid, name, coins) VALUES (?, ?, 0) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name", uuid.toString(), name);
    }

    public long getCoins(UUID uuid) {
        List<Long> r = query("SELECT coins FROM balances WHERE uuid = ?", rs -> rs.getLong(1), uuid.toString());
        return r.isEmpty() ? 0L : r.get(0);
    }

    public void addCoins(UUID uuid, long amount) {
        update("UPDATE balances SET coins = coins + ? WHERE uuid = ?", amount, uuid.toString());
    }

    /** Zieht Coins ab, aber nur wenn genug da sind. Gibt true zurück, wenn es geklappt hat. */
    public boolean takeCoins(UUID uuid, long amount) {
        return update("UPDATE balances SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                amount, uuid.toString(), amount) == 1;
    }

    public void setCoins(UUID uuid, long amount) {
        update("UPDATE balances SET coins = ? WHERE uuid = ?", amount, uuid.toString());
    }

    /** Überweisung von A nach B. Alles oder nichts. */
    public boolean transfer(UUID from, UUID to, long amount) {
        begin();
        try {
            if (!takeCoins(from, amount)) {
                rollback();
                return false;
            }
            addCoins(to, amount);
            commit();
            return true;
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    public Optional<UUID> findUuidByName(String name) {
        List<String> r = query("SELECT uuid FROM balances WHERE name = ? COLLATE NOCASE",
                rs -> rs.getString(1), name);
        return r.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(r.get(0)));
    }

    public String getName(UUID uuid) {
        List<String> r = query("SELECT name FROM balances WHERE uuid = ?", rs -> rs.getString(1), uuid.toString());
        return r.isEmpty() ? uuid.toString() : r.get(0);
    }

    public List<TopEntry> top(int limit) {
        return query("SELECT name, coins FROM balances WHERE coins > 0 ORDER BY coins DESC, name ASC LIMIT ?",
                rs -> new TopEntry(rs.getString(1), rs.getLong(2)), limit);
    }

    // =====================================================================
    //  Merkzettel (meta)
    // =====================================================================

    public long getMetaLong(String key, long fallback) {
        List<String> r = query("SELECT value FROM meta WHERE key = ?", rs -> rs.getString(1), key);
        if (r.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(r.get(0));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setMetaLong(String key, long value) {
        update("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, Long.toString(value));
    }

    // =====================================================================
    //  Server-Shop
    // =====================================================================

    /** Ersetzt alle Angebote durch neue und setzt die Kauf-Zähler aller Spieler zurück. */
    public void replaceOffers(List<Offer> offers) {
        begin();
        try {
            update("DELETE FROM server_offers2");
            update("DELETE FROM server_purchases");
            for (Offer o : offers) {
                update("INSERT INTO server_offers2 (slot, item, price) VALUES (?, ?, ?)",
                        o.slot(), o.item().serializeAsBytes(), o.price());
            }
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    public List<Offer> getOffers() {
        return query("SELECT slot, item, price FROM server_offers2 ORDER BY slot",
                rs -> {
                    ItemStack item = readItem(rs, "item");
                    return item == null ? null : new Offer(rs.getInt("slot"), item, rs.getLong("price"));
                });
    }

    public int getPurchases(UUID uuid, int slot) {
        List<Integer> r = query("SELECT count FROM server_purchases WHERE uuid = ? AND slot = ?",
                rs -> rs.getInt(1), uuid.toString(), slot);
        return r.isEmpty() ? 0 : r.get(0);
    }

    public void addPurchase(UUID uuid, int slot) {
        update("INSERT INTO server_purchases (uuid, slot, count) VALUES (?, ?, 1) "
                + "ON CONFLICT(uuid, slot) DO UPDATE SET count = count + 1", uuid.toString(), slot);
    }

    // =====================================================================
    //  Spieler-Shop: Angebote
    // =====================================================================

    public void insertListing(UUID seller, String sellerName, ItemStack item, long price, long expires) {
        update("INSERT INTO listings (seller, seller_name, item, price, created, expires) VALUES (?, ?, ?, ?, ?, ?)",
                seller.toString(), sellerName, item.serializeAsBytes(), price, System.currentTimeMillis(), expires);
    }

    private Listing mapListing(ResultSet rs) throws SQLException {
        ItemStack item = readItem(rs, "item");
        if (item == null) {
            return null;
        }
        return new Listing(rs.getLong("id"), UUID.fromString(rs.getString("seller")),
                rs.getString("seller_name"), item, rs.getLong("price"), rs.getLong("expires"));
    }

    /** Neueste Angebote zuerst; "offset" und "limit" dienen zum Blättern. */
    public List<Listing> getListings(int offset, int limit) {
        return query("SELECT * FROM listings ORDER BY id DESC LIMIT ? OFFSET ?", this::mapListing, limit, offset);
    }

    public int countListings() {
        List<Integer> r = query("SELECT COUNT(*) FROM listings", rs -> rs.getInt(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public List<Listing> getListingsOf(UUID seller) {
        return query("SELECT * FROM listings WHERE seller = ? ORDER BY id DESC", this::mapListing, seller.toString());
    }

    public int countListingsOf(UUID seller) {
        List<Integer> r = query("SELECT COUNT(*) FROM listings WHERE seller = ?", rs -> rs.getInt(1), seller.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public Optional<Listing> getListing(long id) {
        List<Listing> r = query("SELECT * FROM listings WHERE id = ?", this::mapListing, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Löscht ein Angebot. true = es existierte und wurde gelöscht (schützt vor doppeltem Kauf). */
    public boolean deleteListing(long id) {
        return update("DELETE FROM listings WHERE id = ?", id) == 1;
    }

    public List<Listing> getExpired(long now) {
        return query("SELECT * FROM listings WHERE expires <= ?", this::mapListing, now);
    }

    // =====================================================================
    //  Abholung (claims)
    // =====================================================================

    public void addClaim(UUID owner, ItemStack item, String reason) {
        update("INSERT INTO claims (owner, item, reason, created) VALUES (?, ?, ?, ?)",
                owner.toString(), item.serializeAsBytes(), reason, System.currentTimeMillis());
    }

    private Claim mapClaim(ResultSet rs) throws SQLException {
        ItemStack item = readItem(rs, "item");
        if (item == null) {
            return null;
        }
        return new Claim(rs.getLong("id"), UUID.fromString(rs.getString("owner")), item, rs.getString("reason"));
    }

    public List<Claim> getClaims(UUID owner) {
        return query("SELECT * FROM claims WHERE owner = ? ORDER BY id ASC", this::mapClaim, owner.toString());
    }

    public int countClaims(UUID owner) {
        List<Integer> r = query("SELECT COUNT(*) FROM claims WHERE owner = ?", rs -> rs.getInt(1), owner.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public Optional<Claim> getClaim(long id) {
        List<Claim> r = query("SELECT * FROM claims WHERE id = ?", this::mapClaim, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public boolean deleteClaim(long id) {
        return update("DELETE FROM claims WHERE id = ?", id) == 1;
    }

    // =====================================================================
    //  Verkaufs-Benachrichtigungen für Offline-Verkäufer
    // =====================================================================

    public void addSaleNotice(UUID uuid, Material material, int amount, long coins) {
        update("INSERT INTO sale_notices (uuid, material, amount, coins) VALUES (?, ?, ?, ?)",
                uuid.toString(), material.name(), amount, coins);
    }

    /** Holt alle Benachrichtigungen eines Spielers und löscht sie gleichzeitig. */
    public List<SaleNotice> popSaleNotices(UUID uuid) {
        List<SaleNotice> list = query("SELECT material, amount, coins FROM sale_notices WHERE uuid = ? ORDER BY id",
                rs -> {
                    Material m = Material.matchMaterial(rs.getString(1));
                    return m == null ? null : new SaleNotice(m, rs.getInt(2), rs.getLong(3));
                }, uuid.toString());
        update("DELETE FROM sale_notices WHERE uuid = ?", uuid.toString());
        return list;
    }

    // =====================================================================
    //  Konto des Shops (alle Einnahmen des Shops landen hier)
    // =====================================================================

    private static final String META_SHOP_ACCOUNT = "shop_account";

    public long getShopAccount() {
        return getMetaLong(META_SHOP_ACCOUNT, 0L);
    }

    public void addShopAccount(long amount) {
        setMetaLong(META_SHOP_ACCOUNT, getShopAccount() + amount);
    }

    /** Zieht Coins vom Shop-Konto ab, aber nur wenn genug drauf sind. */
    public boolean takeShopAccount(long amount) {
        long current = getShopAccount();
        if (current < amount) {
            return false;
        }
        setMetaLong(META_SHOP_ACCOUNT, current - amount);
        return true;
    }

    // =====================================================================
    //  Verkaufs-Slots im Spieler-Shop
    // =====================================================================

    /** Wie viele Slots hat der Spieler schon GEKAUFT? */
    public int getSlots(UUID uuid) {
        List<Integer> r = query("SELECT slots FROM player_slots WHERE uuid = ?", rs -> rs.getInt(1), uuid.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public void addSlot(UUID uuid) {
        update("INSERT INTO player_slots (uuid, slots) VALUES (?, 1) "
                + "ON CONFLICT(uuid) DO UPDATE SET slots = slots + 1", uuid.toString());
    }

    // =====================================================================
    //  Investitionen (Sets)
    // =====================================================================

    private Ownership mapOwnership(ResultSet rs) throws SQLException {
        return new Ownership(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3));
    }

    private static final String OWNERSHIP_SELECT =
            "SELECT i.item_id, i.owner, COALESCE(b.name, i.owner) FROM investments i "
                    + "LEFT JOIN balances b ON b.uuid = i.owner";

    public List<Ownership> getOwnerships() {
        return query(OWNERSHIP_SELECT, this::mapOwnership);
    }

    public Optional<Ownership> getOwnership(String itemId) {
        List<Ownership> r = query(OWNERSHIP_SELECT + " WHERE i.item_id = ?", this::mapOwnership, itemId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Trägt den Besitzer ein. false = das Item gehört schon jemandem (schützt vor Doppelkauf). */
    public boolean insertOwnership(String itemId, UUID owner) {
        return update("INSERT OR IGNORE INTO investments (item_id, owner, bought) VALUES (?, ?, ?)",
                itemId, owner.toString(), System.currentTimeMillis()) == 1;
    }

    /** Überträgt ein Item, aber nur wenn "from" wirklich noch der Besitzer ist. */
    public boolean transferOwnership(String itemId, UUID from, UUID to) {
        return update("UPDATE investments SET owner = ? WHERE item_id = ? AND owner = ?",
                to.toString(), itemId, from.toString()) == 1;
    }

    // =====================================================================
    //  Tägliches Einkommen
    // =====================================================================

    /** Gibt ALLEN bekannten Spielern (auch Offline-Spielern) den Grundbetrag. */
    public void payToAll(long amount) {
        update("UPDATE balances SET coins = coins + ?", amount);
        update("INSERT INTO income_pending (uuid, amount) SELECT uuid, ? FROM balances WHERE true "
                + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount", amount);
    }

    /** Gibt einem Spieler Extra-Einkommen (z. B. aus Sets). */
    public void payExtra(UUID uuid, long amount) {
        update("UPDATE balances SET coins = coins + ? WHERE uuid = ?", amount, uuid.toString());
        update("INSERT INTO income_pending (uuid, amount) VALUES (?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount", uuid.toString(), amount);
    }

    /** Holt den noch nicht angezeigten Einkommens-Betrag eines Spielers und setzt ihn auf 0. */
    public long popPendingIncome(UUID uuid) {
        List<Long> r = query("SELECT amount FROM income_pending WHERE uuid = ?", rs -> rs.getLong(1), uuid.toString());
        update("DELETE FROM income_pending WHERE uuid = ?", uuid.toString());
        return r.isEmpty() ? 0L : r.get(0);
    }

    // =====================================================================
    //  Börse
    // =====================================================================

    public StockHolding getStockHolding(UUID uuid) {
        List<StockHolding> r = query("SELECT shares, invested FROM stock_holdings WHERE uuid = ?",
                rs -> new StockHolding(rs.getLong(1), rs.getLong(2)), uuid.toString());
        return r.isEmpty() ? new StockHolding(0, 0) : r.get(0);
    }

    public void setStockHolding(UUID uuid, long shares, long invested) {
        update("INSERT INTO stock_holdings (uuid, shares, invested) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET shares = excluded.shares, invested = excluded.invested",
                uuid.toString(), shares, invested);
    }

    public void deleteStockHolding(UUID uuid) {
        update("DELETE FROM stock_holdings WHERE uuid = ?", uuid.toString());
    }

    public void addStockHistory(long price) {
        update("INSERT INTO stock_history (price, at) VALUES (?, ?)", price, System.currentTimeMillis());
    }

    /** Die letzten Kurse, der neueste zuerst. */
    public List<Long> getStockHistory(int limit) {
        return query("SELECT price FROM stock_history ORDER BY id DESC LIMIT ?", rs -> rs.getLong(1), limit);
    }
}
