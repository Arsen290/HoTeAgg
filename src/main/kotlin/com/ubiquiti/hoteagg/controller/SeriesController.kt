package com.ubiquiti.hoteagg.controller

import com.ubiquiti.hoteagg.model.SeriesResponse
import com.ubiquiti.hoteagg.service.InvalidSeriesRequestException
import com.ubiquiti.hoteagg.service.TelemetrySeriesService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/devices")
class SeriesController(private val service: TelemetrySeriesService) {

    @GetMapping("/{deviceId}/metrics/{metric}/series")
    fun series(
        @PathVariable("deviceId") deviceId: String,
        @PathVariable("metric") metric: String,
        @RequestParam("range", defaultValue = "1h") range: String,
    ): SeriesResponse = service.getSeries(deviceId, metric, range)

    @ExceptionHandler(InvalidSeriesRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: InvalidSeriesRequestException): Map<String, String?> = mapOf("error" to e.message)
}

