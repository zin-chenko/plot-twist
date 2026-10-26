package io.plottwist.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionSafety implements ApplicationRunner {
    private final Environment env;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;

    ProductionSafety(Environment env, JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.env = env; this.jdbc = jdbc; this.passwords = passwords;
        validate(env);
    }

    static void validate(Environment env) {
        if (!"https".equals(URI.create(env.getRequiredProperty("auth.issuer")).getScheme()))
            throw new IllegalStateException("Production issuer must use HTTPS");
        for (String flag : new String[]{"auth.bootstrap.enabled", "auth.machine-client.enabled", "auth.auto-create-key"})
            if (env.getProperty(flag, Boolean.class, false)) throw new IllegalStateException("Development auth switches must be disabled in production");
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false))
            throw new IllegalStateException("Production login cookies must be Secure");
        String smtp = env.getRequiredProperty("spring.mail.host");
        if (smtp.isBlank() || smtp.equals("mailpit") || smtp.equals("localhost"))
            throw new IllegalStateException("Configure a production SMTP relay");
        if (!env.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class, false)
                && !env.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class, false))
            throw new IllegalStateException("Production SMTP must require TLS");
        if (env.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class, true)
                && (env.getProperty("spring.mail.username", "").isBlank() || env.getProperty("spring.mail.password", "").isBlank()))
            throw new IllegalStateException("SMTP authentication credentials are missing");
        if (!Files.isReadable(Path.of(env.getRequiredProperty("auth.signing-key"))))
            throw new IllegalStateException("A readable externally provisioned signing key is required");
    }

    @Override
    public void run(ApplicationArguments args) {
        var demos = jdbc.queryForList("SELECT password FROM users WHERE username = 'demo' AND enabled = true", String.class);
        if (!demos.isEmpty() && passwords.matches("local-demo-password", demos.getFirst()))
            throw new IllegalStateException("Disable the development demo account before production");
        var clients = jdbc.queryForList("SELECT client_secret FROM oauth2_registered_client WHERE client_id = 'plot-twist-cli'", String.class);
        if (!clients.isEmpty()) throw new IllegalStateException("Remove the reserved development machine client before production");
    }
}
