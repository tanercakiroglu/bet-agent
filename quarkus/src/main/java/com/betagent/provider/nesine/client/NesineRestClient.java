/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.ws.rs.GET
 *  jakarta.ws.rs.HeaderParam
 *  jakarta.ws.rs.Path
 *  jakarta.ws.rs.Produces
 *  org.eclipse.microprofile.rest.client.inject.RegisterRestClient
 */
package com.betagent.provider.nesine.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey="nesine-bulten")
@Path(value="/api/bulten")
@Produces(value={"application/json"})
public interface NesineRestClient {
    @GET
    @Path(value="getprebultenfull")
    public Uni<JsonNode> preBultenFull(@HeaderParam(value="Origin") String var1, @HeaderParam(value="Referer") String var2, @HeaderParam(value="Accept-Encoding") String var3);
}

