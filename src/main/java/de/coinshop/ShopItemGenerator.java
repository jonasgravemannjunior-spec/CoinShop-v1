package de.coinshop;

import de.coinshop.Database.Offer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Lost die 10 Angebote des Server-Shops aus:
 *  - normale Items (jedes Item, das es im Survival gibt - Sperrliste und Preise stehen in der config.yml)
 *  - verzauberte Rüstungsteile
 *  - Verzauberungsbücher
 */
public final class ShopItemGenerator {

    // Verzauberungen, die als Buch im Shop auftauchen können (Namen wie im Spiel, ohne "minecraft:").
    // Namen, die es in deiner Version nicht gibt, werden automatisch übersprungen.
    private static final List<String> BOOK_ENCHANTS = List.of(
            "protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
            "respiration", "aqua_affinity", "thorns", "depth_strider", "frost_walker", "soul_speed",
            "swift_sneak", "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
            "looting", "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power",
            "punch", "flame", "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling", "riptide",
            "channeling", "multishot", "quick_charge", "piercing", "mending", "density", "breach",
            "wind_burst");

    private final CoinShopPlugin plugin;
    private final Random random = new Random();

    // Werden bei jedem Auslosen neu aus der config.yml gelesen
    private final Map<String, Long> priceRules = new LinkedHashMap<>();
    private List<String> excludeRules = new ArrayList<>();
    private long defaultPrice = 3;

