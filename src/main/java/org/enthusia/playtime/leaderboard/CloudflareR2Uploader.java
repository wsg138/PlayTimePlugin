package org.enthusia.playtime.leaderboard;

import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.playtime.config.PlaytimeConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class CloudflareR2Uploader {

    private static final String REGION = "auto";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final PlaytimeConfig.R2Export config;
    private final HttpClient httpClient;
    private boolean warnedInvalidConfig;

    public CloudflareR2Uploader(JavaPlugin plugin, PlaytimeConfig.R2Export config) {
        this.plugin = plugin;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void uploadFiles(Path directory, List<String> fileNames) {
        if (!config.enabled()) {
            return;
        }
        if (!hasRequiredConfig()) {
            warnInvalidConfig();
            return;
        }

        for (String fileName : fileNames) {
            Path file = directory.resolve(fileName);
            try {
                uploadFile(file, objectKey(fileName));
            } catch (Exception exception) {
                plugin.getLogger().warning("Failed to upload leaderboard export to Cloudflare R2 (" + fileName + "): " + exception.getMessage());
            }
        }
    }

    private void uploadFile(Path file, String objectKey) throws IOException, InterruptedException {
        byte[] body = Files.readAllBytes(file);
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String payloadHash = sha256Hex(body);
        String host = config.accountId().trim() + ".r2.cloudflarestorage.com";
        String canonicalUri = "/" + trimSlashes(config.bucket()) + "/" + canonicalPath(objectKey);
        URI uri = URI.create("https://" + host + canonicalUri);
        String cacheControl = "public, max-age=60";

        String canonicalHeaders = "cache-control:" + cacheControl + "\n"
                + "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "cache-control;content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = "PUT\n"
                + canonicalUri + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(dateStamp), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + config.accessKeyId().trim() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", authorization)
                .header("Cache-Control", cacheControl)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("R2 returned HTTP " + response.statusCode() + ": " + compactBody(response.body()));
        }
    }

    private byte[] signingKey(String dateStamp) {
        byte[] dateKey = hmac(("AWS4" + config.secretAccessKey().trim()).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] regionKey = hmac(dateKey, REGION);
        byte[] serviceKey = hmac(regionKey, SERVICE);
        return hmac(serviceKey, "aws4_request");
    }

    private boolean hasRequiredConfig() {
        return !isBlank(config.accountId())
                && !isBlank(config.bucket())
                && !isBlank(config.accessKeyId())
                && !isBlank(config.secretAccessKey());
    }

    private void warnInvalidConfig() {
        if (warnedInvalidConfig) {
            return;
        }
        warnedInvalidConfig = true;
        plugin.getLogger().warning("Cloudflare R2 leaderboard upload is enabled but account-id, bucket, access-key-id, or secret-access-key is missing.");
    }

    private String objectKey(String fileName) {
        String prefix = trimSlashes(config.prefix());
        if (prefix.isBlank()) {
            return fileName;
        }
        return prefix + "/" + fileName;
    }

    private String canonicalPath(String key) {
        String[] segments = key.split("/");
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                path.append('/');
            }
            path.append(encodePathSegment(segments[i]));
        }
        return path.toString();
    }

    private String encodePathSegment(String segment) {
        StringBuilder encoded = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    encoded.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
                }
            }
        }
        return encoded.toString();
    }

    private String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String compactBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
