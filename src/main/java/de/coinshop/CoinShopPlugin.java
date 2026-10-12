package de.coinshop;

import de.coinshop.gui.MenuListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * Das Hauptprogramm des Plugins. Der Server startet hier:
 * onEnable() läuft beim Einschalten, onDisable() beim Ausschalten.
 */
public final class CoinShopPlugin extends JavaPlugin {

    private Messages messages;
    private Database database;
    private ServerShopManager serverShop;
    private PlayerShopManager playerShop;
    private ChatPrompts prompts;
    private InvestmentManager investments;
    private WheelManager wheel;
    private StockManager stock;
    private AchievementRewards rewards;

    @Override
    public void onEnable() {
        // Legt config.yml an, falls es sie noch nicht gibt (inkl. Plugin-Ordner)
        saveDefaultConfig();

        messages = new Messages(this);
        messages.load();

        try {
            database = new Database(new java.io.File(getDataFolder(), "coinshop.db"), getLogger());
        } catch (SQLException e) {
            getLogger().severe("Datenbank konnte nicht geöffnet werden: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        prompts = new ChatPrompts();
        investments = new InvestmentManager(this);
        wheel = new WheelManager(this);
        stock = new StockManager(this);
        rewards = new AchievementRewards(this);
        serverShop = new ServerShopManager(this);
        playerShop = new PlayerShopManager(this);

        // Auf Ereignisse im Spiel reagieren (Beitreten, Erfolge, Menü-Klicks, Chat)
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Befehle verbinden (die Namen stehen in plugin.yml)
        CoinCommands coinCommands = new CoinCommands(this);
        for (String name : new String[]{"coins", "pay", "baltop", "coinsadmin"}) {
            register(name, coinCommands, coinCommands);
        }
        ShopCommand shopCommand = new ShopCommand(this);
        register("shop", shopCommand, shopCommand);

        // Server-Shop beim Start prüfen und danach alle 30 Sekunden (Wechsel um 12:00 Uhr)
        serverShop.checkReset();
        getServer().getScheduler().runTaskTimer(this, serverShop::checkReset, 20L * 30, 20L * 30);

        // Abgelaufene Spieler-Angebote jede Minute einsammeln
        getServer().getScheduler().runTaskTimer(this, playerShop::processExpired, 20L * 60, 20L * 60);

        getLogger().info("CoinShop wurde gestartet.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor,
                          org.bukkit.command.TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Befehl '" + name + "' fehlt in der plugin.yml!");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    /** Lädt config.yml und messages.yml neu (/coinsadmin reload). */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        investments.reload();
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public ServerShopManager serverShop() {
        return serverShop;
    }

    public PlayerShopManager playerShop() {
        return playerShop;
    }

    public ChatPrompts prompts() {
        return prompts;
    }

    public InvestmentManager investments() {
        return investments;
    }

    public WheelManager wheel() {
        return wheel;
    }

    public StockManager stock() {
        return stock;
    }

    public AchievementRewards rewards() {
        return rewards;
    }
}
