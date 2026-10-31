package com.storesync.sales.adapter;

import com.storesync.sales.application.InvoiceService;
import com.storesync.sales.application.ReturnService;
import com.storesync.sales.application.SalesRepository;
import com.storesync.sales.application.SalesService;
import com.storesync.sales.application.ShiftService;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
class SalesController {

    private final SalesService sales;
    private final ReturnService returns;
    private final ShiftService shifts;
    private final InvoiceService invoices;

    SalesController(SalesService sales, ReturnService returns, ShiftService shifts, InvoiceService invoices) {
        this.sales = sales;
        this.returns = returns;
        this.shifts = shifts;
        this.invoices = invoices;
    }

    record OpenShift(UUID id, String store, BigDecimal openingFloat) {
    }

    record CloseShift(BigDecimal countedCash) {
    }

    @PostMapping("/v1/sales")
    @ResponseStatus(HttpStatus.CREATED)
    SalesRepository.SaleView sell(Caller caller, @RequestBody SalesService.OnlineSale body) {
        if (body.applyCreditNote() != null) {
            throw ApiException.invalid("Use payments with method CREDIT_NOTE, or the exchange endpoint");
        }
        return sales.sell(caller, body);
    }

    @GetMapping("/v1/sales/{id}")
    SalesRepository.SaleView get(Caller caller, @PathVariable UUID id) {
        return sales.get(caller, id);
    }

    @PostMapping("/v1/sales/{id}/return")
    @ResponseStatus(HttpStatus.CREATED)
    ReturnService.ReturnView returnSale(Caller caller, @PathVariable UUID id, @RequestBody ReturnService.ReturnCommand body) {
        return returns.returnSale(caller, id, body);
    }

    @PostMapping("/v1/sales/{id}/exchange")
    @ResponseStatus(HttpStatus.CREATED)
    ReturnService.ExchangeView exchange(Caller caller, @PathVariable UUID id, @RequestBody ReturnService.ExchangeCommand body) {
        return returns.exchange(caller, id, body);
    }

    @GetMapping("/v1/credit-notes/{no}")
    SalesRepository.CreditNote creditNote(Caller caller, @PathVariable String no) {
        return returns.creditNote(caller, no);
    }

    @PostMapping("/v1/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    SalesRepository.Shift openShift(Caller caller, @RequestBody OpenShift body) {
        if (body.id() == null || body.store() == null) {
            throw ApiException.invalid("id and store are required");
        }
        return shifts.open(caller, body.store(), body.id(), body.openingFloat());
    }

    @PostMapping("/v1/shifts/{id}/close")
    SalesRepository.Shift closeShift(Caller caller, @PathVariable UUID id, @RequestBody CloseShift body) {
        return shifts.close(caller, id, body.countedCash());
    }

    @GetMapping("/v1/shifts/{id}")
    SalesRepository.Shift shift(Caller caller, @PathVariable UUID id) {
        return shifts.get(caller, id);
    }

    @GetMapping("/v1/stores/{code}/reconciliation")
    ShiftService.Reconciliation reconcile(Caller caller, @PathVariable String code, @RequestParam LocalDate day) {
        return shifts.reconcile(caller, code, day);
    }

    @PostMapping("/v1/terminals/{id}/invoice-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceService.Allocation allocate(Caller caller, @PathVariable UUID id) {
        return invoices.allocate(caller, id);
    }
}
