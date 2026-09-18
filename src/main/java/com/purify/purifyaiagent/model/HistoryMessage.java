package com.purify.purifyaiagent.model;

import org.springframework.ai.chat.messages.Message;

/**
 * 历史记录的单条消息，用于对外暴露 MySQL 里存了什么。
 *
 * @param type USER / ASSISTANT / SYSTEM / TOOL
 * @param text 消息内容
 */
public record HistoryMessage(String type, String text) {

    public static HistoryMessage from(Message message) {
        return new HistoryMessage(message.getMessageType().name(), message.getText());
    }
}
