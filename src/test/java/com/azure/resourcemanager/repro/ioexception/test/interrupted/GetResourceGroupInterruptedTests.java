package com.azure.resourcemanager.repro.ioexception.test.interrupted;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.repro.ioexception.test.undeliverable.CustomOkHttpAsyncHttpClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;

public class GetResourceGroupInterruptedTests {
    private static final byte[] RESPONSE_BODY = "{\r\n  \"name\": \"javavm\",\r\n  \"id\": \"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/javacsmrg92054/providers/Microsoft.Compute/virtualMachines/javavm\",\r\n  \"type\": \"Microsoft.Compute/virtualMachines\",\r\n  \"location\": \"eastus\",\r\n  \"tags\": {\r\n    \"azsecpack\": \"nonprod\",\r\n    \"platformsettings.host_environment.service.platform_optedin_for_rootcerts\": \"true\"\r\n  },\r\n  \"properties\": {\r\n    \"vmId\": \"7c628878-6a24-4982-9075-e9e8f3b9709d\",\r\n    \"hardwareProfile\": {\r\n      \"vmSize\": \"Standard_D2a_v4\"\r\n    },\r\n    \"storageProfile\": {\r\n      \"imageReference\": {\r\n        \"publisher\": \"MicrosoftWindowsServer\",\r\n        \"offer\": \"WindowsServer\",\r\n        \"sku\": \"2012-R2-Datacenter\",\r\n        \"version\": \"latest\",\r\n        \"exactVersion\": \"9600.20778.230108\"\r\n      },\r\n      \"osDisk\": {\r\n        \"osType\": \"Windows\",\r\n        \"name\": \"javatest\",\r\n        \"createOption\": \"FromImage\",\r\n        \"vhd\": {\r\n          \"uri\": \"https://stg17102b329b79f.blob.core.windows.net/vhds/javavm-os-disk-0ef5a666-a94a-4aad-9765-ac627d866aeb.vhd\"\r\n        },\r\n        \"caching\": \"ReadWrite\",\r\n        \"deleteOption\": \"Detach\"\r\n      },\r\n      \"dataDisks\": []\r\n    },\r\n    \"osProfile\": {\r\n      \"computerName\": \"javavm\",\r\n      \"adminUsername\": \"Foo12\",\r\n      \"windowsConfiguration\": {\r\n        \"provisionVMAgent\": true,\r\n        \"enableAutomaticUpdates\": true,\r\n        \"patchSettings\": {\r\n          \"patchMode\": \"AutomaticByOS\",\r\n          \"assessmentMode\": \"ImageDefault\"\r\n        },\r\n        \"enableVMAgentPlatformUpdates\": false\r\n      },\r\n      \"secrets\": [],\r\n      \"allowExtensionOperations\": true,\r\n      \"requireGuestProvisionSignal\": true\r\n    },\r\n    \"networkProfile\": {\"networkInterfaces\":[{\"id\":\"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/javacsmrg92054/providers/Microsoft.Network/networkInterfaces/nic70036ae6d30\",\"properties\":{\"primary\":true}}]},\r\n    \"licenseType\": \"Windows_Server\",\r\n    \"provisioningState\": \"Creating\",\r\n    \"timeCreated\": \"2023-01-13T02:23:02.975874+00:00\"\r\n  }\r\n}".getBytes(StandardCharsets.UTF_8);

    private static final String SUBSCRIPTION_ID = UUID.randomUUID().toString();
    private static final String RG_NAME = "my-rg";
    private static final String VM_NAME = "my-vm";

    private static WireMockServer server;

    @BeforeAll
    public static void beforeClass() {
        server = new WireMockServer(WireMockConfiguration.options()
                .dynamicHttpsPort()
                .disableRequestJournal()
                .gzipDisabled(true));

        // mock create endpoint
        server.stubFor(get(String.format("/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Compute/virtualMachines/%s?api-version=2022-08-01", SUBSCRIPTION_ID, RG_NAME, VM_NAME))
                .willReturn(
                        aResponse()
                                .withStatus(200)
                        .withBody(RESPONSE_BODY)
                        // delay response for 10 seconds, so that client always experiences call timeouts
                        .withFixedDelay(100000)));

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

        Thread thread = new Thread(() -> {
            try {
                manager.virtualMachines().getByResourceGroup(RG_NAME, VM_NAME);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.start();

        // ensure the thread is running
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
                .addNetworkInterceptor(
                        chain ->
                            chain.proceed(chain.request()))
                // set call timeout to 1 second, so that client always experiences call timeouts
//                .callTimeout(Duration.ofSeconds(1))
                .readTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(60))
                // bypass https check
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true)
                .build();

        // this is just a copy of OKHttpAsyncHttpClient to set custom OkHttpClient as inner client
        return new CustomOkHttpAsyncHttpClient(okHttpClient);
    }
}
