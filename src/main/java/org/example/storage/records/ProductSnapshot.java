package org.example.storage.records;

public record ProductSnapshot(
        long nmId,
        String name,
        long supplierId,
        String supplierName,
        String categoryUrl,
        long observedAt,
        long price,
        long feedback,
        long stock,
        double percent,
        int channelMask
) {
}


