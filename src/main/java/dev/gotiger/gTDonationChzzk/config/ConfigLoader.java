package dev.gotiger.gTDonationChzzk.config;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

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
        File configFile = new File(dataFolder, "config.json");
        if (!configFile.exists()) {
            applyDefaults();
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JSONObject json = new JSONObject(sb.toString());
            clientId = json.optString("CLIENT_ID", "");
            clientSecret = json.optString("CLIENT_SECRET", "");
            port = json.optInt("port", 20154);
            https = json.optBoolean("https", false);
            displayHost = json.optString("displayHost", "localhost");
            callbackPath = json.optString("callbackPath", "/callback");
            debug = json.optBoolean("debug", false);
        } catch (Exception e) {
            e.printStackTrace();
            applyDefaults();
        }
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
