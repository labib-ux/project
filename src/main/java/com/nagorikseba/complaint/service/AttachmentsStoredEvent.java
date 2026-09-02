package com.nagorikseba.complaint.service;

import java.util.List;

/**
 * Signals that attachment bytes are staged in temp storage and the owning
 * transaction is about to commit (R6).
 *
 * <p>Published inside the transaction, consumed after commit. That ordering is the
 * point: files are promoted to their final location only once the database row
 * that references them is durable, so a rolled-back submission cannot leave
 * orphaned bytes in the served directory, and a served file always has a row.
 *
 * @param storageKeys the keys to promote, in upload order
 */
public record AttachmentsStoredEvent(List<String> storageKeys) {
}
