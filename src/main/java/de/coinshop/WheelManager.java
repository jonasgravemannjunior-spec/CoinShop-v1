package de.coinshop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Das Glücksrad.
 *
 * - Eine Drehung kostet Coins; der Einsatz geht aufs Konto des Shops.
 * - Der Jackpot ist das GANZE Shop-Konto (Chance in der config, Standard 1 zu 1000).
 * - Dazu gibt es Trostpreise mit festen Beträgen, die ebenfalls vom Shop-Konto bezahlt werden
 *   (höchstens so viel, wie gerade darauf ist).
 *
 * Wichtig: Das Ergebnis wird sofort in der Datenbank entschieden und bezahlt. Die Animation im
 * Menü ist nur Show - wer das Menü währenddessen schließt, bekommt trotzdem seinen Gewinn.
 */
public final class WheelManager {

    /** Ein Feld auf dem Rad. */
    public record Prize(boolean jackpot, long amount, double weight) { }

    /** Das Ergebnis einer Drehung. "payout" ist der tatsächlich ausgezahlte Betrag. */
    public record SpinResult(Prize prize, long payout) { }

    private final CoinShopPlugin plugin;
    private final Random random = new Random();
    private final Set<UUID> spinning = ConcurrentHashMap.newKeySet();

    public WheelManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Einstellungen
    // ------------------------------------------------------------------

    public long price() {
        return Math.max(1, plugin.getConfig().getLong("wheel.price", 5));
    }

    public double jackpotChance() {
        double chance = plugin.getConfig().getDouble("wheel.jackpot-chance", 0.001);
        return Math.max(0.0, Math.min(1.0, chance));
    }

    /** Chance, überhaupt zu gewinnen (Trostpreis oder Jackpot). Mindestens so groß wie die Jackpot-Chance. */
    public double winChance() {
        double chance = plugin.getConfig().getDouble("wheel.win-chance", 0.01);
        return Math.max(jackpotChance(), Math.min(1.0, chance));
    }

    /** "1 zu X" für die Gewinnchance. */
    public long winOdds() {
        double chance = winChance();
        return chance <= 0 ? 0 : Math.round(1.0 / chance);
    }

    /** "1 zu X" für die Anzeige. */
    public long jackpotOdds() {
        double chance = jackpotChance();
        return chance <= 0 ? 0 : Math.round(1.0 / chance);
    }

