package com.shorturl.entity.vo;

import lombok.Data;

/**
 * 短链 返回视图对象
 */
@Data
public class ShortUrlVO {

    /** 主键ID */
    private Long id;

    /** 短链码 */
    private String shortCode;

    /** 完整短链(含域名前缀) */
    private String shortUrl;

    /** 原始URL */
    private String originalUrl;

    /** 点击次数 */
    private Long clickCount;

    /** 创建时间 */
    private String createTime;
}
