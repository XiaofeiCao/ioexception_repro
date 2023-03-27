package com.azure.resourcemanager.repro.ioexception.test.undeliverable;

import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.Region;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.test.TestBase;
import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class BatchCreateResourceGroupTests extends TestBase {
    private static final ClientLogger LOGGER = new ClientLogger(BatchCreateResourceGroupTests.class);
    private static final int PARALLELISM = 100;

    private AzureResourceManager azureResourceManager;

    /**
     * Entry for test.
     */
    @Test
    public void createResourceGroups() throws InterruptedException {
        for (int i = 1;; i++) {
            LOGGER.info("Start batch test round " + i + "!");
            batchCreate100ResourceGroups();
            LOGGER.info("Batch test round " + i + " finished!");
            TimeUnit.MINUTES.sleep(5);
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
        okHttpDispatcher.setMaxRequestsPerHost(PARALLELISM);
        okHttpDispatcher.setMaxRequests(PARALLELISM);

        azureResourceManager = AzureResourceManager.configure()
                .withHttpClient(
                        // set okhttpclient with call timeout 10 seconds
                        new OkHttpAsyncHttpClientBuilder()
                                .dispatcher(okHttpDispatcher)
                                // configure connection pool size to be 100
                                .connectionPool(new ConnectionPool(PARALLELISM, 5, TimeUnit.MINUTES))
                                // configure call timeout to be 10 seconds
//                                .callTimeout(Duration.ofSeconds(10))
                                .build())
                .withLogLevel(HttpLogDetailLevel.BASIC)
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
        try {
            // create 100 resource groups in parallel
            Flux.range(0, PARALLELISM)
                    .parallel(PARALLELISM)
                    .sequential()
                    .flatMap(i -> {
                        String resourceGroupName = resourceGroupPrefix + i;
                        return azureResourceManager
                            .resourceGroups()
                            .define(resourceGroupName)
                            .withRegion(Region.US_WEST)
                            .createAsync();
                    })
                    .blockLast();
        } finally {
            // delete resourceGroups
            Flux.range(0, PARALLELISM)
                    .parallel(PARALLELISM)
                    .sequential()
                    .flatMap(i -> {
                        String rgName = resourceGroupPrefix + i;
                        return azureResourceManager.resourceGroups().manager().serviceClient()
                                .getResourceGroups()
                                .checkExistenceAsync(rgName)
                                .flatMap(new Function<Boolean, Mono<Void>>() {
                                    @Override
                                    public Mono<Void> apply(Boolean exist) {
                                        if (exist) {
                                            return azureResourceManager.resourceGroups().deleteByNameAsync(rgName);
                                        } else {
                                            return Mono.empty();
                                        }
                                    }
                                });
                    })
                    .blockLast();
        }
    }
}
