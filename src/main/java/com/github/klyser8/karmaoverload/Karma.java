package com.github.klyser8.karmaoverload;

import com.github.klyser8.karmaoverload.commands.*;
import com.github.klyser8.karmaoverload.commands.storage.ReloadCommand;
import com.github.klyser8.karmaoverload.commands.storage.SaveCommand;
import com.github.klyser8.karmaoverload.karma.*;
import com.github.klyser8.karmaoverload.karma.worldguard.FlagListener;
import com.github.klyser8.karmaoverload.karma.worldguard.KarmaFlags;
import com.github.klyser8.karmaoverload.language.LanguageHandler;
import com.github.klyser8.karmaoverload.storage.*;
import me.mattstudios.mf.base.CommandManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.klyser8.karmaoverload.util.RandomUtil.debugMessage;

public final class Karma extends JavaPlugin {

    public static final String VERSION = Bukkit.getVersion();

    private final List<Alignment> alignments;

    private Connection connection;

    private final Preferences preferences;
    private final ProfileProvider profileProvider;
    private final ProfileWriter profileWriter;
    private final AlignmentFactory alignmentFactory;
    private final LanguageHandler languageHandler;

    private final KarmaLimitRunnable limitRunnable;

    private final Map<Player, Double> karmaLimitMap;

    public Karma() {
        preferences = new Preferences(this);
        languageHandler = new LanguageHandler(this);
        profileProvider = new ProfileProvider(this);
        alignmentFactory = new AlignmentFactory(this);
        alignments = new ArrayList<>();
        profileWriter = new ProfileWriter(this);
        karmaLimitMap = new HashMap<>();
        limitRunnable = new KarmaLimitRunnable();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        alignmentFactory.setup();
        preferences.loadPreferences();
        if (preferences.getStorageType() == Preferences.MYSQL_STORAGE) {
            connectMySQLDB();
        }
        languageHandler.setup();
        CommandManager commandManager = new CommandManager(this, true);
        //Register commands
        commandManager.register(new AddKarmaCommand(this), new ClearKarmaCommand(this), new HelpCommand(this), new ListCommand(this),
                new RemoveKarmaCommand(this), new ResetKarmaCommand(this), new SetKarmaCommand(this), new ViewKarmaCommand(this),
                new ReloadCommand(this), new SaveCommand(this), new VersionCommand(this), new HistoryCommand(this));
        //Register events
        Bukkit.getPluginManager().registerEvents(new StorageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new EffectListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ActionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LoggingListener(this), this);
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) Bukkit.getPluginManager().registerEvents(new FlagListener(), this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            profileProvider.createProfile(player);
        }
        profileWriter.startAutoSave();
        limitRunnable.runTaskTimerAsynchronously(this, 20, 20);
    }

    @Override
    public void onLoad() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) KarmaFlags.registerAll();
    }

    @Override
    public void onDisable() {
        profileWriter.stopAutoSave();
        for (Player player : Bukkit.getOnlinePlayers()) {
            profileWriter.saveProfileGson(profileProvider.getProfile(player));
        }
        limitRunnable.cancel();
    }

    public void reloadDB() {
        if (preferences.getStorageType() == Preferences.MYSQL_STORAGE) {
            connectMySQLDB();
            createDBTables();
        }
    }

    private void connectMySQLDB() {
        try {
            connection = DriverManager.getConnection("jdbc:mysql://" + preferences.getHost() + ":" + preferences.getPort() + "/" + preferences.getDbName() + "?useSSL=false",
                    preferences.getUser(), preferences.getPassword());
            debugMessage(this, "Connected to database: " + preferences.getDbName(), DebugLevel.LOW);
        } catch (SQLException throwable) {
            throwable.printStackTrace();
        }
    }

    private void createDBTables() {
        String queryExists = "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '<TABLE_NAME>' AND TABLE_SCHEMA = '" + preferences.getDbName() + "';";
        String createKarmaTableQuery = "CREATE TABLE `KarmaTable` (" +
                "`UUID` VARCHAR(50) NOT NULL COLLATE `utf8_general_ci`," +
                "`Score` DOUBLE(22,1) NULL DEFAULT " + preferences.getStartingScore() + "," +
                "UNIQUE INDEX `UUID` (`UUID`) USING BTREE" +
                ")" +
                "COLLATE='utf8_general_ci'" +
                "ENGINE=InnoDB" +
                ";";
        String createKarmaHistoryQuery = "CREATE TABLE `KarmaHistory` (" +
                "`UUID` VARCHAR(50) NOT NULL COLLATE `latin1_swedish_ci`," +
                "`EventTime` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "`Source` VARCHAR(50) NOT NULL COLLATE `latin1_swedish_ci`," +
                "`Change` DOUBLE(22,1) NOT NULL" +
                ")" +
                "COLLATE='utf8_general_ci'" +
                ";";
        try {
            queryExists = queryExists.replace("<TABLE_NAME>", "KarmaTable");
            PreparedStatement statement = connection.prepareStatement(queryExists);
            ResultSet resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                statement = connection.prepareStatement(createKarmaTableQuery);
                statement.executeUpdate();
                debugMessage(this, "Karma Table does not exist, creating...", DebugLevel.LOW);
            } else {
                debugMessage(this, "Karma Table already exists.", DebugLevel.LOW);
            }
            queryExists = queryExists.replace("KarmaTable", "KarmaHistory");
            statement = connection.prepareStatement(queryExists);
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                statement = connection.prepareStatement(createKarmaHistoryQuery);
                statement.executeUpdate();
                debugMessage(this, "Karma History does not exist, creating...", DebugLevel.LOW);
            } else {
                debugMessage(this, "Karma History already exists.", DebugLevel.LOW);
            }
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
    }

    public AlignmentFactory getAlignmentFactory() {
        return alignmentFactory;
    }

    public List<Alignment> getAlignments() {
        return alignments;
    }

    public LanguageHandler getLanguageHandler() {
        return languageHandler;
    }

    public ProfileProvider getProfileProvider() {
        return profileProvider;
    }

    public Preferences getPreferences() {
        return preferences;
    }

    public ProfileWriter getProfileWriter() {
        return profileWriter;
    }

    public Connection getConnection() {
        return connection;
    }

    public Map<Player, Double> getKarmaLimitMap() {
        return karmaLimitMap;
    }

    private class KarmaLimitRunnable extends BukkitRunnable {

        private int count = 0;

        @Override
        public void run() {
            count++;
            if (count < getPreferences().getKarmaLimitResetInterval()) return;
            List<Player> playerList = new ArrayList<>(karmaLimitMap.keySet());
            for (Player player : playerList) {
                if (!player.isOnline()) karmaLimitMap.remove(player);
                else karmaLimitMap.put(player, 0.0);
            }
            count = 0;
            debugMessage(Karma.this, "Player recent karma limit has been reset", DebugLevel.LOW);
        }
    }
}
