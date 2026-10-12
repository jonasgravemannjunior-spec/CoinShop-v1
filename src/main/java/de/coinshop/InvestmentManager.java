package de.coinshop;

import de.coinshop.Database.Ownership;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Investitionen ("Monopoly"): Items aus Sets kaufen, die täglich Einkommen bringen.
 *
 * - Jedes Item gibt es nur einmal auf dem Server und gehört genau einem Spieler.
 * - Ein komplettes Set bringt das volle Einkommen (= Preis pro Item), ein Teilbesitz einen Teil davon.
 * - Items können an andere Spieler übertragen werden.
 * - Hier steckt auch die Auszahlung des täglichen Einkommens.
 */
public final class InvestmentManager {

    /** Ein Set aus der config.yml. "id" ist der Schlüssel (z. B. "set01") und wird zum Speichern benutzt. */
    public record SetDef(String id, String name, long price, List<Material> items) { }

    private final CoinShopPlugin plugin;
    private List<SetDef> sets = new ArrayList<>();

    public InvestmentManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Liest die Sets (neu) aus der config.yml. */
    public void reload() {
        List<SetDef> loaded = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("investments.sets");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                List<Material> items = new ArrayList<>();
                for (String name : s.getStringList("items")) {
                    Material material = Material.matchMaterial(name);
                    if (material == null || !material.isItem() || material.isAir()) {
                        plugin.getLogger().warning("Investitionen: '" + name + "' in " + key + " ist kein gültiges Item.");
                        material = Material.BARRIER;
                    }
                    items.add(material);
                }
                if (!items.isEmpty()) {
                    loaded.add(new SetDef(key, s.getString("name", key), Math.max(1, s.getLong("price", 100)), items));
                }
            }
        }
        sets = loaded;
    }

    public List<SetDef> sets() {
        return sets;
    }

    public static String itemId(SetDef set, int itemIndex) {
        return set.id() + ":" + itemIndex;
    }

    // ------------------------------------------------------------------
    //  Besitz und Einkommen
    // ------------------------------------------------------------------

    /** Alle vergebenen Items: itemId -> Besitzer. */
    public Map<String, Ownership> ownershipMap() {
        Map<String, Ownership> map = new HashMap<>();
        for (Ownership o : plugin.database().getOwnerships()) {
            map.put(o.itemId(), o);
        }
        return map;
    }

    /** Wie viele Items dieses Sets besitzt der Spieler? */
    public int ownedCount(SetDef set, UUID uuid, Map<String, Ownership> map) {
        int count = 0;
        for (int i = 0; i < set.items().size(); i++) {
            Ownership o = map.get(itemId(set, i));
            if (o != null && o.owner().equals(uuid)) {
                count++;
            }
        }
        return count;
    }

    /** Wie viel Prozent des Set-Einkommens bringt es, "owned" von "total" Items zu besitzen? */
    private double percent(int owned, int total) {
        if (owned <= 0) {
            return 0;
        }
        if (owned >= total) {
            return 100;
        }
        String path = "investments.percent-by-owned-count." + owned;
        if (plugin.getConfig().contains(path)) {
            return plugin.getConfig().getDouble(path);
        }
        // Standard: gleichmäßig von 10 % (1 Item) bis 100 % (komplett)
        return total <= 1 ? 100 : 10 + (owned - 1) * 90.0 / (total - 1);
    }

    /** Tägliches Einkommen aus einem Set, wenn man "owned" Items davon besitzt. */
    public long setIncome(SetDef set, int owned) {
        return Math.round(set.price() * percent(owned, set.items().size()) / 100.0);
    }

    /** Einkommen aus allen Sets (ohne Grundeinkommen). */
    public long setIncomeOf(UUID uuid, Map<String, Ownership> map) {
        long total = 0;
        for (SetDef set : sets) {
            total += setIncome(set, ownedCount(set, uuid, map));
        }
        return total;
    }

    public long baseIncome() {
        return Math.max(0, plugin.getConfig().getLong("daily-income.base", 10));
    }

    // ------------------------------------------------------------------
    //  Kaufen
    // ------------------------------------------------------------------

    public void buy(Player player, SetDef set, int itemIndex) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();
        String id = itemId(set, itemIndex);
        Material material = set.items().get(itemIndex);

        if (db.getOwnership(id).isPresent()) {
            msg.send(player, "invest-taken");
            return;
        }

        db.begin();
        try {
            if (!db.takeCoins(uuid, set.price())) {
                db.rollback();
                msg.send(player, "not-enough");
                return;
            }
            // Wenn jemand schneller war, bricht alles ab (kein Doppelverkauf)
            if (!db.insertOwnership(id, uuid)) {
                db.rollback();
                msg.send(player, "invest-taken");
                return;
            }
            db.addShopAccount(set.price()); // Der Kaufpreis geht aufs Konto des Shops
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Fehler beim Investment-Kauf: " + e.getMessage());
            msg.send(player, "error");
            return;
        }

        Map<String, Ownership> map = ownershipMap();
        int owned = ownedCount(set, uuid, map);
        msg.send(player, "invest-bought", "item", ItemUtil.name(material), "set", set.name(),
                "price", set.price(), "income", setIncome(set, owned));
        if (owned >= set.items().size()) {
            msg.send(player, "invest-set-complete", "set", set.name());
        }
    }

    // ------------------------------------------------------------------
    //  Übertragen (per Chat: erst Spielername, dann Bestätigung mit "ja")
    // ------------------------------------------------------------------

    public void startTransfer(Player player, SetDef set, int itemIndex) {
        Messages msg = plugin.messages();
        Optional<Ownership> owner = plugin.database().getOwnership(itemId(set, itemIndex));
        if (owner.isEmpty() || !owner.get().owner().equals(player.getUniqueId())) {
            msg.send(player, "transfer-failed");
            return;
        }
        player.closeInventory();
        msg.send(player, "transfer-ask-target", "item", ItemUtil.name(set.items().get(itemIndex)));
        plugin.prompts().start(player, text -> handleTarget(player, set, itemIndex, text));
    }

    private void handleTarget(Player player, SetDef set, int itemIndex, String text) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        if (text.equalsIgnoreCase("abbrechen")) {
            msg.send(player, "ask-cancelled");
            return;
        }
        Optional<UUID> target = db.findUuidByName(text.trim());
        if (target.isEmpty()) {
            msg.send(player, "player-unknown", "player", text);
            return;
        }
        if (target.get().equals(player.getUniqueId())) {
            msg.send(player, "transfer-self");
            return;
        }
        String targetName = db.getName(target.get());
        msg.send(player, "transfer-confirm", "item", ItemUtil.name(set.items().get(itemIndex)),
                "set", set.name(), "player", targetName);
        plugin.prompts().start(player, answer -> {
            if (answer.equalsIgnoreCase("ja")) {
                doTransfer(player, set, itemIndex, target.get(), targetName);
            } else {
                msg.send(player, "ask-cancelled");
            }
        });
    }

    private void doTransfer(Player from, SetDef set, int itemIndex, UUID to, String toName) {
        Messages msg = plugin.messages();
        Material material = set.items().get(itemIndex);
        boolean ok;
        try {
            ok = plugin.database().transferOwnership(itemId(set, itemIndex), from.getUniqueId(), to);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler bei der Übertragung: " + e.getMessage());
            msg.send(from, "error");
            return;
        }
        if (!ok) {
            msg.send(from, "transfer-failed");
            return;
        }
        msg.send(from, "transfer-done", "item", ItemUtil.name(material), "set", set.name(), "player", toName);
        Player receiver = Bukkit.getPlayer(to);
        if (receiver != null) {
            msg.send(receiver, "transfer-received", "item", ItemUtil.name(material), "set", set.name(),
                    "player", from.getName());
        }
    }

    // ------------------------------------------------------------------
    //  Tägliches Einkommen (wird beim Shop-Wechsel um 12:00 Uhr aufgerufen)
    // ------------------------------------------------------------------

    public void payDailyIncome() {
        Database db = plugin.database();
        long base = baseIncome();

        // Extra-Einkommen aus Sets pro Besitzer berechnen
        Map<String, Ownership> map = ownershipMap();
        Map<UUID, Long> extra = new HashMap<>();
        for (Ownership o : map.values()) {
            extra.putIfAbsent(o.owner(), 0L);
        }
        for (UUID owner : new ArrayList<>(extra.keySet())) {
            extra.put(owner, setIncomeOf(owner, map));
        }

        db.begin();
        try {
            if (base > 0) {
                db.payToAll(base);
            }
            for (Map.Entry<UUID, Long> entry : extra.entrySet()) {
                if (entry.getValue() > 0) {
                    db.payExtra(entry.getKey(), entry.getValue());
                }
            }
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().severe("Tägliches Einkommen konnte nicht ausgezahlt werden: " + e.getMessage());
            return;
        }

        // Spieler, die gerade online sind, sehen es sofort (alle anderen beim Beitreten)
        for (Player online : Bukkit.getOnlinePlayers()) {
            long amount = db.popPendingIncome(online.getUniqueId());
            if (amount > 0) {
                plugin.messages().send(online, "daily-income", "coins", amount);
            }
        }
        plugin.getLogger().info("Tägliches Einkommen ausgezahlt.");
    }
}
