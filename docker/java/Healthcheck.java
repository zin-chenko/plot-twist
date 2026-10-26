import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

public final class Healthcheck {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        int exit = 1;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/actuator/health/readiness")
                .toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() == 200) exit = 0;
        } catch (Exception unavailable) {
            // Keep health output free of response bodies, tokens and credentials.
        } finally {
            if (connection != null) connection.disconnect();
        }
        System.exit(exit);
    }
}
