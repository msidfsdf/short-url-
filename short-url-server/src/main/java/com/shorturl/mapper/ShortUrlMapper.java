package com.shorturl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shorturl.entity.po.ShortUrl;
import org.apache.ibatis.annotations.Mapper;

/**
 * 短链 Mapper
 * (MVP 阶段直接用 BaseMapper 通用方法,无需 XML)
 */
@Mapper
public interface ShortUrlMapper extends BaseMapper<ShortUrl> {
}
