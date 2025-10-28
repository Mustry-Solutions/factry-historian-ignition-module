package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.query.AbstractQueryEngine;
import com.inductiveautomation.historian.gateway.api.query.AggregationType;
import com.inductiveautomation.historian.gateway.api.query.browsing.BrowseFilter;
import com.inductiveautomation.historian.gateway.api.query.browsing.BrowsePublisher;
import com.inductiveautomation.historian.gateway.api.query.processor.AggregatedPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.processor.ComplexPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.AggregatedQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.ComplexQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.RawQueryOptions;
import com.inductiveautomation.historian.gateway.api.paths.QualifiedPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;

/**
 * Query engine for reading historical data from the Factry Historian system.
 *
 * This implementation queries data from the proxy REST API at /provider endpoint.
 */
public class FactryQueryEngine extends AbstractQueryEngine {
    private static final Logger logger = LoggerFactory.getLogger(FactryQueryEngine.class);

    private final FactryHistorianSettings settings;

    public FactryQueryEngine(FactryHistorianSettings settings) {
        this.settings = settings;
        logger.info("Factry Query Engine initialized with proxy URL: {}", settings.getProxyUrl());
    }

    @Override
    public void browse(QualifiedPath root, BrowseFilter filter, BrowsePublisher publisher) {
        logger.info("Browse request for path: {}", root);

        // TODO: Implement browsing - query available tags from proxy
        // For now, just complete with no results
        publisher.complete();
    }

    @Override
    public void query(RawQueryOptions options, RawPointProcessor processor) {
        logger.info("Raw query request: {}", options);

        try {
            // TODO: Implement actual HTTP call to proxy /provider endpoint
            // For now, just log and complete

            if (settings.isDebugLogging()) {
                logger.debug("Query start: {}, end: {}",
                        options.getStartTime(), options.getEndTime());
                logger.debug("Paths: {}", options.getPaths());
            }

            // Complete the processor (no data for now)
            processor.complete();

        } catch (Exception e) {
            logger.error("Error querying raw data", e);
            processor.error(e);
        }
    }

    @Override
    public void query(AggregatedQueryOptions options, AggregatedPointProcessor processor) {
        logger.info("Aggregated query request: {}", options);

        try {
            // TODO: Implement aggregated queries
            // For now, just complete with no data
            processor.complete();

        } catch (Exception e) {
            logger.error("Error querying aggregated data", e);
            processor.error(e);
        }
    }

    @Override
    public void query(ComplexQueryOptions options, ComplexPointProcessor processor) {
        logger.info("Complex query request: {}", options);

        try {
            // TODO: Implement complex queries
            // For now, just complete with no data
            processor.complete();

        } catch (Exception e) {
            logger.error("Error querying complex data", e);
            processor.error(e);
        }
    }

    @Override
    public Collection<? extends AggregationType> getNativeAggregates() {
        // Return empty list for now - no native aggregates supported yet
        logger.debug("getNativeAggregates() called");
        return Collections.emptyList();
    }
}
