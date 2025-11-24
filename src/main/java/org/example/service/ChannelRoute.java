package org.example.service;

public final class ChannelRoute {
    private final ChannelType type;
    private final String chatId;
    private final Integer threadId;
    private final String secondaryChatId;

    public ChannelRoute(ChannelType type, String chatId, Integer threadId, String secondaryChatId) {
        this.type = type;
        this.chatId = chatId;
        this.threadId = threadId;
        this.secondaryChatId = secondaryChatId;
    }

    public ChannelType getType() {
        return type;
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
}


