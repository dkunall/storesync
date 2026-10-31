package com.storesync.inventory.adapter;

import com.storesync.inventory.application.InventoryService;
import com.storesync.inventory.application.StockRepository.StockCount;
import com.storesync.inventory.application.StockRepository.StockState;
import com.storesync.inventory.application.StockRepository.Transfer;
import com.storesync.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class InventoryController {

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    record StockView(String sku, long onHand, Map<String, Long> increments, Map<String, Long> decrements) {
        static StockView of(StockState s) {
            return new StockView(s.sku(), s.onHand(), s.counter().increments(), s.counter().decrements());
        }
    }

    record CountRequest(@NotNull UUID id, @NotBlank String sku, long counted) {
    }

    record TransferRequest(@NotNull UUID id, @NotBlank String from, @NotBlank String to,
                           @NotEmpty Map<String, Integer> lines) {
    }

    record ReceiveRequest(Map<String, Integer> received) {
    }

    @GetMapping("/v1/stores/{code}/stock")
    List<StockView> stock(Caller caller, @PathVariable String code) {
        return inventory.stock(caller, code).stream().map(StockView::of).toList();
    }

    @GetMapping("/v1/stores/{code}/stock/{sku}")
    StockView item(Caller caller, @PathVariable String code, @PathVariable String sku) {
        return StockView.of(inventory.item(caller, code, sku));
    }

    @PostMapping("/v1/stores/{code}/stock-counts")
    @ResponseStatus(HttpStatus.CREATED)
    StockCount count(Caller caller, @PathVariable String code, @Valid @RequestBody CountRequest r) {
        return inventory.count(caller, code, r.id(), r.sku(), r.counted());
    }

    @PostMapping("/v1/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    Transfer dispatch(Caller caller, @Valid @RequestBody TransferRequest r) {
        return inventory.dispatch(caller, r.id(), r.from(), r.to(), r.lines());
    }

    @PostMapping("/v1/transfers/{id}/receive")
    Transfer receive(Caller caller, @PathVariable UUID id, @RequestBody(required = false) ReceiveRequest r) {
        return inventory.receive(caller, id, r == null || r.received() == null ? Map.of() : r.received());
    }

    @GetMapping("/v1/transfers/{id}")
    Transfer get(Caller caller, @PathVariable UUID id) {
        return inventory.transfer(caller, id);
    }
}
