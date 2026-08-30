-- ============================================================
-- 修复: original_url 加唯一索引, 防止并发创建同一长链产生多个短码
-- 对应 Java 代码改动: ShortUrlServiceImpl.createShortUrl() 捕获 DuplicateKeyException 回查
--
-- 注意: 本机 MySQL 5.5.40, utf8mb4 下单索引限 767 字节, 500*4=2000 超限
--       用前缀唯一索引 (original_url(191)) 刚好卡边界 (191*4=764)
--       绝大多数 URL 在前 191 字符就不同, 实际可接受
-- ============================================================

USE short_url;

-- 1. 先清理已有的重复 original_url (保留最早创建的那条)
DELETE t1 FROM t_short_url t1
INNER JOIN t_short_url t2
WHERE t1.original_url = t2.original_url
  AND t1.id > t2.id;

-- 2. 删掉旧的普通前缀索引
ALTER TABLE t_short_url DROP INDEX idx_original_url;

-- 3. 加前缀唯一索引 (utf8mb4 191 字符 = 764 字节, < 767 限制)
ALTER TABLE t_short_url ADD UNIQUE KEY uk_original_url (original_url(191));
