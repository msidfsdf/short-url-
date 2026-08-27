package com.shorturl.utils;

/**
 * 10进制 <-> 62进制 转换工具
 *
 * 字符集: 0-9 (10) + a-z (26) + A-Z (26) = 62 个字符
 *
 * 例子:
 *   1000000 (10进制) -> "4c9g" (62进制, 4位字符)
 *   999999  (10进制) -> "4c91" (62进制, 4位字符)
 *
 * 容量参考:
 *   6 位短码 = 62^6 = 568 亿 个组合
 */
public class Base62Utils {

    private static final String CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = 62;

    private Base62Utils() {}

    /**
     * 10 进制 -> 62 进制字符串
     */
    public static String encode(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("num 不能为负数: " + num);
        }
        if (num == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.insert(0, CHARS.charAt((int) (num % BASE)));
            num /= BASE;
        }
        return sb.toString();
    }

    /**
     * 62 进制字符串 -> 10 进制数
     */
    public static long decode(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("字符串不能为空");
        }
        long result = 0;
        for (int i = 0; i < str.length(); i++) {
            int idx = CHARS.indexOf(str.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("非法字符: " + str.charAt(i));
            }
            result = result * BASE + idx;
        }
        return result;
    }
}
