package dev.gotiger.gTDonationChzzk.listener;

import dev.gotiger.gTDonationChzzk.GTDonationChzzk;
import dev.gotiger.gTDonationChzzk.config.ConfigLoader;
import dev.gotiger.gTDonationChzzk.config.CustomChzzkOauthLoginAdapter;
import dev.gotiger.gTDonationCore.api.GTDonationAPI;
import dev.gotiger.gTDonationCore.enums.Platform;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.r2turntrue.chzzk4j.ChzzkClient;
import xyz.r2turntrue.chzzk4j.ChzzkClientBuilder;
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginAdapter;
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginResult;
import xyz.r2turntrue.chzzk4j.auth.ChzzkSimpleUserLoginAdapter;
import xyz.r2turntrue.chzzk4j.session.ChzzkSessionBuilder;
import xyz.r2turntrue.chzzk4j.session.ChzzkSessionSubscriptionType;
import xyz.r2turntrue.chzzk4j.session.ChzzkUserSession;
import xyz.r2turntrue.chzzk4j.session.event.SessionChatMessageEvent;
import xyz.r2turntrue.chzzk4j.session.event.SessionDonationEvent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerListener implements Listener {

    private final GTDonationChzzk plugin;
    private final ConfigLoader configLoader;
    private final CustomChzzkOauthLoginAdapter sharedAdapter;
    private final Map<UUID, ChzzkUserSession> activeSessions = new ConcurrentHashMap<>();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm");

    public PlayerListener(GTDonationChzzk plugin, ConfigLoader configLoader, CustomChzzkOauthLoginAdapter sharedAdapter) {
        this.plugin = plugin;
        this.configLoader = configLoader;
        this.sharedAdapter = sharedAdapter;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = DriverManager.getConnection(plugin.getDBUrl())) {
                PreparedStatement checkStmt = conn.prepareStatement("SELECT * FROM PLAYERS WHERE uuid = ?");
                checkStmt.setString(1, uuid.toString());
                ResultSet rs = checkStmt.executeQuery();

                if (!rs.next()) {
                    handleFirstLogin(player, uuid);
                } else {
                    handleExistingLogin(player, uuid, conn, rs);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("❌ 토큰 처리 중 오류 발생: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        disconnectSession(event.getPlayer().getUniqueId());
    }

    public void disconnectSession(UUID uuid) {
        ChzzkUserSession session = activeSessions.remove(uuid);
        if (session != null) {
            session.disconnectAsync().join();
            plugin.getLogger().info("[CHZZK] 세션 종료됨: " + uuid);

            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                GTDonationAPI.callConnectionEvent(player, Platform.CHZZK, false);
            }
        }
    }

    private void handleFirstLogin(Player player, UUID uuid) {
        String state = uuid.toString();
        String url = sharedAdapter.getAccountInterlockUrl(
                configLoader.getClientId(),
                configLoader.isHttps(),
                state,
                configLoader.getDisplayHost()
        );

        TextComponent message = new TextComponent("§e[CHZZK] 로그인 링크를 클릭하세요: ");
        TextComponent link = new TextComponent("여기를 클릭");
        link.setColor(ChatColor.AQUA);
        link.setBold(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        message.addExtra(link);

        Bukkit.getScheduler().runTask(plugin, () -> player.spigot().sendMessage(message));

        sharedAdapter.authorize(configLoader.getClientId(), configLoader.getClientSecret(), state).thenAccept(result -> {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection conn = DriverManager.getConnection(plugin.getDBUrl())) {
                    updateTokensInDb(conn, uuid, result);
                    ChzzkLoginAdapter loginAdapter = new ChzzkSimpleUserLoginAdapter(result.accessToken(), result.refreshToken());
                    ChzzkClient client = new ChzzkClientBuilder(configLoader.getClientId(), configLoader.getClientSecret())
                            .withLoginAdapter(loginAdapter)
                            .build();
                    client.loginAsync().join();
                    setupUserSession(player, uuid, client);
                } catch (Exception e) {
                    plugin.getLogger().severe("❌ 인증 후 세션 연결 실패: " + e.getMessage());
                }
            });
        });
    }

    private void handleExistingLogin(Player player, UUID uuid, Connection conn, ResultSet rs) throws Exception {
        String refreshToken = rs.getString("refreshToken");
        String accessToken = rs.getString("accessToken");
        String refreshDateStr = rs.getString("refreshDate");

        LocalDateTime refreshDate = LocalDateTime.parse(refreshDateStr, FORMATTER);
        long daysSinceRefresh = ChronoUnit.DAYS.between(refreshDate, LocalDateTime.now());

        if (daysSinceRefresh > 29) {
            initiateReauthentication(player, uuid);
            return;
        }

        ChzzkLoginAdapter adapter = new ChzzkSimpleUserLoginAdapter(accessToken, refreshToken);
        ChzzkClient client = new ChzzkClientBuilder(configLoader.getClientId(), configLoader.getClientSecret())
                .withLoginAdapter(adapter)
                .build();
        try {
            client.loginAsync().join();
            client.refreshTokenAsync().join();
        } catch (Exception e) {
            plugin.getLogger().warning("[CHZZK] 토큰 갱신 실패 (401), 재인증 요청: " + player.getName());
            initiateReauthentication(player, uuid);
            return;
        }

        ChzzkLoginResult result = client.getLoginResult();
        if (result != null) {
            updateTokensInDb(conn, uuid, result);
            setupUserSession(player, uuid, client);
        } else {
            initiateReauthentication(player, uuid);
        }
    }

    private static final int SESSION_RETRY_COUNT = 1;
    private static final long SESSION_RETRY_DELAY_MS = 2000;

    private void setupUserSession(Player player, UUID uuid, ChzzkClient client) {
        setupUserSession(player, uuid, client, 0);
    }

    private void setupUserSession(Player player, UUID uuid, ChzzkClient client, int attempt) {
        try {
            ChzzkUserSession session = new ChzzkSessionBuilder(client)
                    .withAutoRecreate(true)
                    .buildUserSession();

            session.on(SessionChatMessageEvent.class, event -> {
                if (configLoader.isDebug()) {
                    plugin.getLogger().info("[CHZZK] 채팅 수신: " + event.getMessage().getProfile().getNickname()
                            + " -> " + event.getMessage().getContent());
                }
                Bukkit.getScheduler().runTask(plugin, () -> GTDonationAPI.callChatEvent(
                        player,
                        event.getMessage().getProfile().getNickname(),
                        event.getMessage().getContent(),
                        Platform.CHZZK
                ));
            });

            session.on(SessionDonationEvent.class, event -> {
                if (configLoader.isDebug()) {
                    plugin.getLogger().info("[CHZZK] 후원 수신: " + event.getMessage().getDonatorNickname()
                            + " -> " + event.getMessage().getPayAmount());
                }
                Bukkit.getScheduler().runTask(plugin, () -> GTDonationAPI.callDonationEvent(
                        player,
                        event.getMessage().getDonatorNickname(),
                        event.getMessage().getPayAmount(),
                        event.getMessage().getDonationText(),
                        Platform.CHZZK
                ));
            });

            session.createAndConnectAsync().join();
            activeSessions.put(uuid, session);
            session.subscribeAsync(ChzzkSessionSubscriptionType.CHAT).join();
            session.subscribeAsync(ChzzkSessionSubscriptionType.DONATION).join();

            plugin.getLogger().info("[CHZZK] " + player.getName() + " 세션 연결됨");
            player.sendMessage("§a[CHZZK] 인증이 완료되었습니다.");
            Bukkit.getScheduler().runTask(plugin, () ->
                    GTDonationAPI.callConnectionEvent(player, Platform.CHZZK, true));

        } catch (Exception e) {
            if (attempt < SESSION_RETRY_COUNT) {
                plugin.getLogger().warning("[CHZZK] 세션 생성 실패, " + (SESSION_RETRY_DELAY_MS / 1000) + "초 후 재시도: " + e.getMessage());
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                        () -> setupUserSession(player, uuid, client, attempt + 1),
                        SESSION_RETRY_DELAY_MS / 50);
            } else {
                plugin.getLogger().severe("❌ 세션 생성 실패: " + e.getMessage());
                player.sendMessage("§c[CHZZK] 세션 생성 중 오류가 발생했습니다.");
            }
        }
    }

    private void updateTokensInDb(Connection conn, UUID uuid, ChzzkLoginResult result) throws Exception {
        String now = LocalDateTime.now().format(FORMATTER);
        String sql = "INSERT OR REPLACE INTO PLAYERS" +
                " (uuid, refreshToken, accessToken, refreshDate, accessDate) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, result.refreshToken());
            stmt.setString(3, result.accessToken());
            stmt.setString(4, now);
            stmt.setString(5, now);
            stmt.executeUpdate();
        }
    }

    private void initiateReauthentication(Player player, UUID uuid) {
        String state = uuid.toString();
        String url = sharedAdapter.getAccountInterlockUrl(
                configLoader.getClientId(),
                configLoader.isHttps(),
                state,
                configLoader.getDisplayHost()
        );

        TextComponent msg = new TextComponent("§e[CHZZK] 인증이 만료되었습니다. ");
        TextComponent link = new TextComponent("다시 인증하려면 클릭하세요.");
        link.setColor(ChatColor.AQUA);
        link.setBold(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        msg.addExtra(link);

        Bukkit.getScheduler().runTask(plugin, () -> player.spigot().sendMessage(msg));

        sharedAdapter.authorize(configLoader.getClientId(), configLoader.getClientSecret(), state).thenAccept(result -> {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection conn = DriverManager.getConnection(plugin.getDBUrl())) {
                    updateTokensInDb(conn, uuid, result);
                    ChzzkLoginAdapter loginAdapter = new ChzzkSimpleUserLoginAdapter(result.accessToken(), result.refreshToken());
                    ChzzkClient newClient = new ChzzkClientBuilder(configLoader.getClientId(), configLoader.getClientSecret())
                            .withLoginAdapter(loginAdapter)
                            .build();
                    newClient.loginAsync().join();
                    setupUserSession(player, uuid, newClient);
                } catch (Exception e) {
                    plugin.getLogger().severe("❌ 재인증 후 세션 연결 실패: " + e.getMessage());
                }
            });
        });
    }

    public boolean isLinked(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(plugin.getDBUrl())) {
            PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM PLAYERS WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    public void unregister(UUID uuid) {
        try (Connection conn = DriverManager.getConnection(plugin.getDBUrl())) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM PLAYERS WHERE uuid = ?");
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().severe("❌ 연동 해제 실패: " + e.getMessage());
        }
    }
}
