// Compiled during the builder stage; requires no curl/wget in the runtime image.
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
class Healthcheck {
    public static void main(String[] args) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health")).timeout(Duration.ofSeconds(5)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
        } catch (Exception e) { System.exit(1); }
    }
}