    /** Die Trostpreise aus der config. */
    public List<Prize> consolationPrizes() {
        List<Prize> list = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("wheel.prizes");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                double weight = s.getDouble("weight", 1);
                if (weight > 0) {
                    list.add(new Prize(false, Math.max(0, s.getLong("amount", 0)), weight));
                }
            }
        }
        if (list.isEmpty()) {
            list.add(new Prize(false, 0, 1)); // Notfall: nur Nieten
        }
        return list;
    }

    private Prize jackpotPrize() {
        return new Prize(true, 0, jackpotChance());
    }

    // ------------------------------------------------------------------
    //  Auslosen
    // ------------------------------------------------------------------

    /** Wählt zufällig ein Feld (mit den echten Wahrscheinlichkeiten). */
    public Prize roll() {
        double r = random.nextDouble();
        if (r < jackpotChance()) {
            return jackpotPrize();
        }
        if (r >= winChance()) {
            return new Prize(false, 0, 1); // Niete (der Normalfall)
        }
        // Gewonnen, aber kein Jackpot: einen Trostpreis auslosen
        List<Prize> prizes = new ArrayList<>();
        for (Prize p : consolationPrizes()) {
            if (p.amount() > 0) {
                prizes.add(p);
            }
        }
        if (prizes.isEmpty()) {
            return new Prize(false, 0, 1);
        }
        double total = 0;
        for (Prize p : prizes) {
            total += p.weight();
        }
        double pick = random.nextDouble() * total;
        for (Prize p : prizes) {
            pick -= p.weight();
            if (pick < 0) {
                return p;
            }
        }
        return prizes.get(prizes.size() - 1);
    }

    /** Nur für die Animation: ein beliebiges Feld, alle gleich wahrscheinlich (zählt NICHT als Ergebnis). */
    public Prize decorativeRoll() {
        List<Prize> all = new ArrayList<>();
        all.add(new Prize(false, 0, 1));
        for (Prize p : consolationPrizes()) {
            if (p.amount() > 0) {
                all.add(p);
            }
        }
        all.add(jackpotPrize());
        return all.get(random.nextInt(all.size()));
    }

    public Random random() {
        return random;
    }

    // ------------------------------------------------------------------
    //  Drehen
    // ------------------------------------------------------------------

    public boolean isSpinning(UUID uuid) {
        return spinning.contains(uuid);
    }

    public void endSpin(UUID uuid) {
        spinning.remove(uuid);
    }

    /**
     * Bezahlt den Einsatz, entscheidet das Ergebnis und zahlt den Gewinn aus.
     * Gibt null zurück, wenn nicht gedreht werden kann (die Nachricht wurde schon gesendet).
     */
    public SpinResult trySpin(Player player) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();
        if (!spinning.add(uuid)) {
            return null; // dreht gerade schon
        }

        long price = price();
        db.begin();
        try {
            if (!db.takeCoins(uuid, price)) {
                db.rollback();
                spinning.remove(uuid);
                msg.send(player, "wheel-not-enough", "price", price);
                return null;
            }
            db.addShopAccount(price); // Einsatz aufs Shop-Konto

            Prize prize = roll();
            long account = db.getShopAccount();
            long payout = prize.jackpot() ? account : Math.min(prize.amount(), account);
            if (payout > 0) {
                db.takeShopAccount(payout);
                db.addCoins(uuid, payout);
            }
            db.commit();
            return new SpinResult(prize, payout);
        } catch (RuntimeException e) {
            db.rollback();
            spinning.remove(uuid);
            plugin.getLogger().severe("Fehler beim Glücksrad: " + e.getMessage());
            msg.send(player, "error");
            return null;
        }
    }

    /** Sagt dem Spieler (und bei Jackpot allen), was herausgekommen ist. */
    public void announce(Player player, SpinResult result) {
        Messages msg = plugin.messages();
        if (result.prize().jackpot()) {
            msg.send(player, "wheel-jackpot", "coins", result.payout());
            Bukkit.getServer().broadcast(msg.get("prefix").append(
                    msg.get("wheel-jackpot-broadcast", "player", player.getName(), "coins", result.payout())));
        } else if (result.prize().amount() <= 0) {
            msg.send(player, "wheel-nothing");
        } else if (result.payout() < result.prize().amount()) {
            msg.send(player, "wheel-win-capped", "coins", result.payout());
        } else {
            msg.send(player, "wheel-win", "coins", result.payout());
        }
    }

    // ------------------------------------------------------------------
    //  Darstellung eines Feldes im Menü
    // ------------------------------------------------------------------

    /** "jackpotAmount" ist die Summe, die beim Jackpot-Feld angezeigt wird. */
    public ItemStack segmentItem(Prize prize, long jackpotAmount) {
        Messages msg = plugin.messages();
        if (prize.jackpot()) {
            return ItemUtil.icon(Material.NETHER_STAR, msg.get("gui.wheel.seg-jackpot", "coins", jackpotAmount),
                    List.of());
        }
        long amount = prize.amount();
        Material pane;
        Component name;
        if (amount <= 0) {
            pane = Material.GRAY_STAINED_GLASS_PANE;
            name = msg.get("gui.wheel.seg-nothing");
        } else {
            name = msg.get("gui.wheel.seg-coins", "coins", amount);
            if (amount <= 50) {
                pane = Material.LIME_STAINED_GLASS_PANE;
            } else if (amount <= 100) {
                pane = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            } else if (amount <= 250) {
                pane = Material.YELLOW_STAINED_GLASS_PANE;
            } else if (amount <= 500) {
                pane = Material.ORANGE_STAINED_GLASS_PANE;
            } else {
                pane = Material.RED_STAINED_GLASS_PANE;
            }
        }
        return ItemUtil.icon(pane, name, List.of());
    }
}
