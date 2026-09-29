import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Container health check: exits 0 when the readiness probe on the internal management port
 * returns 200. Used because the JRE base image ships no HTTP client tools.
 */
public final class HealthCheck {
  private HealthCheck() {}

  public static void main(String[] args) throws Exception {
    String url = args.length > 0 ? args[0] : "http://127.0.0.1:8081/actuator/health/readiness";
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(2000);
    System.exit(connection.getResponseCode() == 200 ? 0 : 1);
  }
}
