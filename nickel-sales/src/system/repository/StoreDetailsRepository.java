package system.repository;

import system.model.StoreDetailsRecord;

import java.util.List;
import java.util.Optional;

/** The StoreDetails log: rows are only ever added, never changed or deleted. */
public interface StoreDetailsRepository {

    /** Adds a row; returns it with its new ID. */
    StoreDetailsRecord add(StoreDetailsRecord record);

    /** The newest row by PerformedAt (the details in use), or empty if none was ever saved. */
    Optional<StoreDetailsRecord> latest();

    /** Every row, newest first, at most {@code limit}. */
    List<StoreDetailsRecord> history(int limit);
}
