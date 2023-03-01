package com.azure.resourcemanager.repro.ioexception.test.expiredtoken;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.resources.ResourceManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

public class LongRunningOperationTokenExpiredTests {

    /**
     * Here we mock the situation where token expired during long running operation.
     *
     */
    @Test
    public void testResourceGroupDelete() {
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        ArgumentCaptor<HttpRequest> httpRequest = ArgumentCaptor.forClass(HttpRequest.class);

        OffsetDateTime startTime = OffsetDateTime.now();

        Mockito
            .when(httpClient.send(httpRequest.capture(), Mockito.any()))
            .thenReturn(
                Mono
                    .defer(
                            // the response returns 202s for the first 6 minutes
                            // followed by 200s afterwards
                        () -> constructResponse(httpRequest, startTime)));

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

        // concurrently run delete on 100 threads
        // this should take 6 minute to finish
        Flowable.range(1, 100)
                .parallel(100)
                .sequential()
                .flatMapCompletable(ignored ->
                        // implementation of manager.resourceGroups().deleteByName() calls this method
                        // deleteAsync returns Mono<Void>, which should not be converted to Single, use Completable instead
                        // see https://github.com/r2dbc/r2dbc-spi/issues/13#issuecomment-427323053
                        Completable.fromPublisher(
                                manager.resourceGroups().deleteByNameAsync("my-rg")))
                .subscribeOn(Schedulers.io())
                .blockingSubscribe();
    }

    @NotNull
    private Mono<HttpResponse> constructResponse(ArgumentCaptor<HttpRequest> httpRequest, OffsetDateTime startTime) {
        String responseStr =
            "{\"id\":\"em\",\"name\":\"kzsz\",\"status\":\"IN_PROGRESS\",\"percentComplete\":8.862287,\"startTime\":\"2021-03-07T09:12:02Z\",\"endTime\":\"2021-04-02T00:38:08Z\"}";

        HttpResponse httpResponse = Mockito.mock(HttpResponse.class);

        OffsetDateTime now = OffsetDateTime.now();
        if (now.isAfter(startTime.plusMinutes(6))) {
            // after 6 minute, return success
            Mockito.when(httpResponse.getStatusCode()).thenReturn(200);
        } else {
            // always return 202 for the long running operation to poll indefinitely
            Mockito.when(httpResponse.getStatusCode()).thenReturn(202);
        }

        HttpRequest httpRequestValue = httpRequest.getValue();
        if (httpRequestValue.getHttpMethod() != HttpMethod.GET) {
            // set Location url for poll operation to start
            Mockito.when(httpResponse.getHeaders()).thenReturn(new HttpHeaders().set("Location", "https://www.example.org"));
        } else {
            Mockito.when(httpResponse.getHeaders()).thenReturn(new HttpHeaders());
        }
        Mockito
            .when(httpResponse.getBody())
            .thenReturn(Flux.just(ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8))));
        Mockito
            .when(httpResponse.getBodyAsByteArray())
            .thenReturn(Mono.just(responseStr.getBytes(StandardCharsets.UTF_8)));
        Mockito
            .when(httpResponse.getBodyAsString())
            .thenReturn(Mono.just(responseStr));
        Mockito.when(httpResponse.getRequest()).thenReturn(httpRequestValue);
        String auth = httpRequestValue.getHeaders().getValue("Authorization");

        OffsetDateTime expireTime = decode(auth);
        if (now.isAfter(expireTime)) {
            // if an expired token ever got sent, stop with error
            System.out.println("expired!");
            System.exit(1);
        }

        return Mono.just(httpResponse);
    }

    // decode expire time from the token
    private OffsetDateTime decode(String auth) {
        long epochMilli = Long.parseLong(auth.replace("Bearer this_is_a_valid_token", ""));
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    // encode the token expire time into token string
    private String encode(String token, OffsetDateTime expireTime) {
        return token + expireTime.toInstant().toEpochMilli();
    }
}
