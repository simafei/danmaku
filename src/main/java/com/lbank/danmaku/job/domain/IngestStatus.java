package com.lbank.danmaku.job.domain;

public final class IngestStatus {
    public static final String PENDING = "pending";
    public static final String PROCESSING = "processing";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String DISCARDED = "discarded";

    private IngestStatus() {
    }
}
