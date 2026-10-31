package com.storesync.analytics.adapter;

import com.storesync.analytics.application.AnalyticsRepository;
import com.storesync.analytics.application.AnalyticsService;
import com.storesync.shared.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
class AnalyticsController {

    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/v1/analytics/sales")
    AnalyticsService.SalesReport sales(Caller caller, @RequestParam String store, @RequestParam LocalDate from,
                                       @RequestParam LocalDate to) {
        return analytics.sales(caller, store, from, to);
    }

    @GetMapping("/v1/stores/{code}/alerts")
    List<AnalyticsRepository.Alert> alerts(Caller caller, @PathVariable String code,
                                           @RequestParam(defaultValue = "50") int limit) {
        return analytics.alerts(caller, code, limit);
    }
}
