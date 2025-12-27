package xyz.tcheeric.cashu.ledger.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriComponentsBuilder;
import xyz.tcheeric.cashu.ledger.web.config.WebLedgerProperties;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Simple proxy to reach the remote ledger API and avoid browser CORS issues.
 */
@RestController
@RequestMapping("/proxy")
public class ProxyController {

    private static final int STREAM_BUFFER = 4096;
    private final RestTemplate restTemplate;
    private final WebLedgerProperties properties;

    public ProxyController(RestTemplate restTemplate, WebLedgerProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @GetMapping("/vouchers/{id}")
    public ResponseEntity<byte[]> proxyInspect(@PathVariable("id") String id) {
        return forward("/vouchers/" + encode(id), null);
    }

    @GetMapping("/vouchers/{id}/history")
    public ResponseEntity<byte[]> proxyHistory(@PathVariable("id") String id) {
        return forward("/vouchers/" + encode(id) + "/history", null);
    }

    @GetMapping("/vouchers/{id}/verify")
    public ResponseEntity<byte[]> proxyVerify(@PathVariable("id") String id) {
        return forward("/vouchers/" + encode(id) + "/verify", null);
    }

    @GetMapping("/vouchers/{left}/diff/{right}")
    public ResponseEntity<byte[]> proxyDiff(@PathVariable("left") String left, @PathVariable("right") String right) {
        return forward("/vouchers/" + encode(left) + "/diff/" + encode(right), null);
    }

    @GetMapping("/vouchers")
    public ResponseEntity<byte[]> proxySearch(HttpServletRequest request) {
        String query = request.getQueryString();
        String path = "/vouchers" + (query == null ? "" : "?" + query);
        return forward(path, null);
    }

    @GetMapping(value = "/watch/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> proxyWatch(@PathVariable("id") String id) {
        String url = buildUrl("/watch/" + encode(id), null);
        StreamingResponseBody body = outputStream -> {
            URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(20).toMillis());
            connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[STREAM_BUFFER];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, len);
                    outputStream.flush();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private ResponseEntity<byte[]> forward(String path, MultiValueMap<String, String> params) {
        String url = buildUrl(path, params);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class
        );
        MediaType contentType = response.getHeaders().getContentType();
        HttpHeaders outgoing = new HttpHeaders();
        if (contentType != null) {
            outgoing.setContentType(contentType);
        } else {
            outgoing.setContentType(MediaType.APPLICATION_JSON);
        }
        return new ResponseEntity<>(response.getBody(), outgoing, response.getStatusCode());
    }

    private String buildUrl(String path, MultiValueMap<String, String> params) {
        return UriComponentsBuilder.fromHttpUrl(properties.getApiBase())
                .path(path)
                .queryParams(params == null ? null : params)
                .build(Collections.emptyMap())
                .toString();
    }

    private String encode(String value) {
        return UriComponentsBuilder.newInstance().pathSegment(value).build().getPathSegments().get(0);
    }
}
