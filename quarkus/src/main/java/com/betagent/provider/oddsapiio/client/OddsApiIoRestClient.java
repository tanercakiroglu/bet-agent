/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.fasterxml.jackson.databind.JsonNode
 *  io.smallrye.mutiny.Uni
 *  jakarta.ws.rs.GET
 *  jakarta.ws.rs.Path
 *  jakarta.ws.rs.Produces
 *  jakarta.ws.rs.QueryParam
 *  org.eclipse.microprofile.rest.client.inject.RegisterRestClient
 */
package com.betagent.provider.oddsapiio.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey="odds-api-io")
@Path(value="/")
@Produces(value={"application/json"})
public interface OddsApiIoRestClient {
    @GET
    @Path(value="events")
    public Uni<JsonNode> events(@QueryParam(value="apiKey") String var1, @QueryParam(value="sport") String var2, @QueryParam(value="status") String var3, @QueryParam(value="bookmaker") String var4);

    @GET
    @Path(value="odds")
    public Uni<JsonNode> odds(@QueryParam(value="apiKey") String var1, @QueryParam(value="eventId") String var2, @QueryParam(value="bookmakers") String var3);

    @GET
    @Path(value="odds/multi")
    public Uni<JsonNode> multiOdds(@QueryParam(value="apiKey") String var1, @QueryParam(value="eventIds") String var2, @QueryParam(value="bookmakers") String var3);
}

