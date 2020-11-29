package com.wixpress.dst.greyhound.java;

import java.time.Duration;
import java.util.List;

public class RetryConfig {
    private final List<Duration> blockingBackoffs;
    private final List<Duration> nonBlockingBackoffs;

    public RetryConfig(List<Duration> blockingBackoffs, List<Duration> nonBlockingBackoffs) {
        this.blockingBackoffs = blockingBackoffs;
        this.nonBlockingBackoffs = nonBlockingBackoffs;
    }

    public List<Duration> blockingBackoffs() {
        return blockingBackoffs;
    }
    public List<Duration> nonBlockingBackoffs() {
        return nonBlockingBackoffs;
    }
}