package com.shorturl.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shorturl.common.R;
import com.shorturl.entity.dto.ShortUrlCreateDTO;
import com.shorturl.entity.vo.ShortUrlVO;
import com.shorturl.service.IShortUrlService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * 短链 Controller
 *
 * 接口列表:
 *  POST /api/short-url/create  生成短链    入参: {originalUrl, expireSeconds?}
 *  GET  /api/short-url/list    分页查询    入参: current?, size?
 *  GET  /{shortCode}           短链跳转    返回 302 重定向
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ShortUrlController {

    private final IShortUrlService shortUrlService;

    /**
     * 生成短链
     */
    @PostMapping("/api/short-url/create")
    public R<ShortUrlVO> create(@Valid @RequestBody ShortUrlCreateDTO dto) {
        return R.ok(shortUrlService.createShortUrl(dto));
    }

    /**
     * 分页查询
     */
    @GetMapping("/api/short-url/list")
    public R<Page<ShortUrlVO>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(shortUrlService.page(current, size));
    }

    /**
     * 短链跳转: 访问 http://localhost:8081/{shortCode}
     * 返回 302 + Location 实现浏览器自动跳转
     *
     * 用 302 而不是 301: 302 是临时重定向,浏览器不会缓存,每次都会请求服务端
     *                    -> 这样才能统计点击次数
     */
    @GetMapping("/{shortCode}")
    public void redirect(@PathVariable String shortCode, HttpServletResponse response)
            throws IOException {
        String originalUrl = shortUrlService.getOriginalUrl(shortCode);
        if (originalUrl == null) {
            response.setStatus(404);
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write("短链不存在或已过期");
            return;
        }
        response.setStatus(302);
        response.setHeader("Location", originalUrl);
    }
}
