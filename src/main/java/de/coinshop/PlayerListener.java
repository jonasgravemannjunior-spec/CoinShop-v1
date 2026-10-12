package de.coinshop;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Reagiert auf Ereignisse im Spiel:
 *  - Spieler tritt bei        -> Konto anlegen, Hinweise anzeigen (Verkäufe, Einkommen, Abholung)
 *  - Erfolg geschafft         -> Coins als Belohnung (je nach Schwierigkeit)
 *  - Chat-Nachricht           -> Eingaben, auf die das Plugin wartet (Preis, Spielername, Betrag)
 */
public final class PlayerListener implements Listener {

    private final CoinShopPlugin plugin;

    public PlayerListener(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Database db = plugin.database();
        db.ensurePlayer(player.getUniqueId(), player.getName());

        // Eine Sekunde warten, damit die Hinweise nicht in den Beitritts-Meldungen untergehen
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Messages msg = plugin.messages();
            long income = db.popPendingIncome(player.getUniqueId());
            if (income > 0) {
                msg.send(player, "daily-income", "coins", income);
            }
            for (Database.SaleNotice notice : db.popSaleNotices(player.getUniqueId())) {
                msg.send(player, "listing-sold", "amount", notice.amount(),
                        "item", ItemUtil.name(notice.material()), "coins", notice.coins());
            }
            int claims = db.countClaims(player.getUniqueId());
            if (claims > 0) {
                msg.send(player, "claims-waiting", "count", claims);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.prompts().cancel(event.getPlayer());
    }

    /** Coins für Erfolge - je schwieriger der Erfolg, desto mehr. */
    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfig().getBoolean("achievements.enabled", true)) {
            return;
        }
        Advancement advancement = event.getAdvancement();

        // Unsichtbare Fortschritte (z. B. Rezepte-Freischaltungen) zählen nicht
        if (advancement.getDisplay() == null) {
            return;
        }
        // Die "Startseiten" der Erfolgs-Reiter (z. B. minecraft:adventure/root) bekommt man automatisch
        // beim Beitreten - dafür gibt es nichts.
        String path = advancement.getKey().getKey(); // z. B. "story/mine_stone"
        if (path.startsWith("recipes/") || path.equals("root") || path.endsWith("/root")) {
            return;
        }

        AchievementRewards.Reward reward = plugin.rewards().rewardFor(advancement);
        if (reward.coins() <= 0) {
            return;
        }

        Player player = event.getPlayer();
        plugin.database().ensurePlayer(player.getUniqueId(), player.getName());
        plugin.database().addCoins(player.getUniqueId(), reward.coins());
        plugin.messages().send(player, "achievement-reward", "coins", reward.coins(), "tier", reward.tierName());
    }

    /**
     * Fängt die Chat-Nachricht ab, wenn das Plugin gerade eine Eingabe vom Spieler erwartet.
     * Diese Methode läuft NICHT im Haupt-Thread, deshalb übergeben wir die Arbeit mit runTask.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.prompts().isWaiting(player)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.prompts().handle(player, text));
    }
}
