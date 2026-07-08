package dev.gotiger.gTDonationChzzk.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class ConfigLoader {
    private final File dataFolder;
    private String clientId;
    private String clientSecret;
    private int port;
    private boolean https;
    private String displayHost;
    private String callbackPath;
    private boolean debug;

    public ConfigLoader(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void load() {
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            applyDefaults();
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        clientId = config.getString("CLIENT_ID", "");
        clientSecret = config.getString("CLIENT_SECRET", "");
        port = config.getInt("port", 20154);
        https = config.getBoolean("https", false);
        displayHost = config.getString("displayHost", "localhost");
        callbackPath = config.getString("callbackPath", "/callback");
        debug = config.getBoolean("debug", false);
    }

    private void applyDefaults() {
        clientId = "";
        clientSecret = "";
        port = 20154;
        https = false;
        displayHost = "localhost";
        callbackPath = "/callback";
        debug = false;
    }

    public String getClientId() { return clientId; }
    public String getClientSecret() { return clientSecret; }
    public int getPort() { return port; }
    public boolean isHttps() { return https; }
    public String getDisplayHost() { return displayHost; }
    public String getCallbackPath() { return callbackPath; }
    public boolean isDebug() { return debug; }
}
