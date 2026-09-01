package com.shorturl;

import com.shorturl.utils.RedisBloomFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 布隆过滤器测试 (阶段4)
 *
 * 用独立的 Redis key, 避免把应用正在用的位图清掉。
 * 需要本地 Redis 可用。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "short-url.cache-guard.bloom.key=short:url:bloom:test",
        "short-url.cache-guard.bloom.expected-insertions=10000",
        "short-url.cache-guard.bloom.false-probability=0.01"
})
class RedisBloomFilterTest {

    @Autowired
    private RedisBloomFilter bloomFilter;

    @BeforeEach
    void setUp() {
        bloomFilter.reset();
    }

    @Test
    @DisplayName("布隆过滤器绝不漏判: 加进去的元素必须全部判定为可能存在")
    void shouldNeverFalseNegative() {
        int n = 2000;
        List<String> inserted = IntStream.range(0, n)
                .mapToObj(i -> "code" + i)
                .toList();

        bloomFilter.addBatch(inserted);

        for (String s : inserted) {
            assertThat(bloomFilter.mightContain(s))
                    .as("已插入的元素不能被判为不存在: %s", s)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("误判率实测: 应接近配置的 1%, 且不超过 5%")
    void falsePositiveRateShouldMatchConfig() {
        int n = 2000;
        List<String> inserted = IntStream.range(0, n)
                .mapToObj(i -> "inserted-" + i)
                .toList();
        bloomFilter.addBatch(inserted);

        int probes = 20000;
        int falsePositive = 0;
        for (int i = 0; i < probes; i++) {
            if (bloomFilter.mightContain("absent-" + i)) {
                falsePositive++;
            }
        }
        double rate = (double) falsePositive / probes;

        System.out.printf("实测误判率 = %.3f%% (%d/%d), 配置值 1%%%n",
                rate * 100, falsePositive, probes);

        // 允许一定波动: 布隆的实测误判率受 hash 函数质量影响,
        // 这里只卡一个宽松上界, 防止实现退化成"永远返回 true"
        assertThat(rate).isLessThan(0.05);
    }

    @Test
    @DisplayName("空位图时, 任意元素都应判定为不存在")
    void emptyFilterShouldRejectEverything() {
        for (int i = 0; i < 100; i++) {
            assertThat(bloomFilter.mightContain("whatever-" + i)).isFalse();
        }
    }

    @Test
    @DisplayName("单个 add 之后立刻 mightContain 应返回 true")
    void addThenMightContain() {
        String code = "abc123";
        assertThat(bloomFilter.mightContain(code)).isFalse();

        bloomFilter.add(code);

        assertThat(bloomFilter.mightContain(code)).isTrue();
    }

    @Test
    @DisplayName("随机 UUID 批量插入后不应漏判 (模拟真实短码分布)")
    void randomCodesShouldNotBeMissed() {
        List<String> codes = IntStream.range(0, 500)
                .mapToObj(i -> UUID.randomUUID().toString().substring(0, 8))
                .toList();

        bloomFilter.addBatch(codes);

        for (String c : codes) {
            assertThat(bloomFilter.mightContain(c)).isTrue();
        }
    }

    @Test
    @DisplayName("配置应正确生效: n=1万/p=1% 由 Redisson 内部推导 m 与 k")
    void parametersShouldBeAppliedCorrectly() {
        System.out.printf("expectedInsertions = %d, falseProbability = %s%n",
                bloomFilter.getExpectedInsertions(), bloomFilter.getFalseProbability());

        assertThat(bloomFilter.getExpectedInsertions()).isEqualTo(10000L);
        assertThat(bloomFilter.getFalseProbability()).isEqualTo(0.01);
        assertThat(bloomFilter.isReady()).isTrue();
    }
}
