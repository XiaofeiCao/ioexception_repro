package com.azure.resourcemanager.repro.ioexception.test.undeliverable;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.Region;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.DeploymentMode;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import io.reactivex.rxjava3.core.Single;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

public class CallTimeoutMockTests {
    private static final byte[] RESPONSE_BODY = "{\"id\":\"em\",\"name\":\"kzsz\",\"status\":\"IN_PROGRESS\",\"percentComplete\":8.862287,\"startTime\":\"2021-03-07T09:12:02Z\",\"endTime\":\"2021-04-02T00:38:08Z\"}".getBytes(StandardCharsets.UTF_8);

    private static final String SUBSCRIPTION_ID = UUID.randomUUID().toString();
    private static final String RG_NAME = "my-rg";
    private static final String POLL_URL = "/pollOperation";
    private static final String DEPLOYMENT_NAME = "my-deployment";
    private static final int HTTPS_PORT = 54321;

    private static WireMockServer server;

    @BeforeAll
    public static void beforeClass() {
        server = new WireMockServer(WireMockConfiguration.options()
                .httpsPort(HTTPS_PORT)
                .disableRequestJournal()
                .gzipDisabled(true));

        // mock create endpoint
        server.stubFor(put(String.format("/subscriptions/%s/resourcegroups/%s?api-version=2021-01-01", SUBSCRIPTION_ID, RG_NAME))
                .willReturn(
                        aResponse()
                                .withStatus(201)
                                .withHeader("Location", POLL_URL)
                        .withBody(RESPONSE_BODY)
                        // delay response for 10 seconds, so that client always experiences call timeouts
                        .withFixedDelay(10000)));

        server.stubFor(put(String.format(
                "/subscriptions/%s/resourcegroups/%s/providers/Microsoft.Resources/deployments/%s?api-version=2021-01-01",
                SUBSCRIPTION_ID,
                RG_NAME,
                DEPLOYMENT_NAME))
                .willReturn(
                        aResponse()
                                .withStatus(201)
                                .withHeader("AzureAsyncOperation", String.format("https://localhost:%d%s", HTTPS_PORT, POLL_URL))
                                .withBody(RESPONSE_BODY)
                                // delay response for 10 seconds, so that client always experiences call timeouts
                                .withFixedDelay(10000)
                ));

        server.stubFor(head(urlEqualTo(String.format("/subscriptions/%s/resourcegroups/%s/providers/Microsoft.Resources/deployments/%s?api-version=2021-01-01", SUBSCRIPTION_ID, RG_NAME, DEPLOYMENT_NAME)))
        .willReturn(aResponse()
                .withStatus(201)
                .withHeader("AzureAsyncOperation", String.format("https://localhost:%d%s", HTTPS_PORT, POLL_URL))
                .withBody(RESPONSE_BODY)
                // delay response for 10 seconds, so that client always experiences call timeouts
                .withFixedDelay(10000))
        );

        // mock poll endpoint, though create resource group seems not an LRO, never used
        server.stubFor(get(POLL_URL).willReturn(aResponse().withStatus(202).withBody(RESPONSE_BODY).withFixedDelay(10000)));

        server.start();
    }

    /**
     * Test entrance.
     */
    @Test
    public void test() throws NoSuchAlgorithmException, KeyManagementException, InterruptedException {

        HttpClient client = buildHttpClient();
        // replace ARM endpoint to our mock host
        AzureEnvironment.AZURE.getEndpoints().put("resourceManagerEndpointUrl", "https://localhost:" + server.httpsPort() + "/");

        AzureResourceManager manager =
                AzureResourceManager
                        .configure()
                        .withLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS))
                        .withHttpClient(client)
                        .authenticate(
                                mockTokenCredential(),
                                new AzureProfile(UUID.randomUUID().toString(), SUBSCRIPTION_ID, AzureEnvironment.AZURE))
                        .withDefaultSubscription();

        Thread thread = new Thread(() -> manager.deployments().checkExistence(RG_NAME, DEPLOYMENT_NAME));

        thread.start();
        // ensure the thread starts
        Thread.sleep(1000);

        thread.interrupt();
    }

    @NotNull
    private TokenCredential mockTokenCredential() {
        return tokenRequestContext -> Mono.just(new AccessToken("this_is_an_token", OffsetDateTime.MAX));
    }

    @NotNull
    private HttpClient buildHttpClient() throws NoSuchAlgorithmException, KeyManagementException {
        int concurrency = 10;
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequestsPerHost(concurrency); // this is 5 by default.

        // bypass https check
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[]{};
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .addInterceptor(
                        chain ->
                        {
                            try {
                                return chain.proceed(chain.request());
                            } catch (Exception e) {
                                e.printStackTrace();
                                throw e;
                            }
                        }
                )
                // set call timeout to 1 second, so that client always experiences call timeouts
                .connectTimeout(Duration.ofSeconds(100))
                .writeTimeout(Duration.ofSeconds(100))
                .readTimeout(Duration.ofSeconds(100))
                // bypass https check
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true)
                .build();

        // this is just a copy of OKHttpAsyncHttpClient to set custom OkHttpClient as inner client
        return new OkHttpAsyncHttpClientBuilder(okHttpClient).build();
    }
}
