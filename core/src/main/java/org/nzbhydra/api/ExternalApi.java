package org.nzbhydra.api;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.catalina.connector.ClientAbortException;
import org.nzbhydra.config.ConfigProvider;
import org.nzbhydra.config.category.CategoriesConfig;
import org.nzbhydra.downloading.DownloadResult;
import org.nzbhydra.downloading.FileHandler;
import org.nzbhydra.downloading.InvalidSearchResultIdException;
import org.nzbhydra.logging.LoggingMarkers;
import org.nzbhydra.mapping.newznab.ActionAttribute;
import org.nzbhydra.mapping.newznab.NewznabParameters;
import org.nzbhydra.mapping.newznab.NewznabResponse;
import org.nzbhydra.mapping.newznab.OutputType;
import org.nzbhydra.mapping.newznab.xml.NewznabXmlError;
import org.nzbhydra.mediainfo.Imdb;
import org.nzbhydra.mediainfo.InfoProvider.IdType;
import org.nzbhydra.searching.CategoryProvider;
import org.nzbhydra.searching.SearchResult;
import org.nzbhydra.searching.Searcher;
import org.nzbhydra.searching.dtoseventsenums.DownloadType;
import org.nzbhydra.searching.dtoseventsenums.SearchType;
import org.nzbhydra.searching.searchrequests.SearchRequest;
import org.nzbhydra.searching.searchrequests.SearchRequest.SearchSource;
import org.nzbhydra.searching.searchrequests.SearchRequestFactory;
import org.nzbhydra.web.SessionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
public class ExternalApi {

    private static final int MAX_CACHE_SIZE = 5;
    private static final int MAX_CACHE_AGE_HOURS = 24;

    private static final Logger logger = LoggerFactory.getLogger(ExternalApi.class);

    @Value("${nzbhydra.dev.noApiKey:false}")
    private boolean noApiKeyNeeded = false;

    @Autowired
    protected Searcher searcher;
    @Autowired
    protected SearchRequestFactory searchRequestFactory;
    @Autowired
    protected FileHandler fileHandler;
    @Autowired
    protected ConfigProvider configProvider;
    @Autowired
    private NewznabXmlTransformer newznabXmlTransformer;
    @Autowired
    private NewznabJsonTransformer newznabJsonTransformer;
    @Autowired
    private CategoryProvider categoryProvider;
    @Autowired
    private CapsGenerator capsGenerator;
    protected Clock clock = Clock.systemUTC();
    private Random random = new Random();

    private ConcurrentMap<Integer, CacheEntryValue> cache = new ConcurrentHashMap<>();


    @RequestMapping(value = {"/api", "/rss", "/torznab/api"}, consumes = MediaType.ALL_VALUE)
    public ResponseEntity<? extends Object> api(NewznabParameters params) throws Exception {
        logger.info("Received external {}API call: {}", (isTorznabCall() ? "torznab " : ""), params);

        if (!noApiKeyNeeded && !Objects.equals(params.getApikey(), configProvider.getBaseConfig().getMain().getApiKey())) {
            logger.error("Received API call with wrong API key");
            throw new WrongApiKeyException("Wrong api key");
        }

        if (Stream.of(ActionAttribute.SEARCH, ActionAttribute.BOOK, ActionAttribute.TVSEARCH, ActionAttribute.MOVIE).anyMatch(x -> x == params.getT())) {
            if (params.getCachetime() != null) {
                return handleCachingSearch(params);
            }
            NewznabResponse searchResult = search(params);
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set(HttpHeaders.CONTENT_TYPE, searchResult.getContentHeader());
            if (params.getO() != OutputType.JSON) {
                searchResult.setSearchType(isTorznabCall() ? "torznab" : "newznab");
            }
            return new ResponseEntity<>(searchResult, httpHeaders, HttpStatus.OK);

        }

        if (params.getT() == ActionAttribute.GET) {
            return getNzb(params);
        }

        if (params.getT() == ActionAttribute.CAPS) {
            return capsGenerator.getCaps(params.getO(), isTorznabCall());
        }

        logger.error("Incorrect API request: {}", params);
        NewznabXmlError error = new NewznabXmlError("200", "Unknown or incorrect parameter");
        return new ResponseEntity<Object>(error, HttpStatus.OK);
    }

