package org.acme.auth;

import java.util.UUID;

public class AuthResponse {
    public String token;
    public UUID userId;
    public String username;

    public AuthResponse() {}

    public AuthResponse(String token, UUID userId, String username) {
        this.token = token;
        this.userId = userId;
        this.username = username;
    }
}
