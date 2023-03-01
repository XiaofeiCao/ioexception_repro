package com.azure.resourcemanager.repro.ioexception.test.expiredtoken;

import com.azure.core.credential.AccessToken;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.ExponentialBackoff;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.BinaryData;
import com.azure.core.util.logging.ClientLogger;
import com.azure.resourcemanager.AzureResourceManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class LongRunningOperationTokenExpiredTests {

    private static final ClientLogger LOGGER = new ClientLogger(LongRunningOperationTokenExpiredTests.class);
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /**
     * Here we mock the situation where token expired during long running operation.
     *
     */
    @Test
    public void testConcurrentResourceGroupDelete() {
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
                        () -> constructSuccessResponse(httpRequest, startTime)));

        AzureResourceManager manager =
            AzureResourceManager
                .configure()
                .withLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS))
                .withHttpClient(httpClient)
                .authenticate(
                    // mock the situation where service returns an near-expired token, here we return a token 6 minutes before expiry
                    // token refresh happens 5 minutes before token expiry, so it should happen 1 minute after the test starts
                    tokenRequestContext -> Mono.defer(() -> {
                        OffsetDateTime expireTime = OffsetDateTime.now().plusMinutes(6);
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


    @Test
    public void testRetryOnExpiredToken() {
        HttpClient httpClient = Mockito.mock(HttpClient.class);
        ArgumentCaptor<HttpRequest> httpRequest = ArgumentCaptor.forClass(HttpRequest.class);
        Mockito
                .when(httpClient.send(httpRequest.capture(), Mockito.any()))
                .thenReturn(
                        Mono
                                .defer(
                                        () -> constructExpiredTokenResponse(httpRequest)));
        AtomicBoolean retriedOnExpiredToken = new AtomicBoolean(false);

        // construct AzureResourceManager with retry on ExpireAuthenticationToken policy
        AzureResourceManager manager =
                AzureResourceManager
                        .configure()
                        .withLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS))
                        .withHttpClient(httpClient)
                        // retry policy
                        .withRetryPolicy(new RetryPolicy(new ExponentialBackoff() {
                            @Override
                            public boolean shouldRetry(HttpResponse httpResponse) {
                                boolean isExpiredToken = isExpiredAuthenticationToken(httpResponse);
                                if (isExpiredToken) {
                                    // Do some log here
                                    LOGGER.error("Token expired. \nMessage: {}.\nCurrent UTC time: {}",
                                            httpResponse.getBodyAsString().block(),
                                            OffsetDateTime.now().atZoneSameInstant(ZoneOffset.UTC)
                                                    .format(DateTimeFormatter.ofPattern("d/M/yyyy h:mm:ss a")));
                                    // ensure retried in test
                                    retriedOnExpiredToken.set(true);
                                } else if (httpResponse.getHeaderValue(WWW_AUTHENTICATE) != null) {
                                    // in case a Conditional Access policy change, log it:
                                    // https://learn.microsoft.com/en-us/azure/active-directory/conditional-access/concept-continuous-access-evaluation
                                    LOGGER.warning("Conditional Access state changed, header: {}", httpResponse.getHeaderValue(WWW_AUTHENTICATE));
                                }
                                return super.shouldRetry(httpResponse)
                                        || isExpiredToken;
                            }
                        }))
                        .authenticate(
                                tokenRequestContext -> Mono.just(new AccessToken("this_is_an_expired_token", OffsetDateTime.now().minusMinutes(1))),
                                new AzureProfile("", "", AzureEnvironment.AZURE))
                        .withDefaultSubscription();

        Assertions.assertThrows(Exception.class, () ->
                Completable.fromPublisher(manager.resourceGroups().deleteByNameAsync("my-rg")).blockingAwait());
        Assertions.assertTrue(retriedOnExpiredToken.get());
    }

    private boolean isExpiredAuthenticationToken(HttpResponse httpResponse) {
        return
                // 401
                httpResponse.getStatusCode() == HttpURLConnection.HTTP_UNAUTHORIZED
                // no ARM Challenge present
                && httpResponse.getHeaderValue(WWW_AUTHENTICATE) == null
                // contains error code "ExpiredAuthenticationToken"
                && httpResponse.getBodyAsBinaryData() != null
                && httpResponse.getBodyAsBinaryData().toString().contains("ExpiredAuthenticationToken");
    }

    private Mono<HttpResponse> constructExpiredTokenResponse(ArgumentCaptor<HttpRequest> httpRequest) {
        String responseStr = "{\"error\":{\"code\":\"ExpiredAuthenticationToken\",\"message\":\"The access token expiry UTC time '8/19/2017 3:20:06 AM' is earlier than current UTC time '8/19/2017 2:09:10 PM'.\"}}";

        HttpResponse httpResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(httpResponse.getStatusCode()).thenReturn(401);
        Mockito.when(httpResponse.getHeaders()).thenReturn(new HttpHeaders());

        Mockito
                .when(httpResponse.getBody())
                .thenReturn(Flux.just(ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8))));
        Mockito
                .when(httpResponse.getBodyAsByteArray())
                .thenReturn(Mono.just(responseStr.getBytes(StandardCharsets.UTF_8)));
        Mockito
                .when(httpResponse.getBodyAsBinaryData())
                .thenReturn(BinaryData.fromString(responseStr));
        Mockito
                .when(httpResponse.getBodyAsString())
                .thenReturn(Mono.just(responseStr));
        Mockito
                .when(httpResponse.buffer())
                .thenReturn(httpResponse);
        Mockito.when(httpResponse.getRequest()).thenReturn(httpRequest.getValue());

        return Mono.just(httpResponse);
    }

    @NotNull
    private Mono<HttpResponse> constructSuccessResponse(ArgumentCaptor<HttpRequest> httpRequest, OffsetDateTime startTime) {
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
