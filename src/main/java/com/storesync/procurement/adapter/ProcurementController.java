package com.storesync.procurement.adapter;

import com.storesync.procurement.application.ProcurementRepository;
import com.storesync.procurement.application.ProcurementService;
import com.storesync.shared.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class ProcurementController {

    private final ProcurementService procurement;

    ProcurementController(ProcurementService procurement) {
        this.procurement = procurement;
    }

    record ReceiptRequest(String ref, Map<String, Integer> received) {
    }

    @PostMapping("/v1/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    ProcurementRepository.Supplier createSupplier(Caller caller, @RequestBody ProcurementRepository.Supplier body) {
        return procurement.createSupplier(caller, body);
    }

    @GetMapping("/v1/suppliers")
    List<ProcurementRepository.Supplier> suppliers(Caller caller) {
        return procurement.suppliers(caller);
    }

    @PostMapping("/v1/stores/{code}/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    ProcurementRepository.PurchaseOrder order(Caller caller, @PathVariable String code,
                                              @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                              @RequestBody ProcurementService.NewOrder body) {
        return procurement.order(caller, code, key, body);
    }

    @GetMapping("/v1/stores/{code}/purchase-orders")
    List<ProcurementRepository.PurchaseOrder> orders(Caller caller, @PathVariable String code) {
        return procurement.orders(caller, code);
    }

    @GetMapping("/v1/purchase-orders/{id}")
    ProcurementRepository.PurchaseOrder get(Caller caller, @PathVariable UUID id) {
        return procurement.get(caller, id);
    }

    @PostMapping("/v1/purchase-orders/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    ProcurementRepository.Receipt receive(Caller caller, @PathVariable UUID id, @RequestBody ReceiptRequest body) {
        return procurement.receive(caller, id, body.ref(), body.received());
    }

    @GetMapping("/v1/stores/{code}/replenishment")
    ProcurementService.Replenishment replenishment(Caller caller, @PathVariable String code) {
        return procurement.replenishment(caller, code, null);
    }

    @PostMapping("/v1/stores/{code}/replenishment/raise")
    ProcurementService.Replenishment raise(Caller caller, @PathVariable String code,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        if (key == null || key.isBlank()) {
            throw com.storesync.shared.error.ApiException.invalid("idempotency-key-required", "Send an Idempotency-Key header");
        }
        return procurement.replenishment(caller, code, key);
    }
}