    protected ResponseEntity<?> handleCachingSearch(NewznabParameters params) {
        //Remove old entries
        cache.entrySet().removeIf(x -> x.getValue().getLastUpdate().isBefore(clock.instant().minus(MAX_CACHE_AGE_HOURS, ChronoUnit.HOURS)));

        CacheEntryValue cacheEntryValue;
        if (cache.containsKey(params.cacheKey())) {
            cacheEntryValue = cache.get(params.cacheKey());
            if (cacheEntryValue.getLastUpdate().isAfter(clock.instant().minus(params.getCachetime(), ChronoUnit.MINUTES))) {
                Instant nextUpdate = cacheEntryValue.getLastUpdate().plus(params.getCachetime(), ChronoUnit.MINUTES);
                logger.info("Returning cached search result. Next update of search will be done at {}", nextUpdate);
                return new ResponseEntity<>(cacheEntryValue.getSearchResult(), HttpStatus.OK);
            } else {
                logger.info("Updating search because cache time is exceeded");
            }
        }
        //Remove oldest entry when max size is reached
        if (cache.size() == MAX_CACHE_SIZE) {
            Optional<Entry<Integer, CacheEntryValue>> keyToEvict = cache.entrySet().stream().min(Comparator.comparing(o -> o.getValue().getLastUpdate()));
            //Should always be the case anyway
            logger.info("Removing oldest entry from cache because its limit of {} is reached", MAX_CACHE_SIZE);
            keyToEvict.ifPresent(newznabParametersCacheEntryValueEntry -> cache.remove(newznabParametersCacheEntryValueEntry.getKey()));
        }

        NewznabResponse searchResult = search(params);
        logger.info("Putting search result into cache");
        cache.put(params.cacheKey(), new CacheEntryValue(params, clock.instant(), searchResult));
        return new ResponseEntity<>(searchResult, HttpStatus.OK);
    }



    protected ResponseEntity<?> getNzb(NewznabParameters params) throws MissingParameterException, UnknownErrorException {
        if (Strings.isNullOrEmpty(params.getId())) {
            throw new MissingParameterException("Missing ID/GUID");
        }
        DownloadResult downloadResult;
        try {

            downloadResult = fileHandler.getFileByGuid(Long.valueOf(params.getId()), configProvider.getBaseConfig().getSearching().getNzbAccessType(), SearchSource.API);
        } catch (InvalidSearchResultIdException e) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body("<error code=\"300\" description=\"Invalid or outdated search result ID\"/>");
        }
        if (!downloadResult.isSuccessful()) {
            throw new UnknownErrorException(downloadResult.getError());
        }

