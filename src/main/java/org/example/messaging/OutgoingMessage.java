package org.example.messaging;

import org.example.service.ChannelType;
import org.example.storage.records.ProductSnapshot;

public final class OutgoingMessage {
    private final ChannelType channelType;
    private final String chatId;
    private final Integer threadId;
    private final String secondaryChatId;
    private final String payload;
    private final String article;
    private final double percent;
    private final ProductSnapshot snapshot;

    public OutgoingMessage(ChannelType channelType,
                           String chatId,
                           Integer threadId,
                           String secondaryChatId,
                           String payload,
                           String article,
                           double percent) {
        this(channelType, chatId, threadId, secondaryChatId, payload, article, percent, null);
    }

    private OutgoingMessage(ChannelType channelType,
                           String chatId,
                           Integer threadId,
                           String secondaryChatId,
                           String payload,
                           String article,
                           double percent,
                           ProductSnapshot snapshot) {
        this.channelType = channelType;
        this.chatId = chatId;
        this.threadId = threadId;
        this.secondaryChatId = secondaryChatId;
        this.payload = payload;
        this.article = article;
        this.percent = percent;
        this.snapshot = snapshot;
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    public String getChatId() {
        return chatId;
    }

    public Integer getThreadId() {
        return threadId;
    }

    public String getSecondaryChatId() {
        return secondaryChatId;
    }

    public String getPayload() {
        return payload;
    }

    public String getArticle() {
        return article;
    }

    public double getPercent() {
        return percent;
    }

    public ProductSnapshot getSnapshot() {
        return snapshot;
    }

    public OutgoingMessage withSnapshot(ProductSnapshot snapshot) {
        return new OutgoingMessage(
                this.channelType,
                this.chatId,
                this.threadId,
                this.secondaryChatId,
                this.payload,
                this.article,
                this.percent,
                snapshot
        );
    }
}


