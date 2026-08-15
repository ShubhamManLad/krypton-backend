package org.acme.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/health-check")
public class HealthCheckResource {

    @GET
    public String healthCheck() {
        return "Server is Up!";
    }
}
