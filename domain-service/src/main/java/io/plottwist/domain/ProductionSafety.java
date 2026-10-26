package io.plottwist.domain;

import java.net.URI;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionSafety {
    ProductionSafety(Environment env) {
        if (!"https".equals(URI.create(env.getRequiredProperty("auth.issuer")).getScheme()))
            throw new IllegalStateException("Production issuer must use HTTPS");
        String endpoint = env.getProperty("storage.endpoint", "");
        if (!endpoint.isBlank() && !"https".equals(URI.create(endpoint).getScheme()))
            throw new IllegalStateException("External photo storage must use HTTPS");
        if (env.getProperty("storage.access-key", "").startsWith("GK000000"))
            throw new IllegalStateException("Development S3 credentials are not allowed in production");
    }
}
