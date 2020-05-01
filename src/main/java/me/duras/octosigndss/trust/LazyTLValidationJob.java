package me.duras.octosigndss.trust;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.client.http.DSSFileLoader;
import eu.europa.esig.dss.spi.tsl.TLValidationJobSummary;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.tsl.alerts.Alert;
import eu.europa.esig.dss.tsl.alerts.Alerter;
import eu.europa.esig.dss.tsl.cache.CacheCleaner;
import eu.europa.esig.dss.tsl.cache.CacheKey;
import eu.europa.esig.dss.tsl.cache.access.CacheAccessByKey;
import eu.europa.esig.dss.tsl.cache.access.CacheAccessFactory;
import eu.europa.esig.dss.tsl.cache.access.ReadOnlyCacheAccess;
import eu.europa.esig.dss.tsl.dto.ParsingCacheDTO;
import eu.europa.esig.dss.tsl.job.LOTLChangeApplier;
import eu.europa.esig.dss.tsl.job.TLSourceBuilder;
import eu.europa.esig.dss.tsl.runnable.LOTLAnalysis;
import eu.europa.esig.dss.tsl.runnable.LOTLWithPivotsAnalysis;
import eu.europa.esig.dss.tsl.runnable.TLAnalysis;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.tsl.source.TLSource;
import eu.europa.esig.dss.tsl.summary.ValidationJobSummaryBuilder;
import eu.europa.esig.dss.tsl.sync.AcceptAllStrategy;
import eu.europa.esig.dss.tsl.sync.SynchronizationStrategy;
import eu.europa.esig.dss.tsl.sync.TrustedListCertificateSourceSynchronizer;
import eu.europa.esig.dss.utils.Utils;

/**
 * The main class performing the TL/LOTL download / parsing / validation tasks
 *
 */
public class LazyTLValidationJob {
    public final static Pattern canonicalizedCountryPattern = Pattern.compile(".*,?c=(\\w+),?.*",
            Pattern.CASE_INSENSITIVE);

    private ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * Contains all caches for the current validation job
     */
    private CacheAccessFactory cacheAccessFactory = new CacheAccessFactory();

    /**
     * Array of zero, one or more Trusted List (TL) sources.
     * 
     * These trusted lists are not referenced in a List Of Trusted Lists (LOTL)
     */
    private TLSource[] trustedListSources;

    /**
     * Array of zero, one or more List Of Trusted List (LOTL) sources.
     */
    private LOTLSource[] listOfTrustedListSources;

    /**
     * The DSSFileLoader used for offline data loading from a local source
     */
    private DSSFileLoader offlineLoader;

    /**
     * The DSSFileLoader used for online data loading from a remote source
     */
    private DSSFileLoader onlineLoader;

    /**
     * Used to clean the cache
     */
    private CacheCleaner cacheCleaner;

    /**
     * The certificate source to be synchronized
     */
    private TrustedListsCertificateSource trustedListCertificateSource;

    /**
     * The strategy to follow to synchronize the certificates.
     * 
     * Default : all trusted lists and LOTLs are synchronized
     */
    private SynchronizationStrategy synchronizationStrategy = new AcceptAllStrategy();

    /**
     * This property allows to print the cache content before and after the
     * synchronization (default : false)
     */
    private boolean debug = false;

    /**
     * List of all alerts
     */
    private List<Alert<?>> alerts;

    private Set<String> requiredCountries;

    LazyTLValidationJob(Set<String> requiredCountries) {
        this.requiredCountries = requiredCountries;
    }

    public void setTrustedListSources(TLSource... trustedListSources) {
        this.trustedListSources = trustedListSources;
    }

    public void setListOfTrustedListSources(LOTLSource... listOfTrustedListSources) {
        this.listOfTrustedListSources = listOfTrustedListSources;
    }

    public void setExecutorService(ExecutorService executorService) {
        if (this.executorService != null && !this.executorService.isShutdown()) {
            this.executorService.shutdownNow();
        }
        this.executorService = executorService;
    }

    /**
     * Sets the offline DSSFileLoader used for data loading from the local source
     * 
     * @param offlineLoader {@link DSSFileLoader}
     */
    public void setOfflineDataLoader(DSSFileLoader offlineLoader) {
        this.offlineLoader = offlineLoader;
    }

