import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class CrptApi {
    private static final int INITIAL_DELAY = 0;
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 60;
    private static final int TOKEN_EXPIRATION_TIMEOUT = 10 * 60 * 60 * 1000;//10 часов

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CREATE_DOC_URL = "/api/v3/lk/documents/commissioning/contract/create";
    private static final String AUTH_CERT_URL = "/api/v3/auth/cert/";
    private static final String AUTH_CERT_KEY_URL = "/api/v3/auth/cert/key";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final String baseUrl;
    private String authToken;
    private long tokenExpirationTime;

    public CrptApi(TimeUnit timeUnit, int requestLimit, String baseUrl) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.semaphore = new Semaphore(requestLimit);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> semaphore.release(requestLimit - semaphore.availablePermits()),
                INITIAL_DELAY, 1, timeUnit
        );
        this.baseUrl = baseUrl;
    }

    public String createDocument(Document document, String signature) throws Exception {
        semaphore.acquire();

        try {
            String jsonDocument = objectMapper.writeValueAsString(document);
            String token = getValidAuthToken(signature);

            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + CREATE_DOC_URL))
                            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                            .header(HEADER_AUTHORIZATION, BEARER_PREFIX + token)
                            .POST(HttpRequest.BodyPublishers.ofString(jsonDocument))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400)
                throw new RuntimeException("API Error: " + response.statusCode() + " " + response.body());

            return response.body();
        } finally {
            semaphore.release();
        }
    }

    private String getValidAuthToken(String signature) throws Exception {
        if (authToken == null || System.currentTimeMillis() >= tokenExpirationTime) {
            refreshAuthToken(signature);
        }
        return authToken;
    }

    private void refreshAuthToken(String signature) throws Exception {
        HttpResponse<String> keyResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + AUTH_CERT_KEY_URL))
                        .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (keyResponse.statusCode() != 200) {
            throw new RuntimeException("Auth error: status " + keyResponse.statusCode());
        }
        AuthTokenRequest authTokenRequest = objectMapper.readValue(keyResponse.body(), AuthTokenRequest.class);
        String signedData = signDataWithCertificate(authTokenRequest.data, signature);
        authTokenRequest.setData(signedData);

        HttpResponse<String> tokenResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + AUTH_CERT_URL))
                        .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(authTokenRequest)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (tokenResponse.statusCode() != 200) {
            throw new Exception("Auth error: status " + tokenResponse.statusCode());
        }
        this.authToken = objectMapper.readTree(tokenResponse.body()).get("token").asText();
        this.tokenExpirationTime = System.currentTimeMillis() + TOKEN_EXPIRATION_TIMEOUT;
    }

    // Placeholder для подписи (требуется реализация с УКЭП), например через cryptoPro
    private String signDataWithCertificate(String data, String signature) throws Exception {
        throw new UnsupportedOperationException("Подпись УКЭП не реализована");
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Getter
    @Setter
    public static class Document {

        private Description description;
        private String docId;
        private String docStatus;
        private String docType;
        private Boolean importRequest;
        private String ownerInn;
        private String producerInn;
        private String productionDate;
        private String productionType;
        private List<Product> products;
        private String regDate;
        private String regNumber;
    }

    @Getter
    @Setter
    public static class Description {
        private String participantInn;
    }

    @Getter
    @Setter
    public static class Product {
        private String certificateDocument;
        private String certificateDocumentDate;
        private String certificateDocumentNumber;
        private String tnvedCode;
        private String uitCode;
        private String uituCode;
    }

    @Getter
    @Setter
    public static class AuthTokenRequest {
        private String uuid;
        private String data;
    }
}
