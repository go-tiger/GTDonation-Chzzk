package dev.gotiger.gTDonationChzzk.config;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginAdapter;
import xyz.r2turntrue.chzzk4j.auth.ChzzkLoginResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class CustomChzzkOauthLoginAdapter implements ChzzkLoginAdapter {

    private static HttpServer server;
    private static final Map<String, CompletableFuture<ChzzkLoginResult>> stateMap = new ConcurrentHashMap<>();

    private final String bindHost;
    private final int port;
    private final boolean https;
    private final String callbackPath;
    private final String displayHost;

    private String clientId;
    private String clientSecret;

    public CustomChzzkOauthLoginAdapter(String bindHost, int port, boolean https, String callbackPath, String displayHost) {
        this.bindHost = bindHost;
        this.port = port;
        this.https = https;
        this.callbackPath = callbackPath.startsWith("/") ? callbackPath : "/" + callbackPath;
        this.displayHost = displayHost;
    }

    public void setClientCredentials(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void startServerOnce() {
        if (server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            server.createContext(callbackPath, exchange -> {
                try {
                    if (!"GET".equals(exchange.getRequestMethod())) {
                        exchange.sendResponseHeaders(405, -1);
                        return;
                    }

                    Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                    String code = params.get("code");
                    String state = params.get("state");

                    CompletableFuture<ChzzkLoginResult> future = stateMap.remove(state);
                    String html;
                    int status;

                    if (future != null) {
                        try {
                            ChzzkLoginResult result = requestToken(clientId, clientSecret, code, state, getRedirectUri());
                            future.complete(result);
                            html = "<html><body><h1>인증이 완료되었습니다.</h1></body></html>";
                            status = 200;
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                            html = "<html><body><h1 style='color:red;'>인증 실패</h1></body></html>";
                            status = 500;
                        }
                    } else {
                        html = "<html><body><h1 style='color:red;'>잘못된 state</h1></body></html>";
                        status = 400;
                    }

                    byte[] respBytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(status, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } finally {
                    exchange.close();
                }
            });

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("[CHZZK] 인증 서버 시작 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<ChzzkLoginResult> authorize(xyz.r2turntrue.chzzk4j.ChzzkClient client) {
        throw new UnsupportedOperationException("authorize(ChzzkClient)는 사용하지 않습니다.");
    }

    public CompletableFuture<ChzzkLoginResult> authorize(String clientId, String clientSecret, String state) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        CompletableFuture<ChzzkLoginResult> future = new CompletableFuture<>();
        stateMap.put(state, future);
        return future;
    }

    public String getAccountInterlockUrl(String clientId, boolean httpsFlag, String state, String displayHost) {
        try {
            String scheme = httpsFlag ? "https" : "http";
            String redirectUri = scheme + "://" + displayHost + callbackPath;
            String encodedRedirectUri = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
            return "https://chzzk.naver.com/account-interlock" +
                    "?response_type=code" +
                    "&clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&redirectUri=" + encodedRedirectUri +
                    "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("URL 생성 실패", e);
        }
    }

    private String getRedirectUri() {
        String scheme = https ? "https" : "http";
        return scheme + "://" + displayHost + callbackPath;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new ConcurrentHashMap<>();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=");
            if (parts.length == 2) {
                map.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    public static ChzzkLoginResult requestToken(String clientId, String clientSecret, String code, String state, String redirectUri) throws Exception {
        String url = "https://openapi.chzzk.naver.com/auth/v1/token";

        JSONObject payload = new JSONObject();
        payload.put("grantType", "authorization_code");
        payload.put("clientId", clientId);
        payload.put("clientSecret", clientSecret);
        payload.put("code", code);
        payload.put("state", state);
        payload.put("redirectUri", redirectUri);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode == 200 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();

        JSONObject json = new JSONObject(response.toString());
        JSONObject content = json.getJSONObject("content");

        return new ChzzkLoginResult(
                null,
                null,
                content.getString("accessToken"),
                content.getString("refreshToken"),
                content.optInt("expiresIn", 86400)
        );
    }
}
