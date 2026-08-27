-- 修复: short_code 列允许 NULL (两阶段写入: 先INSERT拿ID, 再UPDATE短码)
ALTER TABLE t_short_url MODIFY COLUMN short_code VARCHAR(10) NULL COMMENT '短链码(62进制编码, 两阶段写入)';