package de.coinshop;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Die Befehle /coins, /pay, /baltop und /coinsadmin. */
public final class CoinCommands implements CommandExecutor, TabCompleter {

    // Größter Betrag, den ein Admin auf einmal eingeben kann (nur Schutz vor Zahlen-Überlauf)
    private static final long ADMIN_MAX = 1_000_000_000_000L;

    private final CoinShopPlugin plugin;

    public CoinCommands(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName().toLowerCase()) {
                case "coins" -> coins(sender, args);
                case "pay" -> pay(sender, args);
                case "baltop" -> baltop(sender);
                case "coinsadmin" -> admin(sender, args);
                default -> { }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler bei /" + command.getName() + ": " + e);
            plugin.messages().send(sender, "error");
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  /coins [Spieler]
    // ------------------------------------------------------------------
    private void coins(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                msg.send(sender, "only-players");
                return;
            }
            msg.send(player, "balance-self", "coins", db.getCoins(player.getUniqueId()));
            return;
        }
        Optional<UUID> target = db.findUuidByName(args[0]);
        if (target.isEmpty()) {
            msg.send(sender, "player-unknown", "player", args[0]);
            return;
        }
        msg.send(sender, "balance-other", "player", db.getName(target.get()), "coins", db.getCoins(target.get()));
    }

    // ------------------------------------------------------------------
    //  /pay [Spieler] [Betrag]
    // ------------------------------------------------------------------
    private void pay(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        if (!(sender instanceof Player player)) {
            msg.send(sender, "only-players");
            return;
        }
        if (args.length != 2) {
            msg.send(player, "pay-usage");
            return;
        }
        Optional<UUID> target = db.findUuidByName(args[0]);
        if (target.isEmpty()) {
            msg.send(player, "player-unknown", "player", args[0]);
            return;
        }
        if (target.get().equals(player.getUniqueId())) {
            msg.send(player, "pay-self");
            return;
        }
        long max = plugin.getConfig().getLong("coins.max-transfer", 1_000_000_000L);
        long amount = parseAmount(args[1], max);
        if (amount < 1) {
            msg.send(player, "invalid-amount", "max", max);
            return;
        }
        if (!db.transfer(player.getUniqueId(), target.get(), amount)) {
            msg.send(player, "not-enough");
            return;
        }

        String targetName = db.getName(target.get());
        msg.send(player, "pay-sent", "coins", amount, "player", targetName);
        Player online = Bukkit.getPlayer(target.get());
        if (online != null) {
            msg.send(online, "pay-received", "coins", amount, "player", player.getName());
        }
    }

    // ------------------------------------------------------------------
    //  /baltop
    // ------------------------------------------------------------------
    private void baltop(CommandSender sender) {
        Messages msg = plugin.messages();
        List<Database.TopEntry> top = plugin.database().top(10);
        if (top.isEmpty()) {
            msg.send(sender, "baltop-empty");
            return;
        }
        msg.sendPlain(sender, "baltop-header");
        int rank = 1;
        for (Database.TopEntry entry : top) {
            msg.sendPlain(sender, "baltop-line", "rank", rank++, "player", entry.name(), "coins", entry.coins());
        }
    }

    // ------------------------------------------------------------------
    //  /coinsadmin give|take|set [Spieler] [Betrag] | resetshop | reload
    // ------------------------------------------------------------------
    private void admin(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Database db = plugin.database();

        if (!sender.hasPermission("coinshop.admin")) {
            msg.send(sender, "no-permission");
            return;
        }
        if (args.length == 0) {
            msg.send(sender, "admin-usage");
            return;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            plugin.reloadAll();
            msg.send(sender, "admin-reload");
            return;
        }
        if (sub.equals("resetshop")) {
            plugin.serverShop().forceRotate();
            msg.send(sender, "admin-shop-reset");
            return;
        }
        if (!(sub.equals("give") || sub.equals("take") || sub.equals("set")) || args.length != 3) {
            msg.send(sender, "admin-usage");
            return;
        }

        Optional<UUID> target = db.findUuidByName(args[1]);
        if (target.isEmpty()) {
            msg.send(sender, "player-unknown", "player", args[1]);
            return;
        }
        // "set" darf auch 0 sein, give/take müssen mindestens 1 sein
        long amount = parseAmount(args[2], ADMIN_MAX);
        boolean zeroAllowed = sub.equals("set") && args[2].trim().equals("0");
        if (amount < 1 && !zeroAllowed) {
            msg.send(sender, "invalid-amount", "max", ADMIN_MAX);
            return;
        }
        amount = Math.max(0, amount);

        String name = db.getName(target.get());
        switch (sub) {
            case "give" -> {
                db.addCoins(target.get(), amount);
                msg.send(sender, "admin-given", "player", name, "coins", amount);
            }
            case "take" -> {
                if (!db.takeCoins(target.get(), amount)) {
                    msg.send(sender, "admin-target-too-poor", "player", name);
                    return;
                }
                msg.send(sender, "admin-taken", "player", name, "coins", amount);
            }
            default -> {
                db.setCoins(target.get(), amount);
                msg.send(sender, "admin-set", "player", name, "coins", amount);
            }
        }
    }

    /** Wandelt Text in eine Zahl um. Gibt -1 zurück, wenn ungültig oder größer als "max". */
    private static long parseAmount(String text, long max) {
        try {
            long value = Long.parseLong(text.trim());
            return (value < 0 || value > max) ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    //  Tab-Vervollständigung (Vorschläge beim Tippen)
    // ------------------------------------------------------------------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        List<String> options = new ArrayList<>();

        if ((name.equals("coins") || name.equals("pay")) && args.length == 1) {
            addOnlineNames(options);
        } else if (name.equals("coinsadmin") && sender.hasPermission("coinshop.admin")) {
            if (args.length == 1) {
                options.addAll(List.of("give", "take", "set", "resetshop", "reload"));
            } else if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase())) {
                addOnlineNames(options);
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        options.removeIf(option -> !option.toLowerCase().startsWith(typed));
        return options;
    }

    private static void addOnlineNames(List<String> options) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            options.add(online.getName());
        }
    }
}
