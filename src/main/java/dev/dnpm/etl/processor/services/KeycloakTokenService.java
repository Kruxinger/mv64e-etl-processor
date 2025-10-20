package dev.dnpm.etl.processor.services;



import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Service
public class KeycloakTokenService {

    @Value("${keycloak.token-url}")
    private String tokenUrl;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${keycloak.username}")
    private String username;

    @Value("${keycloak.password}")
    private String password;

    private String cachedToken;

    public String getAccessToken() {
        if (cachedToken != null) return cachedToken;

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String body = String.format(
                "client_id=%s&client_secret=%s&username=%s&password=%s&grant_type=password",
                clientId, clientSecret, username, password
        );

        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(tokenUrl, entity, Map.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            cachedToken = (String) response.getBody().get("access_token");
            return cachedToken;
        }
        throw new RuntimeException("Failed to get Keycloak token: " + response.getStatusCode());
    }
}
