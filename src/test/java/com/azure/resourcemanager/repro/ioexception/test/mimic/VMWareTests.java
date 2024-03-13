// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.resourcemanager.repro.ioexception.test.mimic;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.rest.Response;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.Region;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.test.TestBase;
import com.azure.core.util.logging.ClientLogger;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.fluent.models.DeploymentExtendedInner;
import com.azure.resourcemanager.resources.fluentcore.model.Accepted;
import com.azure.resourcemanager.resources.fluentcore.utils.ResourceManagerUtils;
import com.azure.resourcemanager.resources.models.Deployment;
import com.azure.resourcemanager.resources.models.DeploymentMode;
import com.azure.resourcemanager.resources.models.ProvisioningState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class VMWareTests extends TestBase {
    private AzureResourceManager azureResourceManager;
    private static final int PARALLELISM = 4;
    private final Random random = new Random();

    private static final ClientLogger LOGGER = new ClientLogger(VMWareTests.class);
    @Test
    public void testVmware() throws IOException {
        String templateJson = new String(readAllBytes(AzureResourceManager.class.getResourceAsStream("/vm-template.json")), Charset.forName("utf-8"));
        Assertions.assertNotNull(templateJson);
        AtomicInteger round = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        while (true) {
            LOGGER.info("round: {}, request count: {}, failures: {}", round.get(), counter.get(), failures.get());
            try {
                CountDownLatch ctl = new CountDownLatch(PARALLELISM);

                for (int i = 0; i < PARALLELISM; i++) {
                    counter.incrementAndGet();
                    Deployment beginDeploy;
                    String resourceGroupName = generateRandomResourceName("vmware", 15);
                    try {
                        String deploymentName = generateRandomResourceName("dp", 15);
                        beginDeploy = azureResourceManager
                            .deployments()
                            .define(deploymentName)
                            .withNewResourceGroup(resourceGroupName, Region.US_WEST)
                            .withTemplate(templateJson)
                            .withParameters(new HashMap<String, Object>() {{
                                setParameter(this, "adminUsername", "azureUser");
                                setParameter(this, "adminPassword", generateRandomResourceName("Pa5$", 15));
                            }}).withMode(DeploymentMode.INCREMENTAL)
                            .beginCreateAsync()
                            .doOnSuccess(deployment -> LOGGER.info("Create VM: {}", deployment))
                            .block();
                    } catch (Exception e) {
                        LOGGER.logThrowableAsError(e);
                        failures.incrementAndGet();
                        ctl.countDown();
                        continue;
                    }

                    new Thread(() -> {
                        try {
                            waitForCompletion(resourceGroupName, beginDeploy);
                        } catch (Exception e) {
                            LOGGER.logThrowableAsError(e);
                            failures.incrementAndGet();
                        } finally {
                            ctl.countDown();
                            azureResourceManager.resourceGroups().beginDeleteByName(resourceGroupName);
                        }
                    }, "Thread-" + counter.get()).start();
                }
                try {
                    ctl.await();
                } catch (InterruptedException e) {
                    LOGGER.logThrowableAsError(e);
                }
                ResourceManagerUtils.sleep(Duration.ofSeconds(10));
            } catch (Exception e) {
                LOGGER.logThrowableAsError(e);
            } finally {
                round.incrementAndGet();
            }
        }
    }

    private void waitForCompletion(String resourceGroupName, Deployment deployment) {
        Response<DeploymentExtendedInner> response = Flux.interval(Duration.ofSeconds(30))
                .flatMap(index -> azureResourceManager
                        .genericResources()
                        .manager()
                        .serviceClient()
                        .getDeployments()
                        .getByResourceGroupWithResponseAsync(resourceGroupName, deployment.name()))
                .takeUntil(new Predicate<>() {
                    @Override
                    public boolean test(Response<DeploymentExtendedInner> response) {
                        // stop at server error
                        if (response.getStatusCode() >= 400) {
                            return true;
                        }
                        // stop at final state
                        DeploymentExtendedInner inner = response.getValue();
                        ProvisioningState provisioningState = inner.properties().provisioningState();
                        return isFinalState(provisioningState);
                    }
                }).blockLast();
        if (response.getStatusCode() >= 400) {
            throw new IllegalStateException("response status: " + response.getStatusCode());
        }
        if (!ProvisioningState.SUCCEEDED.equals(response.getValue().properties().provisioningState())) {
            throw new IllegalStateException("provision state: " + response.getValue().properties().provisioningState());
        }
    }

    private boolean isFinalState(ProvisioningState provisioningState) {
        return ProvisioningState.CANCELED.equals(provisioningState)
                || ProvisioningState.SUCCEEDED.equals(provisioningState)
                || ProvisioningState.FAILED.equals(provisioningState);
    }

    private String generateRandomResourceName(String prefix, int length) {
        if (prefix == null) {
            return generateRandomResourceName("df", length);
        }
        StringBuilder result = new StringBuilder(prefix);
        while (result.length() < length) {
            result.append(random.nextInt(10));
        }
        return result.toString();
    }

    protected byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int readValue;
        byte[] data = new byte[0xFFFF];
        while ((readValue = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, readValue);
        }
        return buffer.toByteArray();
    }
    private static void setParameter(Map<String, Object> parameters, final String name, final Object value) {
        parameters.put(name, new HashMap<String, Object>(){{this.put("value", value);}});
    }
    @Override
    protected void beforeTest() {
        super.beforeTest();
        String tenantId = System.getenv("AZURE_TENANT_ID");
        String clientId = System.getenv("AZURE_CLIENT_ID");
        String clientSecret = System.getenv("AZURE_CLIENT_SECRET");
        String subscriptionId = System.getenv("AZURE_SUBSCRIPTION_ID");

        ConnectionProvider connectionProvider = ConnectionProvider.builder("AzureV2Connection")
                .maxConnections(500)
                // When the maximum number of channels in the pool is reached, up to specified new attempts to
                // acquire a channel are delayed (pending) until a channel is returned to the pool again, and further attempts are declined with an error.
                // PoolAcquirePendingLimitException will be thrown directly when the maximum number of channels in the pool reached, it will drop the request directly
                // So we set pendingAcquireMaxCount to -1 to not limit the maximum number of channels
                .pendingAcquireMaxCount(-1)
                .maxIdleTime(Duration.ofSeconds(60)) // Configures the maximum time for a connection to stay idle to 20 seconds.
                //.maxLifeTime(Duration.ofSeconds(60)) // Configures the maximum time for a connection to stay alive to 60 seconds.
                .pendingAcquireTimeout(Duration.ofSeconds(60)) // Configures the maximum time for the pending acquire operation to 60 seconds. The request will be retried when pendingAcquireTimeout
                //.evictInBackground(Duration.ofSeconds(120)) // Every two minutes, the connection pool is regularly checked for connections that are applicable for removal.
                .build();
        HttpClient httpClient = new NettyAsyncHttpClientBuilder()
                .responseTimeout(Duration.of(90, ChronoUnit.SECONDS))
                .readTimeout(Duration.of(60, ChronoUnit.SECONDS))
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .connectionProvider(connectionProvider)
                .build();

        azureResourceManager = AzureResourceManager.configure()
                .withHttpClient(httpClient)
                .withLogLevel(HttpLogDetailLevel.BODY_AND_HEADERS)
                .withPolicy(new DynamicThrottlePolicy(clientId))
                .authenticate(new DefaultAzureCredentialBuilder().build(), new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE))
                .withDefaultSubscription();
    }
}
