package dev.dnpm.etl.processor.pseudonym;

import dev.dnpm.etl.processor.services.KeycloakTokenService;
import jakarta.xml.soap.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.net.URL;
import java.security.cert.X509Certificate;

@Service
public class GpasSoapClient {

    private final KeycloakTokenService tokenService;

    @Value("${gpas.wsdl-url}")
    private String wsdlUrl;

    public GpasSoapClient(KeycloakTokenService tokenService) {
        this.tokenService = tokenService;
    }

    public String getVorgangsnummerForFallId(String fallId) throws Exception {
        disableSslVerification(); // nur für interne Testumgebungen!

        String token = tokenService.getAccessToken();

        SOAPConnectionFactory soapConnectionFactory = SOAPConnectionFactory.newInstance();
        SOAPConnection soapConnection = soapConnectionFactory.createConnection();

        URL endpoint = new URL(wsdlUrl); // z.B. "http://localhost:8082/gpas/gpasService"

        // 1. Aufruf: getOrCreatePseudonymFor -> Arbeitsnummer
        SOAPMessage request1 = createRequest(fallId, "arbeitsnummer", "getOrCreatePseudonymFor", token);
        SOAPMessage response1 = soapConnection.call(request1, endpoint);
        String arbeitsnummer = extractPsnResult(response1);

        // 2. Aufruf: createPseudonymFor -> Vorgangsnummer
        SOAPMessage request2 = createRequest(arbeitsnummer, "vorgangsnummer", "createPseudonymFor", token);
        SOAPMessage response2 = soapConnection.call(request2, endpoint);
        String vorgangsnummer = extractPsnResult(response2);

        soapConnection.close();
        return vorgangsnummer;
    }

    private SOAPMessage createRequest(String value, String domain, String operation, String token) throws SOAPException {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();

        SOAPEnvelope envelope = soapMessage.getSOAPPart().getEnvelope();
        envelope.addNamespaceDeclaration("psn", "http://psn.ttp.ganimed.icmvc.emau.org/");
        SOAPBody body = envelope.getBody();

        SOAPElement root = body.addChildElement(operation, "psn");
        root.addChildElement("value").addTextNode(value);
        root.addChildElement("domainName").addTextNode(domain);

        MimeHeaders headers = soapMessage.getMimeHeaders();
        headers.addHeader("Authorization", "Bearer " + token);
        headers.addHeader("Content-Type", "text/xml;charset=UTF-8");

        soapMessage.saveChanges();
        return soapMessage;
    }

    private String extractPsnResult(SOAPMessage response) throws Exception {
        SOAPBody body = response.getSOAPBody();
        // extrahiert den Inhalt des ersten Kind-Elements im psn-Namespace
        return body.getFirstChild().getTextContent().trim();
    }

    /** deaktiviert Zertifikatsprüfung für interne Testumgebung **/
    private static void disableSslVerification() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }
}
