package com.azure.resourcemanager.repro.ioexception.test.expiredtoken;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.implementation.ResourceManagementClientBuilder;
import com.azure.resourcemanager.resources.implementation.ResourceManagementClientImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LongRunningOperationTokenExpiredTests {

    /**
     * Here we mock the situation where token expired during long running operation.
     */
    @Test
    public void testResourceGroupDelete() throws InterruptedException {
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        ArgumentCaptor<HttpRequest> httpRequest = ArgumentCaptor.forClass(HttpRequest.class);

        Mockito
            .when(httpClient.send(httpRequest.capture(), Mockito.any()))
            .thenReturn(
                Mono
                    .defer(
                        () -> {
                            String responseStr =
                                "{\"id\":\"em\",\"name\":\"kzsz\",\"status\":\"IN_PROGRESS\",\"percentComplete\":8.862287,\"startTime\":\"2021-03-07T09:12:02Z\",\"endTime\":\"2021-04-02T00:38:08Z\"}";

                            HttpResponse httpResponse = Mockito.mock(HttpResponse.class);

                            // always return 202 for the long running operation to poll indefinitely
                            Mockito.when(httpResponse.getStatusCode()).thenReturn(202);
                            // set Location url for poll operation to start
                            Mockito.when(httpResponse.getHeaders()).thenReturn(new HttpHeaders().set("Location", "https://www.example.org"));
                            Mockito
                                .when(httpResponse.getBody())
                                .thenReturn(Flux.just(ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8))));
                            Mockito
                                .when(httpResponse.getBodyAsByteArray())
                                .thenReturn(Mono.just(responseStr.getBytes(StandardCharsets.UTF_8)));
                            Mockito
                                .when(httpResponse.getBodyAsString())
                                .thenReturn(Mono.just(responseStr));
                            HttpRequest httpRequestValue = httpRequest.getValue();
                            Mockito.when(httpResponse.getRequest()).thenReturn(httpRequestValue);
                            String auth = httpRequestValue.getHeaders().getValue("Authorization");

                            OffsetDateTime expireTime = decode(auth);
                            if (OffsetDateTime.now().isAfter(expireTime)) {
                                System.out.println("expired!");
                                System.exit(1);
                            }

                            return Mono.just(httpResponse);
                        }));

        ResourceManager manager =
            ResourceManager
                .configure()
                .withLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS))
                .withHttpClient(httpClient)
                .authenticate(
                    // mock the situation where service returns an near-expired token, here we return a token 30 seconds before expiry
                    tokenRequestContext -> Mono.defer(() -> {
                        OffsetDateTime expireTime = OffsetDateTime.now().plusSeconds(30);
                        return Mono.just(new AccessToken(encode("this_is_a_valid_token", expireTime), expireTime));
                    }),
                    new AzureProfile("", "", AzureEnvironment.AZURE))
            .withDefaultSubscription();

        ResourceManagementClientImpl managementClient = new ResourceManagementClientBuilder()
            // set poll interval to 1 second
            .defaultPollInterval(Duration.ofSeconds(1))
            .pipeline(manager.httpPipeline())
            .subscriptionId(manager.subscriptionId())
            .buildClient();

        // implementation of manager.resourceGroups().deleteByName() calls this method
        ExecutorService executorService = Executors.newFixedThreadPool(100);
        for (int i = 0; i < 100; i++) {
            executorService.submit(() -> {
                managementClient.getResourceGroups().deleteAsync("my-rg").block();
            });
        }
        // wait indefinitely
        executorService.awaitTermination(100, TimeUnit.DAYS);
    }

    private OffsetDateTime decode(String auth) {
        long epochMilli = Long.parseLong(auth.replace("Bearer this_is_a_valid_token", ""));
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    private String encode(String token, OffsetDateTime expireTime) {
        return token + expireTime.toInstant().toEpochMilli();
    }
}
