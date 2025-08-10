import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class CrptApi {
    private static final String BASE_URL = "https://api.crpt.ru/v1/documents";
    private static final String CONTENT_TYPE = "application/json";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Semaphore limiter;
    private final ScheduledExecutorService scheduler;
    private final TimeUnit timeUnit;
    private final int requestLimit;

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be > 0");
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.
                        time.
                        Duration.
                        ofSeconds(10))
                .build();
        this.mapper   = new ObjectMapper();

        this.limiter  = new Semaphore(requestLimit);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // Сброс доступных пермиссов каждые `timeUnit` секунды
        scheduler.scheduleAtFixedRate(() -> limiter.release(requestLimit),
                0, timeUnit.toMillis(1), TimeUnit.MILLISECONDS);

        // Хранит самопроизвольный таск чтобы не «разъедать» ресурсы.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> scheduler.shutdownNow()));
    }

    public void createDocument(Document doc, String signature) throws IOException, InterruptedException {
        limiter.acquire();
        try {
            HttpRequest request = buildRequest(doc, signature);
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 400) {
                throw new IOException("API error: " + resp.statusCode() + " - " + resp.body());
            }
        } finally {
            // Периодический запуск для сброса токенов, но сейчас finally ничего не делает –
            // всё контролируется задачей в scheduler
        }
    }

    private HttpRequest buildRequest(Document doc, String signature) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("document", mapper.valueToTree(doc));
        root.put("signature", signature);

        String json = mapper.writeValueAsString(root);

        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", "application/json")
                .header("User-Agent", "CrptApi/1.0")
                .build();
    }

    // Внутренний DTO
    public static final class Document {
        private final String omsId;
        private final String country;
        private final String product;
        private String description;
        private String serialNumber;

        public Document(String omsId, String country, String product) {
            this.omsId = omsId;
            this.country = country;
            this.product = product;
        }

        public String getOmsId() { return omsId; }
        public String getCountry() { return country; }
        public String getProduct() { return product; }
        public String getDescription() { return description; }
        public String getSerialNumber() { return serialNumber; }

        public Document setDescription(String description) { this.description = description; return this; }
        public Document setSerialNumber(String sn) { this.serialNumber = sn; return this; }
    }
}
