package com.shorturl.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点击事件消息
 *
 * 生产者(跳转流程)发送, 消费者(统计端)接收, 用于异步累加 click_count。
 * 只携带最小必要字段, 与 302 响应无关, 可完全脱离请求关键路径异步处理。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickMessage implements Serializable {

    /** 短链码 */
    private String shortCode;

    /** 点击发生时间戳(毫秒), 留作阶段4 点击趋势分析使用 */
    private Long timestamp;
}
