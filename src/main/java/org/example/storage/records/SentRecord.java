package org.example.storage.records;

import org.example.service.ChannelType;

public record SentRecord(
        String article,
        ChannelType channelType,
        String chatId,
        long sentAt,
        double percent
) {
}


