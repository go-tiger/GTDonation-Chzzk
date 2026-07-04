package dev.gotiger.gTDonationChzzk;

import dev.gotiger.gTDonationChzzk.command.ChzzkCommand;
import dev.gotiger.gTDonationChzzk.config.ConfigLoader;
import dev.gotiger.gTDonationChzzk.config.CustomChzzkOauthLoginAdapter;
import dev.gotiger.gTDonationChzzk.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class GTDonationChzzk extends JavaPlugin {

    private ConfigLoader configLoader;
    private CustomChzzkOauthLoginAdapter sharedAdapter;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.json");
        if (!configFile.exists()) {
            saveResource("config.json", false);
        }

        configLoader = new ConfigLoader(getDataFolder());
        configLoader.load();

        initDatabase();

        sharedAdapter = new CustomChzzkOauthLoginAdapter(
                "0.0.0.0",
                configLoader.getPort(),
                configLoader.isHttps(),
                configLoader.getCallbackPath(),
                configLoader.getDisplayHost()
        );
        sharedAdapter.setClientCredentials(configLoader.getClientId(), configLoader.getClientSecret());
        sharedAdapter.startServerOnce();

        PlayerListener playerListener = new PlayerListener(this, configLoader, sharedAdapter);
        Bukkit.getPluginManager().registerEvents(playerListener, this);

        ChzzkCommand chzzkCommand = new ChzzkCommand(this, playerListener);
        getCommand("chzzk").setExecutor(chzzkCommand);
        getCommand("chzzk").setTabCompleter(chzzkCommand);

        getLogger().info("GTDonationChzzk 플러그인 활성화 완료");
    }

    @Override
    public void onDisable() {
        getLogger().info("GTDonationChzzk 플러그인 비활성화 완료");
    }

    public String getDBUrl() {
        File dbFile = new File(getDataFolder(), "tokens.db");
        return "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    private void initDatabase() {
        String sql = """
        CREATE TABLE IF NOT EXISTS PLAYERS (
            uuid TEXT PRIMARY KEY,
            refreshToken TEXT,
            accessToken TEXT,
            refreshDate TEXT,
            accessDate TEXT
        );
        """;

        try (Connection conn = DriverManager.getConnection(getDBUrl());
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
            getLogger().info("✅ DB 테이블 생성 확인 완료: PLAYERS");
        } catch (Exception e) {
            getLogger().severe("❌ DB 초기화 실패: " + e.getMessage());
        }
    }
}
