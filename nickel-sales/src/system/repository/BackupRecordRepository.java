package system.repository;

import system.model.BackupRecord;

import java.util.List;
import java.util.Optional;

/** The history of backups and restores. Append-only. Database problems are RuntimeException. */
public interface BackupRecordRepository {

    void add(BackupRecord record);

    /** The newest records first, at most {@code limit}. */
    List<BackupRecord> findRecent(int limit);

    /** The newest successful backup (of any terminal), if any. */
    Optional<BackupRecord> latestBackup();
}
