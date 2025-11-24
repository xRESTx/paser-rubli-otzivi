package org.example.storage.commands;

import org.example.storage.records.ProductSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class PersistSnapshotCommand implements StorageCommand {

    private static final String UPSERT_PRODUCT = """
            INSERT INTO products(nm_id, name, supplier_id, supplier_name, category_url, first_seen_at, last_seen_at,
                                 last_price, last_feedback, last_stock)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(nm_id) DO UPDATE SET
                name=excluded.name,
                supplier_id=excluded.supplier_id,
                supplier_name=excluded.supplier_name,
                category_url=excluded.category_url,
                last_seen_at=excluded.last_seen_at,
                last_price=excluded.last_price,
                last_feedback=excluded.last_feedback,
                last_stock=excluded.last_stock
            """;

    private static final String INSERT_HISTORY = """
            INSERT INTO price_history(nm_id, observed_at, price, feedback, stock, percent, channel_mask)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """;

    private final ProductSnapshot snapshot;

    public PersistSnapshotCommand(ProductSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void execute(Connection connection) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_PRODUCT);
             PreparedStatement history = connection.prepareStatement(INSERT_HISTORY)) {
            upsert.setLong(1, snapshot.nmId());
            upsert.setString(2, snapshot.name());
            upsert.setLong(3, snapshot.supplierId());
            upsert.setString(4, snapshot.supplierName());
            upsert.setString(5, snapshot.categoryUrl());
            upsert.setLong(6, snapshot.observedAt());
            upsert.setLong(7, snapshot.observedAt());
            upsert.setLong(8, snapshot.price());
            upsert.setLong(9, snapshot.feedback());
            upsert.setLong(10, snapshot.stock());
            upsert.executeUpdate();

            history.setLong(1, snapshot.nmId());
            history.setLong(2, snapshot.observedAt());
            history.setLong(3, snapshot.price());
            history.setLong(4, snapshot.feedback());
            history.setLong(5, snapshot.stock());
            history.setDouble(6, snapshot.percent());
            history.setInt(7, snapshot.channelMask());
            history.executeUpdate();
        }
    }
}


