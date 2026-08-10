package org.acme.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/hello")
public class HelloWorldResource {

    @GET
    @Path("/helloworld")
    public String helloWorld() {
        return "Hello World";
    }
}
