public class RequestMetrics {
    long startTime;
    String requestType;  // "POST" or "GET"
    long latency;
    int responseCode;

    public RequestMetrics(long startTime, String requestType, long latency, int responseCode) {
        this.startTime = startTime;
        this.requestType = requestType;
        this.latency = latency;
        this.responseCode = responseCode;
    }

    @Override
    public String toString() {
        return startTime + "," + requestType + "," + latency + "," + responseCode;
    }
}