    /**
     * Sets the online DSSFileLoader used for data loading from a remote source
     * 
     * @param onlineLoader {@link DSSFileLoader}
     */
    public void setOnlineDataLoader(DSSFileLoader onlineLoader) {
        this.onlineLoader = onlineLoader;
    }

    /**
     * Sets the cacheCleaner
     * 
     * @param cacheCleaner {@link CacheCleaner}
     */
    public void setCacheCleaner(final CacheCleaner cacheCleaner) {
        this.cacheCleaner = cacheCleaner;
    }

    /**
     * Sets the TrustedListsCertificateSource to be filled with the job
     * 
     * @param trustedListCertificateSource the TrustedListsCertificateSource to fill
     *                                     with the job results
     */
    public void setTrustedListCertificateSource(TrustedListsCertificateSource trustedListCertificateSource) {
        this.trustedListCertificateSource = trustedListCertificateSource;
    }

    /**
     * Sets the strategy to follow for the certificate synchronization
     * 
     * @param synchronizationStrategy the different options for the certificate
     *                                synchronization
     */
    public void setSynchronizationStrategy(SynchronizationStrategy synchronizationStrategy) {
        Objects.requireNonNull(synchronizationStrategy, "The SynchronizationStrategy cannot be null");
        this.synchronizationStrategy = synchronizationStrategy;
    }

    /**
     * Sets the debug mode (print the cache contents before and after the
     * synchronization)
     * 
     * @param debug TRUE to enable the debug mode (default = false)
     */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /**
     * Sets the alerts to be checked
     * 
     * @param alerts
     */
    public void setAlerts(List<Alert<?>> alerts) {
        this.alerts = alerts;
    }

    /**
     * Returns validation job summary for all processed LOTL / TLs
     * 
     * @return {@link TLValidationJobSummary}
     */
    public synchronized TLValidationJobSummary getSummary() {
        return new ValidationJobSummaryBuilder(cacheAccessFactory.getReadOnlyCacheAccess(), trustedListSources,
                listOfTrustedListSources).build();
    }

    /**
     * Used to execute the refresh in offline mode (no date from remote sources will
     * be downloaded) By default used on initialization
     */
    public synchronized void offlineRefresh() {
        Objects.requireNonNull(offlineLoader, "The offlineLoader must be defined!");
        refresh(offlineLoader);
    }

    /**
     * Used to execute the refresh in online mode (all data will be updated from
     * remote sources) Used as default database update.
     */
    public synchronized void onlineRefresh() {
        Objects.requireNonNull(onlineLoader, "The onlineLoader must be defined!");
        refresh(onlineLoader);
    }

    private void refresh(DSSFileLoader dssFileLoader) {

        List<TLSource> currentTLSources = new ArrayList<>();
        if (trustedListSources != null) {
            currentTLSources.addAll(Arrays.asList(trustedListSources));
        }

        // Execute all LOTLs
        if (Utils.isArrayNotEmpty(listOfTrustedListSources)) {
            final List<LOTLSource> lotlList = Arrays.asList(listOfTrustedListSources);

            executeLOTLSourcesAnalysis(lotlList, dssFileLoader);

            // Check LOTLs consistency

            // extract TLSources from cached LOTLs
            currentTLSources.addAll(extractTlSources(lotlList));
        }

        // And then, execute all TLs (manual configs + TLs from LOTLs)
        executeTLSourcesAnalysis(currentTLSources, dssFileLoader);

        // alerts()
        if (Utils.isCollectionNotEmpty(alerts)) {
            TLValidationJobSummary jobSummary = getSummary();
            Alerter alerter = new Alerter(jobSummary, alerts);
            alerter.detectChanges();
        }

        if (debug) {
            cacheAccessFactory.getDebugCacheAccess().dump();
        }

        // TLCerSource sync + cache sync if needed
        synchronizeTLCertificateSource();

        executeCacheCleaner();

        if (debug) {
            cacheAccessFactory.getDebugCacheAccess().dump();
        }
    }

