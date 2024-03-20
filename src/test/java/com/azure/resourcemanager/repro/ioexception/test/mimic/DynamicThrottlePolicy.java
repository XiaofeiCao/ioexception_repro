// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.resourcemanager.repro.ioexception.test.mimic;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.logging.ClientLogger;
import com.azure.resourcemanager.resources.fluentcore.utils.ResourceManagerUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure SDK is created to make single resource operation easy, and has wrappers around low level API by encapsulating
 * polling as well. The polling is based on the suggested retry-after from Azure service, and is user-friendly as
 * self-contained methods. However when concurrently using these methods, it will create lots of polling to the service,
 * and even within the API quota, sometimes timeout happens. The effective request rate to Azure is not as high as
 * the quota limit.
 * <p>
 * The purpose of this interceptor is to support reuse of the easy-to-use high-level APIs from the SDK, while keeping
 * a low request rate to Azure, with minimal changes. This aspect is does not pollute app layer logic.
 * <p>
 * There are other designs, like the Request Aggregator pattern plus Rolling Poller Window pattern.
 * (https://github.com/nanw1103/parallel-processing-patterns), which will be more performant if well implemented, but
 * is also harder to implement.
 */
@SuppressWarnings("squid:S1068")
public class DynamicThrottlePolicy implements HttpPipelinePolicy {
    private static final ClientLogger log = new ClientLogger(DynamicThrottlePolicy.class);

    private static final String URL_PART_OPERATIONS = "/operations/";
    private static final String URL_PART_OPERATIONS_RESULTS = "/operationresults/";
    private static final String URL_PART_OPERATIONS_STATUSES = "/operationStatuses/";
    private static final int DEFAULT_RETRY_AFTER = 30;

    //https://management.azure.com/subscriptions/a201beee-0dd8-4336-b889-89b223a1a393/providers/Microsoft.Compute/locations/eastus/operations/dd720952-f72c-46e1-9f42-2df8554435d2?
    private static final Pattern azureUrlSubscriptionPattern = Pattern.compile("^(.+?)(\\/subscriptions\\/)(.+?)(\\/)(.+?)");

    public DynamicThrottlePolicy(String clientId) {
        this.clientId = clientId;
    }

    private final String clientId;


    private final Map<String, List<Long>> cache = new LinkedHashMap<>();

    @Override
    public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
        HttpRequest httpRequest = context.getHttpRequest();
        String requestUrl = httpRequest.getUrl().toString();

        String logPrefix = String.format("[%s] {}", requestUrl);

        String requestMethod = httpRequest.getHttpMethod().name();

//        Speedometer speedometer = SpeedometerImpl.getInstance();
        // Record our recognized operationType for dev purpose
//        String requestOperationType = speedometer.getPrimaryQuotaType(requestMethod, requestUrl);
        log.info("[{}/{}/{}] get quota delay start", Thread.currentThread().getName(), requestMethod, requestUrl);
        long start = System.currentTimeMillis();
        // emulate getQuota
//        ResourceManagerUtils.sleep(Duration.ofSeconds(5));
        int delay = 0;

        long end = System.currentTimeMillis();
        long spend = end - start;
        log.info("[{}/{}/{}] get quota delay end, spend {}ms", Thread.currentThread().getName(), requestMethod, requestUrl, spend);
        if (spend > 10_000L) {
            log.info("[{}/{}/{}] Oops! get quota delay slow", Thread.currentThread().getName(), requestMethod, requestUrl);
        }
        if (delay > 0L) {
//            String reason = delay == SpeedometerConfig.DEFAULT_QUOTA_LEAK_TTL_MS
//                ? "Quota cache expired, refreshing quota cache"
//                : "Delay due to Azure rate limit";
//            return Mono.error(new CloudDriverThrottleControlException(requestMethod, requestUrl, reason, delay));
        }

        Mono<HttpResponse> response = next.process();

        Matcher m = azureUrlSubscriptionPattern.matcher(requestUrl);
        if (!m.matches()) {
            return response;
        } else {
            return response.flatMap(httpResponse -> {
                String subscriptionId = m.group(3);
                //resType not in-use so far.

                if (isOperationsPollRequest(requestUrl)) {
                    int retryAfter = recordAndCalculateRetryAfter(subscriptionId);
                    int originalRetryAfter = getOriginalRetryAfter(httpResponse);
                    //log.info(logPrefix, "originalRetryAfter=" + originalRetryAfter + ", retryAfter=" + retryAfter);
                    if (retryAfter > DEFAULT_RETRY_AFTER //not default
                        && retryAfter > originalRetryAfter  //greater than original
                    ) {
                        log.info(logPrefix, "extended retryAfter: " + originalRetryAfter + " -> " + retryAfter);
                        HttpHeaders headers = httpResponse.getHeaders();
                        headers.add(HttpHeaderName.RETRY_AFTER, String.valueOf(retryAfter));
                    }
                }
//                logRateLimit(httpResponse, subscriptionId, requestOperationType);
                return Mono.just(httpResponse);
            });
        }
    }

    // TODO: Dev purpose method
    private void compareOperationType(String originalQuotaType, Set<String> operationTypes) {
        if (!operationTypes.contains(originalQuotaType)) {
            log.warning(String.format("[DEV_TODO] Azure rate limit quota type not match. request type: %s, response type: %s", originalQuotaType, operationTypes));
        }
    }

    // TODO: requestedOperationType is for dev purposed only
    private void logRateLimit(HttpResponse response, String subscriptionId, String requestedOperationType) {
//        HttpHeaders headers = response.getHeaders();
//        HttpRequest httpRequest = response.getRequest();
//        String url = httpRequest.getUrl().toString();
//        String method = httpRequest.getHttpMethod().name();
//
//        StringBuilder sb = new StringBuilder();
//        sb.append('[').append(subscriptionId).append("/").append(method).append("/").append(url).append("] ");
//        Map<String, Long> quotaTypeToLimitMap = SpeedometerUtil.parseQuotaMapFromHeaders(headers);
//
//        quotaTypeToLimitMap.keySet().forEach(quotaType -> {
//            long rateLimit = quotaTypeToLimitMap.get(quotaType);
//            if (quotaType.startsWith(SpeedometerUtil.PRIMARY_SUBSCRIPTION_PREFIX)) {
//                recordSpeedometer(subscriptionId, clientId, quotaType, rateLimit);
//            }
//            sb.append(' ').append(quotaType).append('=').append(rateLimit);
//        });
//        if (!quotaTypeToLimitMap.isEmpty()) { // Has limit
//            // TODO: dev purpose method. Tracking operationType correctness
//            Set<String> responseQuotaType = quotaTypeToLimitMap.keySet();
//            compareOperationType(requestedOperationType, responseQuotaType);
//            log.info(sb.toString());
//        }
    }

//    private long getQuotaDelay(Speedometer speedometer, String method, String url, String clientId) {
//        String quotaType = speedometer.getPrimaryQuotaType(method, url);
//        // Is url pattern not recognized, no limit will be applied.
//        if (StringUtils.isBlank(quotaType))
//            return 0L;
//        Matcher matcher = azureUrlSubscriptionPattern.matcher(url);
//        if (!matcher.matches())
//            return 0L;
//        String subscriptionId = matcher.group(3);
//        return speedometer.getQuotaDelay(subscriptionId, clientId, quotaType);
//    }

    private void recordSpeedometer(String subscriptionId,
                                   String clientId,
                                   String name,
                                   Long value) {
//        Speedometer speedometer = SpeedometerImpl.getInstance();
//        speedometer.recordQuota(subscriptionId, clientId, name, value);
    }

    private int getOriginalRetryAfter(HttpResponse response) {
        String val = response.getHeaderValue(HttpHeaderName.RETRY_AFTER);
        if (val == null || val.trim().isEmpty())
            return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            log.warning("Fail parsing retry-after header: " + val);
            return 0;
        }
    }

    public synchronized int recordAndCalculateRetryAfter(String subscriptionId) {
        long now = System.currentTimeMillis();

        //get the holder for this subscription
        List<Long> timePoints = cache.get(subscriptionId);
        if (timePoints == null) {
            timePoints = new ArrayList<>();
            cache.put(subscriptionId, timePoints);
        } else {
            //expire stale time points
            long ttl = 300;
            while (!timePoints.isEmpty()) {
                long oldest = timePoints.get(0);
                if (now - oldest > ttl) {
                    timePoints.remove(0);
                    continue;
                }
                break;
            }
        }
        timePoints.add(now);

        int i;
        for (i = 0; i < timePoints.size(); i++) {
            if (now - timePoints.get(i) < 30 * 1000)
                break;
        }
        int num300s = timePoints.size();
        int num30s = timePoints.size() - i;
        float factor = 0.5f;
        int forecast300s = (int) (factor * num300s);
        int forecast30s = (int) (factor * num30s);
        int retryAfter = Math.max(forecast30s, forecast300s);
        retryAfter = Math.max(DEFAULT_RETRY_AFTER, retryAfter);
        return retryAfter;
    }

    private boolean isOperationsPollRequest(String url) {
        return (url.contains(URL_PART_OPERATIONS)
            || url.contains(URL_PART_OPERATIONS_RESULTS)
            || url.contains(URL_PART_OPERATIONS_STATUSES));
    }
}
