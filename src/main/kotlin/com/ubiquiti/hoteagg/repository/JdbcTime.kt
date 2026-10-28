package com.ubiquiti.hoteagg.repository

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** pgjdbc cannot bind java.time.Instant directly, so timestamps are sent as UTC OffsetDateTime. */
internal fun Instant.toUtc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

internal fun ResultSet.getInstant(column: String): Instant =
    getObject(column, OffsetDateTime::class.java).toInstant()

