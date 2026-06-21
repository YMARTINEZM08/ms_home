package com.liverpool.ms_home.adapter.outbound.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liverpool.ms_home.config.ContentstackProperties;
import com.liverpool.ms_home.domain.model.content.ContentQuery;
import com.liverpool.ms_home.domain.model.content.HomeDefinition;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaticBlockCacheAdapterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private Cache<String, HomeDefinition> l1Cache;
    private StaticBlockCacheAdapter adapter;

    private static final ContentstackProperties PROPS = new ContentstackProperties(
            "http://localhost:8082", "LP", "x-preview",
            Duration.ofSeconds(2), Duration.ofSeconds(5),
            "page", "home", Duration.ofMinutes(5), Duration.ofSeconds(30));

    private static final ContentQuery QUERY = new ContentQuery("LP", "es-mx", "home", false);
    private static final String CACHE_KEY = "home:def:LP:es-mx:home:false";
    private static final HomeDefinition DEFINITION = new HomeDefinition("page", "es-mx", null, null, List.of());

    @BeforeEach
    void setUp() {
        l1Cache = Caffeine.newBuilder().maximumSize(10).build();
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        adapter = new StaticBlockCacheAdapter(redisTemplate, l1Cache, new ObjectMapper(), PROPS);
    }

    // ── get — L1 hit ─────────────────────────────────────────────────────────────────────────────

    @Test
    void get_l1Hit_returnsWithoutCallingRedis() {
        l1Cache.put(CACHE_KEY, DEFINITION);

        Optional<HomeDefinition> result = adapter.get(QUERY);

        assertThat(result).isPresent().contains(DEFINITION);
        verify(redisTemplate, never()).opsForValue();
    }

    // ── get — L2 hit ─────────────────────────────────────────────────────────────────────────────

    @Test
    void get_l2Hit_returnsAndPromotesToL1() throws Exception {
        String json = new ObjectMapper().writeValueAsString(DEFINITION);
        when(valueOperations.get(eq(CACHE_KEY))).thenReturn(json);

        Optional<HomeDefinition> result = adapter.get(QUERY);

        assertThat(result).isPresent();
        assertThat(l1Cache.getIfPresent(CACHE_KEY)).isNotNull(); // promoted to L1
    }

    // ── get — both miss ──────────────────────────────────────────────────────────────────────────

    @Test
    void get_bothCachesMiss_returnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn(null);

        Optional<HomeDefinition> result = adapter.get(QUERY);

        assertThat(result).isEmpty();
    }

    // ── get — Redis error ────────────────────────────────────────────────────────────────────────

    @Test
    void get_redisReadThrows_returnsEmptyDefensively() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<HomeDefinition> result = adapter.get(QUERY);

        assertThat(result).isEmpty(); // graceful degradation
    }

    @Test
    void get_redisReturnsMalformedJson_evictsAndReturnsEmpty() {
        when(valueOperations.get(anyString())).thenReturn("{bad-json}}}");

        Optional<HomeDefinition> result = adapter.get(QUERY);

        assertThat(result).isEmpty();
    }

    // ── put ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void put_populatesL1Immediately() {
        adapter.put(QUERY, DEFINITION);

        assertThat(l1Cache.getIfPresent(CACHE_KEY)).isEqualTo(DEFINITION);
    }

    @Test
    void put_writesToRedisWithTtl() {
        adapter.put(QUERY, DEFINITION);

        verify(valueOperations).set(eq(CACHE_KEY), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void put_redisWriteError_l1StillPopulated() {
        org.mockito.Mockito.doThrow(new RuntimeException("Redis write failed"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        adapter.put(QUERY, DEFINITION); // must not throw

        assertThat(l1Cache.getIfPresent(CACHE_KEY)).isEqualTo(DEFINITION); // L1 still warm
    }

    // ── cache key ────────────────────────────────────────────────────────────────────────────────

    @Test
    void get_previewAndNonPreviewHaveSeparateCacheEntries() throws Exception {
        ContentQuery nonPreview = new ContentQuery("LP", "es-mx", "home", false);
        ContentQuery preview    = new ContentQuery("LP", "es-mx", "home", true);

        HomeDefinition live       = new HomeDefinition("page", "es-mx", null, null, List.of());
        HomeDefinition previewDef = new HomeDefinition("page", "es-mx", null, null, List.of());

        String liveKey    = "home:def:LP:es-mx:home:false";
        String previewKey = "home:def:LP:es-mx:home:true";

        ObjectMapper mapper = new ObjectMapper();
        when(valueOperations.get(eq(liveKey))).thenReturn(mapper.writeValueAsString(live));
        when(valueOperations.get(eq(previewKey))).thenReturn(mapper.writeValueAsString(previewDef));

        Optional<HomeDefinition> liveResult    = adapter.get(nonPreview);
        Optional<HomeDefinition> previewResult = adapter.get(preview);

        assertThat(liveResult).isPresent();
        assertThat(previewResult).isPresent();
        // Keys differ — both were requested independently.
        verify(valueOperations).get(eq(liveKey));
        verify(valueOperations).get(eq(previewKey));
    }
}
