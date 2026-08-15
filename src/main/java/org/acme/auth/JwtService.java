package org.acme.auth;

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class JwtService {

    public String generateToken(UUID userId, String username) {
        return Jwt.issuer("https://chat-app")
                .subject(userId.toString())
                .upn(username)
                .groups(Set.of("user"))
                .expiresIn(Duration.ofDays(7))
                .sign();
    }
}
