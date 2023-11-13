import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SimpleClient {
    public static final boolean DEBUG = true;
    public static final AtomicInteger SUCCESSFUL_REQUESTS = new AtomicInteger(0);
    public static final AtomicInteger FAILED_NONE_OK = new AtomicInteger(0);
    public static final AtomicInteger FAILED_WITH_EXCEPTION = new AtomicInteger(0);
    public static final Queue<RequestMetrics> METRICS_LIST = new ConcurrentLinkedQueue<>();
    public static ObjectMapper objectMapper = new ObjectMapper();
    private static void debugPrintln(String message) {
        if (DEBUG) {
            System.out.println(message);
        }
    }
    public static class AlbumResponse {
        private String albumId;
        private int imageSize;

        // getters and setters
        public String getAlbumId() {
            return albumId;
        }

        public void setAlbumId(String albumId) {
            this.albumId = albumId;
        }

        public int getImageSize() {
            return imageSize;
        }

        public void setImageSize(int imageSize) {
            this.imageSize = imageSize;
        }
    }

    static byte[] imageBytes = new byte[3457];

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 4) {
            System.out.println("Usage: SimpleClient <threadGroupSize> <numThreadGroups> <delay> <IPAddr>");
            return;
        }

        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(imageBytes);

        int threadGroupSize = Integer.parseInt(args[0]);
        int numThreadGroups = Integer.parseInt(args[1]);
        int delay = Integer.parseInt(args[2]);
        String IPAddr = args[3];

        // sendRequests(IPAddr, 1);

        // Initialization phase with 10 threads
        ExecutorService initializationExecutor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 10; i++) {
            initializationExecutor.submit(() -> sendRequests(IPAddr, 100));
        }
        initializationExecutor.shutdown();
        initializationExecutor.awaitTermination(1, TimeUnit.HOURS);

        long startTime = System.currentTimeMillis();

        ExecutorService executor = Executors.newFixedThreadPool(numThreadGroups * threadGroupSize);
        for (int i = 0; i < numThreadGroups; i++) {
            for (int j = 0; j < threadGroupSize; j++) {
                executor.submit(() -> sendPairRequest(IPAddr, 1000));
            }
            Thread.sleep(delay * 1000);
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);

        long endTime = System.currentTimeMillis();

        System.out.println("====================================================");
        System.out.println("Execution started at: " + startTime);
        System.out.println("Execution ended at: " + endTime);
        System.out.println("====================================================");
        System.out.println("Total execution time (sec): " + (endTime - startTime) / 1000);
        System.out.println("Total throughput (reqs/sec): " + 1000 * (SUCCESSFUL_REQUESTS.get()) / (endTime - startTime));
        System.out.println("====================================================");
        System.out.println("Total successful total: " + SUCCESSFUL_REQUESTS.get());
        System.out.println("Total failed non-200 total: " + FAILED_NONE_OK.get());
        System.out.println("Total failed with exception total: " + FAILED_NONE_OK.get());
        System.out.println("====================================================");
        Metrics analyzer = new Metrics(METRICS_LIST);
        analyzer.computeAndPrintStats("POST");
        analyzer.computeAndPrintStats("GET");
        // analyzer.saveMetricsToFile("metrics.csv");
    }

    private static void sendRequests(String baseUri, int pairs) {
        String albumID = sendPostRequest(baseUri);
        for (int i = 0; i < pairs; i++) {
            sendGetRequest(baseUri + "/" + albumID);
        }
    }

    private static void sendPairRequest(String baseUri, int pairs) {
        for (int i = 0; i < pairs; i++) {
            String albumID = sendPostRequest(baseUri);
            sendGetRequest(baseUri + "/" + albumID);
        }
    }

    private static String sendPostRequest(String targetUrl) {
        String jsonProfile = "{\"artist\": \"ximing1\", \"title\": \"dora\",\"year\": \"2014\"}";

        String boundary = Long.toHexString(System.currentTimeMillis()); // Just generate some unique random value.
        String CRLF = "\r\n"; // Line separator required by multipart/form-data.

        try {
            long start = System.currentTimeMillis();
            URL url = new URL(targetUrl);
            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();

            httpURLConnection.setDoOutput(true);
            httpURLConnection.setRequestMethod("POST");
            httpURLConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream output = httpURLConnection.getOutputStream();
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, "UTF-8"), true)) {

                // Send image data.
                writer.append("--" + boundary).append(CRLF);
                writer.append("Content-Disposition: form-data; name=\"image\"; filename=\"image.jpg\"").append(CRLF);
                writer.append("Content-Type: image/jpeg").append(CRLF);  // Adjust the content type if your image isn't a JPEG
                writer.append(CRLF).flush();
                output.write(imageBytes);
                output.flush();
                writer.append(CRLF).flush();

                // Send JSON profile data.
                writer.append("--" + boundary).append(CRLF);
                writer.append("Content-Disposition: form-data; name=\"profile\"").append(CRLF);
                writer.append("Content-Type: application/json; charset=UTF-8").append(CRLF);
                writer.append(CRLF).append(jsonProfile).append(CRLF).flush();

                // End of multipart/form-data.
                writer.append("--" + boundary + "--").append(CRLF).flush();
            }

            int responseCode = httpURLConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                SUCCESSFUL_REQUESTS.incrementAndGet();
                BufferedReader in = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;

                while ((line = in.readLine()) != null) {
                    response.append(line);
                }
                in.close();

                AlbumResponse albumResponse = objectMapper.readValue(response.toString(), AlbumResponse.class);

//                long end = System.currentTimeMillis();
//                METRICS_LIST.add(new RequestMetrics(start, "POST", end - start, responseCode));

                debugPrintln(Thread.currentThread().getName() + " - POST request to " + targetUrl + " succeeded! AlbumId: " + albumResponse.getAlbumId());
                return albumResponse.getAlbumId();
            } else {
                FAILED_NONE_OK.incrementAndGet();
                System.out.println(Thread.currentThread().getName() + " - POST request to " + targetUrl + " failed! Response code: " + responseCode);
                return "";
            }
        } catch (Exception e) {
            FAILED_WITH_EXCEPTION.incrementAndGet();
            System.out.println(Thread.currentThread().getName() + " - POST request to " + targetUrl + " Error: " + e.getMessage());
            return "";
        }
    }


    private static void sendGetRequest(String targetUrl) {
        try {
            long start = System.currentTimeMillis();
            URL url = new URL(targetUrl);
            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.setRequestProperty("User-Agent", "Mozilla/5.0");

            int responseCode = httpURLConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                SUCCESSFUL_REQUESTS.incrementAndGet();
                BufferedReader in = new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
                StringBuffer response = new StringBuffer();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
//                debugPrintln(Thread.currentThread().getName() + " - GET request to " + targetUrl + " succeeded! Response: " + response.toString());
//                long end = System.currentTimeMillis();
//                METRICS_LIST.add(new RequestMetrics(start, "GET", end - start, responseCode));
            } else {
                FAILED_NONE_OK.incrementAndGet();
                System.out.println(Thread.currentThread().getName() + " - GET request to " + targetUrl + " failed! Response code: " + responseCode);
            }
        } catch (Exception e) {
            FAILED_WITH_EXCEPTION.incrementAndGet();
            System.out.println(Thread.currentThread().getName() + " - GET request to " + targetUrl + " Error: " + e.getMessage());
        }
    }
}