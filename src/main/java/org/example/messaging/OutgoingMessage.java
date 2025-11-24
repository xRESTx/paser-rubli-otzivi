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
    private final double price;
    private final ProductSnapshot snapshot;
    private final int delaySeconds;
    private final byte[] imageBytes;

    public OutgoingMessage(ChannelType channelType,
                           String chatId,
                           Integer threadId,
                           String secondaryChatId,
                           String payload,
                           String article,
                           double percent) {
        this(channelType, chatId, threadId, secondaryChatId, payload, article, percent, 0.0, null, 0, null);
    }

    public OutgoingMessage(ChannelType channelType,
                           String chatId,
                           Integer threadId,
                           String secondaryChatId,
                           String payload,
                           String article,
                           double percent,
                           double price) {
        this(channelType, chatId, threadId, secondaryChatId, payload, article, percent, price, null, 0, null);
    }

    private OutgoingMessage(ChannelType channelType,
                           String chatId,
                           Integer threadId,
                           String secondaryChatId,
                           String payload,
                           String article,
                           double percent,
                           double price,
                           ProductSnapshot snapshot,
                           int delaySeconds,
                           byte[] imageBytes) {
        this.channelType = channelType;
        this.chatId = chatId;
        this.threadId = threadId;
        this.secondaryChatId = secondaryChatId;
        this.payload = payload;
        this.article = article;
        this.percent = percent;
        this.price = price;
        this.snapshot = snapshot;
        this.delaySeconds = delaySeconds;
        this.imageBytes = imageBytes;
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

    public double getPrice() {
        return price;
    }

    public ProductSnapshot getSnapshot() {
        return snapshot;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }

    public byte[] getImageBytes() {
        return imageBytes;
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
                this.price,
                snapshot,
                this.delaySeconds,
                this.imageBytes
        );
    }

    public OutgoingMessage withDelay(int delaySeconds) {
        return new OutgoingMessage(
                this.channelType,
                this.chatId,
                this.threadId,
                this.secondaryChatId,
                this.payload,
                this.article,
                this.percent,
                this.price,
                this.snapshot,
                delaySeconds,
                this.imageBytes
        );
    }
    
    public OutgoingMessage withUpdatedPayload(String newPayload) {
        return new OutgoingMessage(
                this.channelType,
                this.chatId,
                this.threadId,
                this.secondaryChatId,
                newPayload,
                this.article,
                this.percent,
                this.price,
                this.snapshot,
                this.delaySeconds,
                this.imageBytes
        );
    }
    
    public OutgoingMessage withImageBytes(byte[] imageBytes) {
        return new OutgoingMessage(
                this.channelType,
                this.chatId,
                this.threadId,
                this.secondaryChatId,
                this.payload,
                this.article,
                this.percent,
                this.price,
                this.snapshot,
                this.delaySeconds,
                imageBytes
        );
    }
}


