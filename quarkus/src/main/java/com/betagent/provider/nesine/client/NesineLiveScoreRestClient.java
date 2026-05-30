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
 *  jakarta.ws.rs.QueryParam
 *  org.eclipse.microprofile.rest.client.inject.RegisterRestClient
 */
package com.betagent.provider.nesine.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey="nesine-livescore")
@Path(value="/api/v2/LiveScore")
@Produces(value={"application/json"})
public interface NesineLiveScoreRestClient {
    @GET
    @Path(value="GetLiveMatchListWithVersion")
    public Uni<JsonNode> liveMatches(@QueryParam(value="sportType") int var1, @QueryParam(value="includeFinished") Boolean var2, @HeaderParam(value="Origin") String var3, @HeaderParam(value="Referer") String var4, @HeaderParam(value="Accept-Encoding") String var5);
}

