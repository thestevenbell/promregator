package org.cloudfoundry.promregator.endpoint;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import javax.annotation.PostConstruct;
import javax.validation.constraints.Null;

import org.apache.log4j.Logger;
import org.cloudfoundry.promregator.auth.AuthenticationEnricher;
import org.cloudfoundry.promregator.config.PromregatorConfiguration;
import org.cloudfoundry.promregator.config.Target;
import org.cloudfoundry.promregator.fetcher.CFMetricsFetcher;
import org.cloudfoundry.promregator.fetcher.MetricsFetcher;
import org.cloudfoundry.promregator.fetcher.MetricsFetcherMetrics;
import org.cloudfoundry.promregator.fetcher.MetricsFetcherSimulator;
import org.cloudfoundry.promregator.rewrite.AbstractMetricFamilySamplesEnricher;
import org.cloudfoundry.promregator.rewrite.CFMetricFamilySamplesEnricher;
import org.cloudfoundry.promregator.rewrite.GenericMetricFamilySamplesPrefixRewriter;
import org.cloudfoundry.promregator.rewrite.MergableMetricFamilySamples;
import org.cloudfoundry.promregator.scanner.AppInstanceScanner;
import org.cloudfoundry.promregator.scanner.Instance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;

/**
 * An abstract class allowing to easily build a spring-framework HTTP REST-server endpoint, 
 * compliant to the specification of a Prometheus text (!) metrics endpoint,
 * whose data is being backed by a set of further Prometheus metrics endpoints run on one or several CF apps. 
 *
 */

// Warning! Setting @Scope(value=WebApplicationContext.SCOPE_REQUEST) here is useless, as it is
// being ignored by the Framework. Workaround: Annotate the implementing class instead
public abstract class AbstractMetricsEndpoint {
	
	private static final Logger log = Logger.getLogger(AbstractMetricsEndpoint.class);
	
	@Value("${promregator.simulation.enabled:false}")
	private boolean simulationMode;
	
	@Autowired
	private AppInstanceScanner appInstanceScanner;
	
	@Autowired
	private ExecutorService metricsFetcherPool;
	
	@Autowired
	private CollectorRegistry collectorRegistry;
	
	@Value("${cf.proxyHost:@null}")
	private String proxyHost;
	
	@Value("${cf.proxyPort:0}")
	private int proxyPort;

	@Value("${promregator.endpoint.maxProcessingTime:5000}")
	private int maxProcessingTime;
	
	@Autowired
	private PromregatorConfiguration promregatorConfiguration;
	
	@Autowired
	private AuthenticationEnricher ae;
	
	private GenericMetricFamilySamplesPrefixRewriter gmfspr = new GenericMetricFamilySamplesPrefixRewriter("promregator");
	
	/* own metrics --- specific to this (scraping) request */
	private CollectorRegistry requestRegistry;
	
	// see also https://prometheus.io/docs/instrumenting/writing_exporters/#metrics-about-the-scrape-itself
	private Gauge up;
	
	@PostConstruct
	public void setupOwnRequestScopedMetrics() {
		this.requestRegistry = new CollectorRegistry();
		
		this.up = Gauge.build("promregator_up", "Indicator, whether the target of promregator is available")
				.labelNames(CFMetricFamilySamplesEnricher.getEnrichingLabelNames())
				.register(this.requestRegistry);
	}
	
	public String handleRequest(@Null Predicate<? super String> applicationIdFilter, @Null Predicate<? super Instance> instanceFilter) {
		log.debug(String.format("Received request to a metrics endpoint; we have %d targets configured", this.promregatorConfiguration.getTargets().size()));
		Instant start = Instant.now();
		
		this.up.clear();
		
		List<Instance> instanceList = this.appInstanceScanner.determineInstancesFromTargets(this.promregatorConfiguration.getTargets(), applicationIdFilter, instanceFilter);
		log.debug(String.format("Raw list contains %d instances", instanceList.size()));
		
		if (instanceList.isEmpty()) {
			throw new HttpClientErrorException(HttpStatus.NOT_FOUND);
		}
		
		List<MetricsFetcher> callablesPrep = this.createMetricsFetchers(instanceList);
		
		LinkedList<Future<HashMap<String, MetricFamilySamples>>> futures = this.startMetricsFetchers(callablesPrep);
		log.debug(String.format("Fetching metrics from %d distinct endpoints", futures.size()));
		
		MergableMetricFamilySamples mmfs = waitForMetricsFetchers(futures);
		
		Instant stop = Instant.now();
		Duration duration = Duration.between(start, stop);
		this.handleScrapeDuration(this.requestRegistry, duration);
		
		if (this.isIncludeGlobalMetrics()) {
			// also add our own (global) metrics
			mmfs.merge(this.gmfspr.determineEnumerationOfMetricFamilySamples(this.collectorRegistry));
		}
		
		// add also our own request-specific metrics
		mmfs.merge(this.gmfspr.determineEnumerationOfMetricFamilySamples(this.requestRegistry));
		
		return mmfs.toType004String();
	}

