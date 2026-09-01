package com.shorturl.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存值包装 (阶段4 逻辑过期模式专用)
 *
 * 为什么需要它:
 *  逻辑过期方案要求缓存**不设真实 TTL**(否则就没有"旧值可返回"了),
 *  所以过期时间只能存在 value 里面, 由应用自己判断。
 *
 * 三个字段:
 *  u = 原始 URL
 *  e = 逻辑过期时间戳(毫秒), 过期后触发异步重建
 *  n = 是否为空值标记(对应短码不存在/已过期)
 *
 * 兼容说明:
 *  阶段1~3 写入的缓存是"裸 URL 字符串", 不是 JSON。
 *  parse() 解析失败会返回 null, 调用方按"从未缓存"处理并走重建路径, 自动自愈。
 */
@Data
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class CacheValue {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 原始 URL, 空值标记时为 null */
    private String u;

    /** 逻辑过期时间戳(毫秒) */
    private long e;

    /** 是否为空值标记 */
    private boolean n;

    public CacheValue() {
    }

    public static CacheValue of(String url, long expireAtMillis) {
        CacheValue v = new CacheValue();
        v.u = url;
        v.e = expireAtMillis;
        v.n = false;
        return v;
    }

    public static CacheValue nullValue(long expireAtMillis) {
        CacheValue v = new CacheValue();
        v.u = null;
        v.e = expireAtMillis;
        v.n = true;
        return v;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > e;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException ex) {
            log.error("缓存值序列化失败", ex);
            return null;
        }
    }

    /**
     * 解析缓存值。
     *
     * @return 解析成功返回对象; JSON 非法或为空返回 null
     *         (包括阶段1~3 遗留的裸 URL 字符串, 调用方会走重建路径自动覆盖)
     */
    public static CacheValue parse(String json) {
        if (json == null || json.isEmpty() || json.charAt(0) != '{') {
            return null;
        }
        try {
            return MAPPER.readValue(json, CacheValue.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