        return downloadResult.getAsResponseEntity();
    }

    protected NewznabResponse search(NewznabParameters params) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        SearchRequest searchRequest = buildBaseSearchRequest(params);
        if (isTorznabCall()) {
            searchRequest.setDownloadType(DownloadType.TORRENT);
        } else {
            searchRequest.setDownloadType(DownloadType.NZB);
        }
        SearchResult searchResult = searcher.search(searchRequest);

        NewznabResponse transformedResults = transformResults(searchResult, params, searchRequest);
        logger.info("Search took {}ms. Returning {} results", stopwatch.elapsed(TimeUnit.MILLISECONDS), searchResult.getSearchResultItems().size());
        return transformedResults;
    }

    private boolean isTorznabCall() {
        return SessionStorage.requestUrl.get() != null && SessionStorage.requestUrl.get().toLowerCase().contains("torznab");
    }

    @ExceptionHandler(value = ExternalApiException.class)
    public NewznabXmlError handler(ExternalApiException e) {
        NewznabXmlError error = new NewznabXmlError(e.getStatusCode(), e.getMessage());
        return error;
    }

    @ExceptionHandler(value = Exception.class)
    public ResponseEntity handleUnexpectedError(Exception e) {
        if (e instanceof ClientAbortException || Throwables.getCausalChain(e).stream().anyMatch(x -> x instanceof ClientAbortException)) {
            logger.warn("Calling tool closed the connection before getting the results");
            return null; //Can't return anything because the connection is closed, obviously
        }
        try {
            logger.error("Unexpected error while handling API request", e);
            if (configProvider.getBaseConfig().getSearching().isWrapApiErrors()) {
                logger.debug("Wrapping error in empty search result");
                return ResponseEntity.status(200).body(newznabXmlTransformer.getRssRoot(Collections.emptyList(), 0, 0, null));
            } else {
                NewznabXmlError error = new NewznabXmlError("900", e.getMessage());
                return ResponseEntity.status(200).body(error);
            }
        } catch (Exception e1) {
            return ResponseEntity.status(200).body("<error code=\"900\" description=\"" + e.getMessage() + "\"");
        }
    }


    protected NewznabResponse transformResults(SearchResult searchResult, NewznabParameters params, SearchRequest searchRequest) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        NewznabResponse response;
        int total = searchResult.getNumberOfTotalAvailableResults() - searchResult.getNumberOfRejectedResults() - searchResult.getNumberOfRemovedDuplicates();
        if (params.getO() == OutputType.JSON) {
            response = newznabJsonTransformer.transformToRoot(searchResult.getSearchResultItems(), params.getOffset(), total, searchRequest);
        } else {
            response = newznabXmlTransformer.getRssRoot(searchResult.getSearchResultItems(), params.getOffset(), total, searchRequest);
        }
        logger.debug(LoggingMarkers.PERFORMANCE, "Transforming results took {}ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
        return response;
    }


    private SearchRequest buildBaseSearchRequest(NewznabParameters params) {
        SearchType searchType = SearchType.valueOf(params.getT().name());
        SearchRequest searchRequest = searchRequestFactory.getSearchRequest(searchType, SearchSource.API, categoryProvider.fromSearchNewznabCategories(params.getCat(), CategoriesConfig.allCategory), random.nextInt(1000000), params.getOffset(), params.getLimit());
        logger.info("Executing new search");
        searchRequest.setQuery(params.getQ());
        searchRequest.setLimit(params.getLimit());
        searchRequest.setOffset(params.getOffset());
        searchRequest.setMinage(params.getMinage()); //Not part of spec
        searchRequest.setMaxage(params.getMaxage());
        searchRequest.setMinsize(params.getMinsize()); //Not part of spec
        searchRequest.setMaxsize(params.getMaxsize()); //Not part of spec
        searchRequest.setAuthor(params.getAuthor());
        searchRequest.setTitle(params.getTitle());
        searchRequest.setSeason(params.getSeason());
        searchRequest.setEpisode(params.getEp());
        if (params.getIndexers() != null && !params.getIndexers().isEmpty()) {
            searchRequest.setIndexers(params.getIndexers());
        }
        if (params.getCat() != null) {
            searchRequest.getInternalData().setNewznabCategories(params.getCat());
        }

        if (!Strings.isNullOrEmpty(params.getTvdbid())) {
            searchRequest.getIdentifiers().put(IdType.TVDB, params.getTvdbid());
        }
        if (!Strings.isNullOrEmpty(params.getTvmazeid())) {
            searchRequest.getIdentifiers().put(IdType.TVMAZE, params.getTvmazeid());
        }
        if (!Strings.isNullOrEmpty(params.getRid())) {
            searchRequest.getIdentifiers().put(IdType.TVRAGE, params.getRid());
        }
        if (!Strings.isNullOrEmpty(params.getImdbid()) && searchType == SearchType.MOVIE) {
            searchRequest.getIdentifiers().put(IdType.IMDB, Imdb.withTt(params.getImdbid()));
        }
        if (!Strings.isNullOrEmpty(params.getImdbid()) && searchType == SearchType.TVSEARCH) {
            searchRequest.getIdentifiers().put(IdType.TVIMDB, Imdb.withTt(params.getImdbid()));
        }
        if (!Strings.isNullOrEmpty(params.getTmdbid())) {
            searchRequest.getIdentifiers().put(IdType.TMDB, params.getTmdbid());
        }
        searchRequest = searchRequestFactory.extendWithSavedIdentifiers(searchRequest);

        return searchRequest;
    }


    @Data
    @AllArgsConstructor
    private static class CacheEntryValue {
        private final NewznabParameters params;
        private final Instant lastUpdate;
        private final NewznabResponse searchResult;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            if (!super.equals(o)) {
                return false;
            }
            CacheEntryValue that = (CacheEntryValue) o;
            return com.google.common.base.Objects.equal(params, that.params) &&
                    com.google.common.base.Objects.equal(lastUpdate, that.lastUpdate) &&
                    com.google.common.base.Objects.equal(searchResult, that.searchResult);
        }

        @Override
        public int hashCode() {
            return com.google.common.base.Objects.hashCode(super.hashCode(), params, lastUpdate, searchResult);
        }
    }

}