    public ShopItemGenerator(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Hauptmethode
    // ------------------------------------------------------------------

    public List<Offer> generate() {
        FileConfiguration cfg = plugin.getConfig();
        loadRules(cfg);

        int count = cfg.getInt("server-shop.offers", 10);
        double armorChance = cfg.getDouble("server-shop.enchanted-armor-chance", 0.12);
        double bookChance = cfg.getDouble("server-shop.enchanted-book-chance", 0.18);
        double rarityDivisor = Math.max(1.0, cfg.getDouble("server-shop.rarity-divisor", 200));

        List<Material> candidates = new ArrayList<>();
        List<Material> armorPieces = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!isAllowed(material)) {
                continue;
            }
            candidates.add(material);
            String name = material.name();
            if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) {
                armorPieces.add(material);
            }
        }

        // Gewichte: teure Items erscheinen seltener
        double[] cumulative = new double[candidates.size()];
        double sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            sum += 1.0 / (1.0 + unitPrice(candidates.get(i)) / rarityDivisor);
            cumulative[i] = sum;
        }

        Set<String> used = new HashSet<>();
        List<Offer> offers = new ArrayList<>();
        int attempts = 0;
        while (offers.size() < count && attempts++ < 500) {
            double roll = random.nextDouble();
            Offer offer;
            int slot = offers.size();
            if (roll < armorChance && !armorPieces.isEmpty()) {
                offer = enchantedArmor(slot, armorPieces, used);
            } else if (roll < armorChance + bookChance) {
                offer = enchantedBook(slot, used);
            } else {
                offer = normalItem(slot, candidates, cumulative, sum, used);
            }
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    // ------------------------------------------------------------------
    //  Regeln aus der config.yml
    // ------------------------------------------------------------------

    private void loadRules(FileConfiguration cfg) {
        priceRules.clear();
        ConfigurationSection prices = cfg.getConfigurationSection("server-shop.prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                priceRules.put(key.toUpperCase(Locale.ROOT), Math.max(1, prices.getLong(key)));
            }
        }
        excludeRules = new ArrayList<>();
        for (String rule : cfg.getStringList("server-shop.exclude")) {
            excludeRules.add(rule.toUpperCase(Locale.ROOT));
        }
        defaultPrice = Math.max(1, cfg.getLong("server-shop.default-price", 3));
    }

    /** Passt der Name zum Muster? Mit * am Anfang und/oder Ende sind Gruppen möglich. */
    private static boolean matches(String pattern, String name) {
        boolean star = pattern.contains("*");
        if (!star) {
            return pattern.equals(name);
        }
        String core = pattern.replace("*", "");
        boolean start = pattern.startsWith("*");
        boolean end = pattern.endsWith("*");
        if (start && end) {
            return name.contains(core);
        }
        if (start) {
            return name.endsWith(core);
        }
        return name.startsWith(core);
    }

    private boolean isAllowed(Material material) {
        if (material.name().startsWith("LEGACY_") || !material.isItem() || material.isAir()) {
            return false;
        }
        for (String rule : excludeRules) {
            if (matches(rule, material.name())) {
                return false;
            }
        }
        return true;
    }

    /** Preis eines einzelnen Items (erster passender Eintrag in der config). */
    private long unitPrice(Material material) {
        for (Map.Entry<String, Long> rule : priceRules.entrySet()) {
            if (matches(rule.getKey(), material.name())) {
                return rule.getValue();
            }
        }
        return defaultPrice;
    }

    // ------------------------------------------------------------------
    //  Normale Items
    // ------------------------------------------------------------------

    private Offer normalItem(int slot, List<Material> candidates, double[] cumulative, double sum, Set<String> used) {
        if (candidates.isEmpty()) {
            return null;
        }
        for (int tries = 0; tries < 50; tries++) {
            double pick = random.nextDouble() * sum;
            int index = 0;
            while (index < cumulative.length - 1 && cumulative[index] < pick) {
                index++;
            }
            Material material = candidates.get(index);
            if (!used.add("item:" + material.name())) {
                continue;
            }
            long unit = unitPrice(material);
            int[] range = amountRange(unit);
            int amount = range[0] + random.nextInt(range[1] - range[0] + 1);
            amount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
            return new Offer(slot, new ItemStack(material, amount), unit * amount);
        }
        return null;
    }

    /** Teure Items gibt es nur einzeln, billige Items in großen Mengen. */
    private static int[] amountRange(long unitPrice) {
        if (unitPrice >= 1000) {
            return new int[]{1, 1};
        }
        if (unitPrice >= 300) {
            return new int[]{1, 2};
        }
        if (unitPrice >= 100) {
            return new int[]{1, 4};
        }
        if (unitPrice >= 25) {
            return new int[]{2, 8};
        }
        if (unitPrice >= 8) {
            return new int[]{4, 16};
        }
        if (unitPrice >= 3) {
            return new int[]{8, 32};
        }
        return new int[]{16, 64};
    }

    // ------------------------------------------------------------------
    //  Verzauberungen
    // ------------------------------------------------------------------

    private Enchantment lookup(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    /** Kosten PRO STUFE einer Verzauberung (aus der config). */
    private long costPerLevel(String key) {
        FileConfiguration cfg = plugin.getConfig();
        long fallback = cfg.getLong("server-shop.enchant-cost-per-level.default", 100);
        return Math.max(1, cfg.getLong("server-shop.enchant-cost-per-level." + key, fallback));
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Fügt eine Verzauberung mit zufälliger Stufe zur Liste hinzu (falls es sie gibt). */
    private void addEnchant(Map<Enchantment, Integer> target, Map<String, Integer> levelsByKey, String key) {
        Enchantment enchantment = lookup(key);
        if (enchantment == null) {
            return;
        }
        int level = 1 + random.nextInt(Math.max(1, enchantment.getMaxLevel()));
        target.put(enchantment, level);
        levelsByKey.put(key, level);
    }

    private Offer enchantedArmor(int slot, List<Material> armorPieces, Set<String> used) {
        for (int tries = 0; tries < 30; tries++) {
            Material piece = armorPieces.get(random.nextInt(armorPieces.size()));
            String name = piece.name();

            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            Map<String, Integer> levels = new LinkedHashMap<>();

            // Immer genau ein Schutz-Typ
            String[] protections = {"protection", "fire_protection", "blast_protection", "projectile_protection"};
            addEnchant(enchants, levels, protections[random.nextInt(protections.length)]);
            if (chance(0.7)) {
                addEnchant(enchants, levels, "unbreaking");
            }
            if (chance(0.45)) {
                addEnchant(enchants, levels, "mending");
            }
            if (chance(0.3)) {
                addEnchant(enchants, levels, "thorns");
            }
            if (name.endsWith("_HELMET")) {
                if (chance(0.4)) {
                    addEnchant(enchants, levels, "respiration");
                }
                if (chance(0.4)) {
                    addEnchant(enchants, levels, "aqua_affinity");
                }
            } else if (name.endsWith("_LEGGINGS")) {
                if (chance(0.4)) {
                    addEnchant(enchants, levels, "swift_sneak");
                }
            } else if (name.endsWith("_BOOTS")) {
                if (chance(0.6)) {
                    addEnchant(enchants, levels, "feather_falling");
                }
                // Tiefseeläufer und Frostläufer schließen sich aus
                if (chance(0.3)) {
                    addEnchant(enchants, levels, "depth_strider");
                } else if (chance(0.2)) {
                    addEnchant(enchants, levels, "frost_walker");
                }
                if (chance(0.2)) {
                    addEnchant(enchants, levels, "soul_speed");
                }
            }
            if (enchants.isEmpty()) {
                return null;
            }

            String signature = "armor:" + name + levels;
            if (!used.add(signature)) {
                continue;
            }

            ItemStack item = new ItemStack(piece);
            item.editMeta(meta -> {
                for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                    meta.addEnchant(e.getKey(), e.getValue(), true);
                }
            });

            long price = unitPrice(piece);
            for (Map.Entry<String, Integer> e : levels.entrySet()) {
                price += costPerLevel(e.getKey()) * e.getValue();
            }
            return new Offer(slot, item, price);
        }
        return null;
    }

    private Offer enchantedBook(int slot, Set<String> used) {
        for (int tries = 0; tries < 30; tries++) {
            String key = BOOK_ENCHANTS.get(random.nextInt(BOOK_ENCHANTS.size()));
            Enchantment enchantment = lookup(key);
            if (enchantment == null) {
                continue;
            }
            int level = 1 + random.nextInt(Math.max(1, enchantment.getMaxLevel()));
            if (!used.add("book:" + key + ":" + level)) {
                continue;
            }

            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(enchantment, level, true));
            long price = Math.max(250, costPerLevel(key) * level);
            return new Offer(slot, book, price);
        }
        return null;
    }
}
