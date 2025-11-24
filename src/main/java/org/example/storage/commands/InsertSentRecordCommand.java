package org.example.storage.commands;

import org.example.storage.records.SentRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class InsertSentRecordCommand implements StorageCommand {
    private static final String SQL = """
            INSERT OR IGNORE INTO sent_posts(nm_id, channel, chat_id, sent_at, percent)
            VALUES(?, ?, ?, ?, ?)
            """;

    private final SentRecord record;

    public InsertSentRecordCommand(SentRecord record) {
        this.record = record;
    }

    @Override
    public void execute(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setLong(1, Long.parseLong(record.article()));
            statement.setString(2, record.channelType().name());
            statement.setString(3, record.chatId());
            statement.setLong(4, record.sentAt());
            // Округляем percent до 4 знаков после запятой для избежания проблем с точностью
            double roundedPercent = Math.round(record.percent() * 10000.0) / 10000.0;
            statement.setDouble(5, roundedPercent);
            statement.executeUpdate();
        }
    }
}


