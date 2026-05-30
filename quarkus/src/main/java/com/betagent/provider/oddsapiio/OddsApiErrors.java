/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.ws.rs.WebApplicationException
 *  jakarta.ws.rs.core.Response
 */
package com.betagent.provider.oddsapiio;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Locale;

public final class OddsApiErrors {
    private OddsApiErrors() {
    }

    public static boolean isQuotaExceeded(Throwable ex) {
        if (ex == null) {
            return false;
        }
        if (!(ex instanceof WebApplicationException)) {
            String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
            return msg.contains("rate limit of") && msg.contains("per hour");
        }
        WebApplicationException webEx = (WebApplicationException)ex;
        Response response = webEx.getResponse();
        if (response == null || response.getStatus() != 429) {
            return false;
        }
        String remaining = response.getHeaderString("X-Ratelimit-Remaining");
        if (remaining != null && remaining.trim().equals("0")) {
            return true;
        }
        try {
            if (response.hasEntity()) {
                String lowerBody;
                String body = (String)response.readEntity(String.class);
                String string = lowerBody = body == null ? "" : body.toLowerCase(Locale.ROOT);
                if (lowerBody.contains("rate limit of") && lowerBody.contains("per hour")) {
                    return true;
                }
            }
        }
        catch (Exception body) {
            // empty catch block
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("rate limit of") && msg.contains("per hour");
    }

    public static boolean shouldFailoverToNextKey(Throwable ex) {
        WebApplicationException webEx;
        Response response;
        if (OddsApiErrors.isQuotaExceeded(ex)) {
            return true;
        }
        if (ex instanceof WebApplicationException && (response = (webEx = (WebApplicationException)ex).getResponse()) != null && response.getStatus() == 429) {
            return true;
        }
        String msg = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("429") || msg.contains("too many requests");
    }
}

