package com.azure.resourcemanager.repro.ioexception.test.undeliverable;

import com.azure.core.http.HttpResponse;
import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.azure.core.http.policy.ExponentialBackoff;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.Region;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.test.TestBase;
import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.fluentcore.policy.ResourceManagerThrottlingPolicy;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class BatchCreateResourceGroupTests extends TestBase {
    private static final ClientLogger LOGGER = new ClientLogger(BatchCreateResourceGroupTests.class);

    private AzureResourceManager azureResourceManager;

    /**
     * Entry for test.
     */
    @Test
    public void createResourceGroups() {
        for (int i = 1; i <= 100; i++) {
            LOGGER.info("Start batch test round " + i + "!");
            batchCreate100ResourceGroups();
            LOGGER.info("Batch test round " + i + " finished!");
        }
    }

    @Override
    protected void beforeTest() {
        super.beforeTest();
        String tenantId = System.getenv("AZURE_TENANT_ID");
        String clientId = System.getenv("AZURE_CLIENT_ID");
        String clientSecret = System.getenv("AZURE_CLIENT_SECRET");
        String subscriptionId = System.getenv("AZURE_SUBSCRIPTION_ID");

        // configure thread pool size to be 100
        Dispatcher okHttpDispatcher = new Dispatcher(Executors.newFixedThreadPool(100));
        // max request per host 100
        okHttpDispatcher.setMaxRequestsPerHost(100);

        azureResourceManager = AzureResourceManager.configure()
                .withHttpClient(
                        // set okhttpclient with call timeout 10 seconds
                        new OkHttpAsyncHttpClientBuilder()
                                .dispatcher(okHttpDispatcher)
                                // configure connection pool size to be 100
                                .connectionPool(new ConnectionPool(100, 5, TimeUnit.MINUTES))
                                // configure call timeout to be 10 seconds
                                .callTimeout(Duration.ofSeconds(10))
                                .build())
                .withPolicy(new ResourceManagerThrottlingPolicy((httpResponse, resourceManagerThrottlingInfo) -> resourceManagerThrottlingInfo.getRateLimit().ifPresent(integer -> {
                    if (integer <= 0 || httpResponse.getStatusCode() == 429) {
                        throw new RateLimitExceededException();
                    }
                    if (httpResponse.getStatusCode() == 409) {
                        throw new QuotaLimitExceededException();
                    }
                })))
                .withPolicy(new RetryPolicy(
                        new ExponentialBackoff(
                                new ExponentialBackoffOptions()
                                        .setMaxRetries(1)
                                        .setMaxDelay(Duration.ofSeconds(30))){

                            @Override
                            public boolean shouldRetry(HttpResponse httpResponse) {
                                boolean shouldRetry = super.shouldRetry(httpResponse);
                                if (shouldRetry) {
                                    LOGGER.warning("Http status code: " + httpResponse.getStatusCode() + ". Start retrying.");
                                }
                                return shouldRetry;
                            }

                            @Override
                            public boolean shouldRetryException(Throwable throwable) {
                                boolean shouldRetry = super.shouldRetryException(throwable)
                                        // do not try on QuotaLimitExceededException
                                        && !(throwable instanceof QuotaLimitExceededException);
                                if (shouldRetry) {
                                    LOGGER.warning("Start retrying on exception.");
                                }
                                return shouldRetry;
                            }
                        }))
                .withLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS)
                .authenticate(new DefaultAzureCredentialBuilder().build(), new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE))
                .withDefaultSubscription();
    }

    @Test
    public void removeResourceGroups() {
        azureResourceManager.resourceGroups().listAsync().flatMap((Function<ResourceGroup, Publisher<?>>) resourceGroup -> {
            if (resourceGroup.name().contains("rg-batchtest")) {
                return azureResourceManager.resourceGroups().deleteByNameAsync(resourceGroup.name());
            }
            return Mono.empty();
        }).onErrorContinue((throwable, o) -> {
        }).blockLast();
    }

    private void batchCreate100ResourceGroups() {
        String resourceGroupPrefix = "rg-batchtest-";
        List<Single<ResourceGroup>> resourceGroupCreateSingles = new ArrayList<>();
        Map<String, Single<Void>> resourceGroupDeleteSingles = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            String resourceGroupName = resourceGroupPrefix + i;

            Mono<Void> resourceGroupDeleteMono = azureResourceManager
                    .resourceGroups()
                    .deleteByNameAsync(resourceGroupName)
                    .onErrorContinue(throwable -> ! (throwable instanceof IOException), (throwable, o) -> {
                    });
            // convert Mono to Single
            resourceGroupDeleteSingles.put(resourceGroupName, Single.fromPublisher(resourceGroupDeleteMono));

            Mono<ResourceGroup> resourceGroupCreateMono = azureResourceManager.resourceGroups()
                    .define(resourceGroupName)
                    .withRegion(Region.US_WEST)
                    .createAsync()
                    .doOnError(throwable -> {
                        if (throwable instanceof QuotaLimitExceededException || throwable instanceof RateLimitExceededException) {
                            resourceGroupDeleteSingles.remove(resourceGroupName);
                        }
                    })
                    .onErrorContinue(throwable -> !(throwable instanceof IOException), (throwable, o) -> {
                    });

            // convert Mono to Single
            resourceGroupCreateSingles.add(Single.fromPublisher(resourceGroupCreateMono));
        }
        try {
            Single.merge(resourceGroupCreateSingles).subscribeOn(Schedulers.io()).blockingSubscribe();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                Single.merge(resourceGroupDeleteSingles.values()).subscribeOn(Schedulers.io()).blockingSubscribe();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
