package org.enthusia.playtime.util;

import java.util.concurrent.atomic.LongAdder;

public final class PerformanceCounters {

    public final LongAdder activityEventsAccepted = new LongAdder();
    public final LongAdder activityEventsSkipped = new LongAdder();
    public final LongAdder moveEventsThrottled = new LongAdder();
    public final LongAdder minuteDeltasQueued = new LongAdder();
    public final LongAdder flushBatches = new LongAdder();
    public final LongAdder dbReadCacheHits = new LongAdder();
    public final LongAdder dbReadCacheMisses = new LongAdder();
    public final LongAdder asyncRefreshesStarted = new LongAdder();
    public final LongAdder asyncRefreshesCompleted = new LongAdder();
    public final LongAdder asyncRefreshesFailed = new LongAdder();
    public final LongAdder leaderboardExports = new LongAdder();
    public final LongAdder r2UploadsAttempted = new LongAdder();
    public final LongAdder r2UploadsSkipped = new LongAdder();
    public final LongAdder r2UploadsFailed = new LongAdder();
    public final LongAdder backstopScans = new LongAdder();
    public final LongAdder backstopRepairs = new LongAdder();
    public final LongAdder guiLoadingRenders = new LongAdder();
    public final LongAdder placeholderCachedReturns = new LongAdder();
    public final LongAdder placeholderStaleRefreshes = new LongAdder();
    public final LongAdder reloadTaskRestarts = new LongAdder();
    public final LongAdder headCacheHits = new LongAdder();
    public final LongAdder headCacheMisses = new LongAdder();
    public final LongAdder headCacheSaves = new LongAdder();

    public String summary() {
        return "activity accepted=" + activityEventsAccepted.sum()
                + ", skipped=" + activityEventsSkipped.sum()
                + ", move throttled=" + moveEventsThrottled.sum()
                + ", minutes queued=" + minuteDeltasQueued.sum()
                + ", flush batches=" + flushBatches.sum()
                + ", cache hits=" + dbReadCacheHits.sum()
                + ", cache misses=" + dbReadCacheMisses.sum()
                + ", refresh start/done/fail=" + asyncRefreshesStarted.sum() + "/"
                + asyncRefreshesCompleted.sum() + "/" + asyncRefreshesFailed.sum()
                + ", exports=" + leaderboardExports.sum()
                + ", r2 attempted/skipped/failed=" + r2UploadsAttempted.sum() + "/"
                + r2UploadsSkipped.sum() + "/" + r2UploadsFailed.sum()
                + ", audits=" + backstopScans.sum()
                + ", repairs=" + backstopRepairs.sum()
                + ", GUI loading=" + guiLoadingRenders.sum()
                + ", placeholders cached/stale=" + placeholderCachedReturns.sum() + "/"
                + placeholderStaleRefreshes.sum()
                + ", reload tasks=" + reloadTaskRestarts.sum()
                + ", head hit/miss/save=" + headCacheHits.sum() + "/"
                + headCacheMisses.sum() + "/" + headCacheSaves.sum();
    }
}
