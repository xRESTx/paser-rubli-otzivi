package org.example.storage.commands;

import java.sql.Connection;
import java.sql.SQLException;

public interface StorageCommand {
    void execute(Connection connection) throws SQLException;
}


