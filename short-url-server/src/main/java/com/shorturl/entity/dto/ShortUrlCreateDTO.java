package com.shorturl.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生成短链 请求参数
 */
@Data
public class ShortUrlCreateDTO {

    /** 原始URL */
    @NotBlank(message = "原始URL不能为空")
    @Size(max = 500, message = "URL长度不能超过500")
    private String originalUrl;

    /** 过期秒数(可空,空表示永久; 例如 86400 表示 1 天) */
    private Long expireSeconds;
}
