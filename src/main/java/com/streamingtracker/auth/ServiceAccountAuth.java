package com.streamingtracker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Exchanges a GCP service-account JSON file for a short-lived OAuth2 access token
 * suitable for Firebase REST API calls.  Uses only the JDK crypto stack — no external
 * JWT library needed.
 */
public class ServiceAccountAuth {

    private static final String TOKEN_URL  = "https://oauth2.googleapis.com/token";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    // Both scopes are required: database for data writes, userinfo.email for the JWT audience.
    private static final String SCOPE =
            "https://www.googleapis.com/auth/firebase.database " +
            "https://www.googleapis.com/auth/userinfo.email";

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final OkHttpClient http;

    public ServiceAccountAuth(String serviceAccountPath, OkHttpClient http) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(serviceAccountPath)));
        JsonObject sa = JsonParser.parseString(raw).getAsJsonObject();
        this.clientEmail = sa.get("client_email").getAsString();
        this.privateKey  = parseRsaKey(sa.get("private_key").getAsString());
        this.http = http;
    }

    public String getAccessToken() throws Exception {
        String jwt = buildJwt();
        RequestBody form = new FormBody.Builder()
                .add("grant_type", GRANT_TYPE)
                .add("assertion", jwt)
                .build();
        Request req = new Request.Builder().url(TOKEN_URL).post(form).build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("Token exchange failed (" + resp.code() + "): " + body);
            }
            return JsonParser.parseString(body).getAsJsonObject()
                    .get("access_token").getAsString();
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private PrivateKey parseRsaKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String buildJwt() throws Exception {
        long now = Instant.now().getEpochSecond();
        String header  = b64u(("{\"alg\":\"RS256\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
        String payload = b64u(String.format(
                "{\"iss\":\"%s\",\"scope\":\"%s\",\"aud\":\"%s\",\"iat\":%d,\"exp\":%d}",
                clientEmail, SCOPE, TOKEN_URL, now, now + 3600
        ).getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + b64u(sig.sign());
    }

    private static String b64u(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
