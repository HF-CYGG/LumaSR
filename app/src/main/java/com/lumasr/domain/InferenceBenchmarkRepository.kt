package com.lumasr.domain

interface InferenceBenchmarkRepository {
    suspend fun save(result: InferenceBenchmarkResult)

    suspend fun list(): List<InferenceBenchmarkResult>

    suspend fun findValid(identity: BenchmarkIdentity, nowEpochMs: Long): InferenceBenchmarkResult?
}