    private void executeLOTLSourcesAnalysis(List<LOTLSource> lotlSources, DSSFileLoader dssFileLoader) {
        checkNoDuplicateUrls(lotlSources);

        int nbLOTLSources = lotlSources.size();

        Map<CacheKey, ParsingCacheDTO> oldParsingValues = extractParsingCache(lotlSources);

        CountDownLatch latch = new CountDownLatch(nbLOTLSources);
        for (LOTLSource lotlSource : lotlSources) {
            final CacheAccessByKey cacheAccess = cacheAccessFactory.getCacheAccess(lotlSource.getCacheKey());
            if (lotlSource.isPivotSupport()) {
                executorService
                        .submit(new LOTLWithPivotsAnalysis(cacheAccessFactory, lotlSource, dssFileLoader, latch));
            } else {
                executorService.submit(new LOTLAnalysis(lotlSource, cacheAccess, dssFileLoader, latch));
            }
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<CacheKey, ParsingCacheDTO> newParsingValues = extractParsingCache(lotlSources);

        // Analyze introduced changes for TLs + adapt cache for TLs (EXPIRED)
        final LOTLChangeApplier lotlChangeApplier = new LOTLChangeApplier(cacheAccessFactory.getTLChangesCacheAccess(),
                oldParsingValues, newParsingValues);
        lotlChangeApplier.analyzeAndApply();
    }

    private List<TLSource> extractTlSources(List<LOTLSource> lotlList) {
        TLSourceBuilder tlSourceBuilder = new TLSourceBuilder(lotlList, extractParsingCache(lotlList));
        return tlSourceBuilder.build();
    }

    private Map<CacheKey, ParsingCacheDTO> extractParsingCache(List<LOTLSource> lotlSources) {
        final ReadOnlyCacheAccess readOnlyCacheAccess = cacheAccessFactory.getReadOnlyCacheAccess();
        return lotlSources.stream().collect(Collectors.toMap(LOTLSource::getCacheKey,
                s -> readOnlyCacheAccess.getParsingCacheDTO(s.getCacheKey())));
    }

    private void executeTLSourcesAnalysis(List<TLSource> tlSources, DSSFileLoader dssFileLoader) {
        List<TLSource> requiredTlSources = tlSources.stream().filter((TLSource source) -> {
            for (CertificateToken cert : source.getCertificateSource().getCertificates()) {
                Matcher matcher = canonicalizedCountryPattern.matcher(cert.getCanonicalizedSubject());
                if (matcher.find() && requiredCountries.contains(matcher.group(1)))
                    return true;
            }
            return false;
        }).collect(Collectors.toList());

        int nbTLSources = requiredTlSources.size();
        if (nbTLSources == 0) {
            return;
        }

        checkNoDuplicateUrls(requiredTlSources);

        CountDownLatch latch = new CountDownLatch(nbTLSources);
        for (TLSource tlSource : requiredTlSources) {
            final CacheAccessByKey cacheAccess = cacheAccessFactory.getCacheAccess(tlSource.getCacheKey());
            executorService.submit(new TLAnalysis(tlSource, cacheAccess, dssFileLoader, latch));
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void synchronizeTLCertificateSource() {
        if (trustedListCertificateSource == null) {
            return;
        }

        TrustedListCertificateSourceSynchronizer synchronizer = new TrustedListCertificateSourceSynchronizer(
                trustedListSources, listOfTrustedListSources, trustedListCertificateSource, synchronizationStrategy,
                cacheAccessFactory.getSynchronizerCacheAccess());
        synchronizer.sync();
    }

    private void executeCacheCleaner() {
        if (cacheCleaner == null) {
            return;
        }

        Set<CacheKey> cacheKeys = cacheAccessFactory.getReadOnlyCacheAccess().getAllCacheKeys();
        for (CacheKey cacheKey : cacheKeys) {
            final CacheAccessByKey cacheAccess = cacheAccessFactory.getCacheAccess(cacheKey);
            cacheCleaner.clean(cacheAccess);
        }
    }

    /**
     * Duplicate urls mean cache conflict.
     * 
     * @param sources a list of TLSource
     */
    private void checkNoDuplicateUrls(List<? extends TLSource> sources) {
        List<String> allUrls = sources.stream().map(s -> s.getUrl()).collect(Collectors.toList());
        Set<String> uniqueUrls = new HashSet<>(allUrls);
        if (allUrls.size() > uniqueUrls.size()) {
            throw new DSSException(String.format("Duplicate urls found : %s", allUrls));
        }
    }

}
