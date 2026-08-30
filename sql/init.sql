-- ============================================================
-- 短链系统 建库建表脚本 (阶段1 MVP)
-- ============================================================

CREATE DATABASE IF NOT EXISTS short_url DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE short_url;

-- 短链表
DROP TABLE IF EXISTS t_short_url;
CREATE TABLE t_short_url (
    id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    short_code  VARCHAR(10)   NULL     COMMENT '短链码(62进制编码, 两阶段写入, 先INSERT拿ID再UPDATE编码)',
    original_url VARCHAR(500)  NOT NULL COMMENT '原始URL',
    click_count  BIGINT        NOT NULL DEFAULT 0 COMMENT '点击次数',
    expire_time  DATETIME      NULL     COMMENT '过期时间(可空,空表示永久)',
    create_time  DATETIME      NULL DEFAULT NULL COMMENT '创建时间',
    update_time  DATETIME      NULL DEFAULT NULL COMMENT '更新时间',
    UNIQUE KEY uk_short_code (short_code),
    -- 前缀唯一索引: 防并发创建同一长链产生多个短码
    -- 注意: MySQL 5.5 utf8mb4 单索引限 767 字节, 191*4=764 < 767, 500*4=2000 超限
    -- MySQL 8+ 可改用全列唯一索引, 生产环境建议升级后改
    UNIQUE KEY uk_original_url (original_url(191))
) ENGINE=InnoDB AUTO_INCREMENT=1000000 DEFAULT CHARSET=utf8mb4 COMMENT='短链表';

-- 说明: AUTO_INCREMENT 从 1000000 开始
-- 目的: 让 62 进制编码后短码至少 4 位字符,避免出现 "1"/"2" 这种 1 位短码
-- 1000000 的 62 进制 = "4c9g"
