/*
 * Copyright 2013-2016, Teradata, Inc. All rights reserved.
 */
package com.teradata.benchto.driver.graphite;

import com.facebook.presto.jdbc.internal.guava.base.Joiner;
import com.facebook.presto.jdbc.internal.guava.collect.ImmutableMap;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.teradata.benchto.driver.BenchmarkExecutionException;
import com.teradata.benchto.driver.utils.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.teradata.benchto.driver.graphite.GraphiteClient.GraphiteRenderResponseItem.DATA_POINT_VALUE_INDEX;
import static java.lang.String.format;
import static java.util.stream.Collectors.toMap;
import static org.springframework.http.HttpStatus.OK;

@Component
@ConditionalOnProperty(prefix = "graphite", value = "url")
public class GraphiteClient
{

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphiteClient.class);

    @Value("${graphite.url}")
    private String graphiteURL;

    @Autowired
    private RestTemplate restTemplate;

    @Retryable(value = RestClientException.class, backoff = @Backoff(1000))
    public void storeEvent(GraphiteEventRequest request)
    {
        LOGGER.debug("Storing graphite event: {}", request);

        restTemplate.postForObject("{graphiteURL}/events/", request, Object.class, ImmutableMap.of("graphiteURL", graphiteURL));
    }

    @Retryable(value = RestClientException.class, backoff = @Backoff(1000))
    public Map<String, double[]> loadMetrics(Map<String, String> metrics, ZonedDateTime from, ZonedDateTime to)
    {
        URI uri = buildLoadMetricsURI(metrics, from, to);

        LOGGER.debug("Loading metrics: {}", uri);

        ResponseEntity<GraphiteRenderResponseItem[]> response = restTemplate.getForEntity(uri, GraphiteRenderResponseItem[].class);

        if (response.getStatusCode() != OK) {
            throw new BenchmarkExecutionException("Could not load metrics: " + metrics + " - error: " + response);
        }

        return Arrays.stream(response.getBody()).collect(toMap(
                GraphiteRenderResponseItem::getTarget,
                responseItem -> parseDataPoints(responseItem.datapoints)
        ));
    }

    private URI buildLoadMetricsURI(Map<String, String> metrics, ZonedDateTime from, ZonedDateTime to)
    {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromUriString(graphiteURL)
                .path("/render")
                .queryParam("format", "json")
                .queryParam("from", from.toEpochSecond())
                .queryParam("until", to.toEpochSecond());

        for (Map.Entry<String, String> metric : metrics.entrySet()) {
            String metricQueryExpr = metric.getValue();
            String metricName = metric.getKey();

            uriBuilder.queryParam("target", format("alias(%s,'%s')", metricQueryExpr, metricName));
        }

        return URI.create(uriBuilder.toUriString());
    }

    private double[] parseDataPoints(Double[][] inputDataPoints)
    {
        double[] dataPoints = new double[inputDataPoints.length];
        for (int i = 0; i < inputDataPoints.length; i++) {
            Double value = inputDataPoints[i][DATA_POINT_VALUE_INDEX];
            // rarely graphite does not return value for given timestamp, use 0 in this case
            dataPoints[i] = value == null ? 0 : value;
        }
        return dataPoints;
    }

    @SuppressWarnings("unused")
    @JsonAutoDetect(fieldVisibility = ANY)
    public static class GraphiteEventRequest
    {
        private String what;
        private String tags;
        private String data = "";
        private BigDecimal when;

        private GraphiteEventRequest()
        {
        }

        public static class GraphiteEventRequestBuilder
        {
            private GraphiteEventRequest request = new GraphiteEventRequest();

            public GraphiteEventRequestBuilder()
            {
                when(TimeUtils.nowUtc());
            }

            public GraphiteEventRequestBuilder what(String what)
            {
                request.what = what;
                return this;
            }

            public GraphiteEventRequestBuilder tags(String... tags)
            {
                request.tags = Joiner.on(" ").join(tags);
                return this;
            }

            public GraphiteEventRequestBuilder data(String data)
            {
                request.data = data;
                return this;
            }

            public GraphiteEventRequestBuilder when(ZonedDateTime zonedDateTime)
            {
                request.when = new BigDecimal(format("%d.%d", zonedDateTime.toEpochSecond(), zonedDateTime.getNano() / 1000));
                return this;
            }

            public GraphiteEventRequest build()
            {
                return request;
            }
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("what", what)
                    .add("tags", tags)
                    .add("data", data)
                    .add("when", when)
                    .toString();
        }
    }

    @SuppressWarnings("unused")
    @JsonAutoDetect(fieldVisibility = ANY)
    public static class GraphiteRenderResponseItem
    {
        public static final int DATA_POINT_VALUE_INDEX = 0;
        public static final int DATA_POINT_TIMESTAMP_INDEX = 1;

        private String target;
        private Double[][] datapoints;

        String getTarget()
        {
            return target;
        }
    }
}