	/**
	 * called when scraping has been finished; contains the overall duration of the scraping request.
	 * 
	 * The implementing class is suggested to write the duration into an own sample for the corresponding
	 * metric.
	 * @param requestReqistry the registry to which the metric shall be / is registered.
	 * @param duration the duration of the just completed scrape request.
	 */
	protected abstract void handleScrapeDuration(CollectorRegistry requestRegistry, Duration duration);

	/**
	 * specifies whether the global metrics provided by Promregator itself
	 * shall be added to the response of the scraping request
	 * @return <code>true</code> if the global metrics shall be added in the response; 
	 * <code>false</code> otherwise.
	 */
	protected abstract boolean isIncludeGlobalMetrics();

	private MergableMetricFamilySamples waitForMetricsFetchers(LinkedList<Future<HashMap<String, MetricFamilySamples>>> futures) {
		long starttime = System.currentTimeMillis();
		
		MergableMetricFamilySamples mmfs = new MergableMetricFamilySamples();
		
		for (Future<HashMap<String, MetricFamilySamples>> future : futures) {
			long maxWaitTime = starttime + this.maxProcessingTime - System.currentTimeMillis();
			
			try {
				if (maxWaitTime < 0 && !future.isDone()) {
					// only process those, which are already completed
					continue;
				}
				HashMap<String, MetricFamilySamples> emfs = future.get(maxWaitTime, TimeUnit.MILLISECONDS);
				
				if (emfs != null) {
					mmfs.merge(emfs);
				}
			} catch (InterruptedException e) {
				continue;
			} catch (ExecutionException e) {
				log.warn("Exception thrown while fetching Metrics data from target", e);
				continue;
			} catch (TimeoutException e) {
				log.info("Timeout while fetching metrics data from target", e);
				continue; // process the other's as well!
			}
			
		}
		return mmfs;
	}

	private LinkedList<Future<HashMap<String, MetricFamilySamples>>> startMetricsFetchers(List<MetricsFetcher> callablesPrep) {
		LinkedList<Future<HashMap<String,MetricFamilySamples>>> futures = new LinkedList<>();
		
		for (MetricsFetcher mf : callablesPrep) {
			Future<HashMap<String, MetricFamilySamples>> future = this.metricsFetcherPool.submit(mf);
			
			futures.add(future);
		}
		return futures;
	}

	protected List<MetricsFetcher> createMetricsFetchers(List<Instance> instanceList) {
		
		List<MetricsFetcher> callablesList = new LinkedList<>();
		for (Instance instance : instanceList) {
			log.debug(String.format("Creating Metrics Fetcher for instance %s", instance.getInstanceId()));
			
			Target target = instance.getTarget();
			String orgName = target.getOrgName();
			String spaceName = target.getSpaceName();
			String appName = target.getApplicationName();
			
			String accessURL = instance.getAccessUrl();
			
			if (accessURL == null) {
				log.warn(String.format("Unable to retrieve hostname for %s/%s/%s; skipping", orgName, spaceName, appName));
				continue;
			}
			
			AbstractMetricFamilySamplesEnricher mfse = new CFMetricFamilySamplesEnricher(orgName, spaceName, appName, instance.getInstanceId());
			MetricsFetcherMetrics mfm = new MetricsFetcherMetrics(mfse, up);

			MetricsFetcher mf = null;
			
			if (this.simulationMode) {
				mf = new MetricsFetcherSimulator(accessURL, this.ae, mfse, mfm);
			} else {
				if (this.proxyHost != null && this.proxyPort != 0) {
					mf = new CFMetricsFetcher(accessURL, instance.getInstanceId(), this.ae, mfse, this.proxyHost, this.proxyPort, mfm);
				} else {
					mf = new CFMetricsFetcher(accessURL, instance.getInstanceId(), this.ae, mfse, mfm);
				}
			}
			callablesList.add(mf);
		}
		
		return callablesList;
	}
}
