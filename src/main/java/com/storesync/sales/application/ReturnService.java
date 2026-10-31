package com.storesync.sales.application;

import com.storesync.inventory.application.InventoryService;
import com.storesync.inventory.domain.StockPolicy;
import com.storesync.outbox.application.Outbox;
import com.storesync.sales.application.SalesRepository.CreditNote;
import com.storesync.sales.application.SalesRepository.Line;
import com.storesync.sales.application.SalesRepository.Return;
import com.storesync.sales.domain.PaymentMethod;
import com.storesync.sales.domain.Refunds;
import com.storesync.sales.domain.SaleStatus;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.domain.DomainConflictException;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Hashes;
import com.storesync.shared.domain.Money;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ReturnService {

    public record ReturnLine(int lineNo, int qty) {
    }

    public record ReturnCommand(UUID returnId, List<ReturnLine> lines, PaymentMethod refundMethod, boolean restock,
                                UUID shiftId, String reason) {
        public ReturnCommand {
            if (returnId == null || lines == null || lines.isEmpty() || refundMethod == null) {
                throw new IllegalArgumentException("returnId, lines and refundMethod are required");
            }
            lines = List.copyOf(lines);
        }
    }

    public record ReturnView(UUID returnId, UUID saleId, String invoiceNo, SaleStatus saleStatus,
                             PaymentMethod refundMethod, BigDecimal amount, String creditNoteNo,
                             Map<Integer, Integer> qty, boolean restock, Instant at) {
    }

    /** An exchange: the returned goods become a credit note that pays for the new sale first. */
    public record ExchangeCommand(ReturnCommand ret, SalesService.OnlineSale newSale) {
    }

    public record ExchangeView(ReturnView returned, SalesRepository.SaleView sale, BigDecimal creditNoteBalance) {
    }

    private final SalesRepository repo;
    private final InventoryService inventory;
    private final ShiftService shifts;
    private final InvoiceService invoices;
    private final SalesService sales;
    private final Outbox outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    ReturnService(SalesRepository repo, InventoryService inventory, ShiftService shifts, InvoiceService invoices,
                  SalesService sales, Outbox outbox, Clock clock, MeterRegistry meters) {
        this.repo = repo;
        this.inventory = inventory;
        this.shifts = shifts;
        this.invoices = invoices;
        this.sales = sales;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Refunds part or all of a sale. The sale row is locked first, so concurrent returns against one sale run
     * one after another and the per-line {@code returned_qty <= qty} rule holds. Each tax component is prorated
     * on its own and the last unit takes the remainder, so a fully returned line refunds exactly what it charged.
     */
    @Transactional
    public ReturnView returnSale(Caller caller, UUID saleId, ReturnCommand cmd) {
        var sale = repo.sale(saleId, true).orElseThrow(() -> ApiException.notFound("sale"));
        caller.requireStore(sale.store(), Role.CASHIER, Role.MANAGER);
        String hash = Hashes.sha256(saleId + "|" + cmd);
        var prior = repo.findReturn(cmd.returnId());
        if (prior.isPresent()) {
            if (prior.get().requestHash().equals(hash)) {
                return view(prior.get(), sale.invoiceNo(), repo.sale(saleId, false).orElseThrow().status());
            }
            throw ApiException.unprocessable("return-id-reused", "Return id was used for a different return");
        }
        if (!sale.status().returnable()) {
            throw new DomainConflictException("not-returnable", "A " + sale.status() + " sale cannot be returned");
        }
        var view = repo.view(saleId).orElseThrow();
        var byNo = new TreeMap<Integer, Line>();
        view.lines().forEach(l -> byNo.put(l.lineNo(), l));
        var qty = new TreeMap<Integer, Integer>();
        for (ReturnLine r : cmd.lines()) {
            if (!byNo.containsKey(r.lineNo())) {
                throw new DomainRuleException("unknown-line", "Sale has no line " + r.lineNo());
            }
            if (qty.put(r.lineNo(), r.qty()) != null) {
                throw new DomainRuleException("duplicate-line", "Line " + r.lineNo() + " appears twice");
            }
        }
        if (cmd.refundMethod() == PaymentMethod.CARD || cmd.refundMethod() == PaymentMethod.UPI) {
            boolean paidThatWay = view.payments().stream().anyMatch(p -> p.method() == cmd.refundMethod());
            if (!paidThatWay) {
                throw new DomainRuleException("refund-method", "Refund to " + cmd.refundMethod()
                        + " only for sales paid that way");
            }
        }

        var changed = new ArrayList<Line>();
        var amounts = new TreeMap<Integer, BigDecimal>();
        var eventLines = new ArrayList<Map<String, Object>>();
        BigDecimal total = Money.ZERO;
        for (var e : qty.entrySet()) {
            Line l = byNo.get(e.getKey());
            int q = e.getValue();
            BigDecimal taxable = Refunds.prorate(l.taxable(), l.qty(), l.returnedQty(), l.refundedTaxable(), q);
            // The refund is the line total prorated; tax components are fitted inside it so that rounding each
            // component separately can never refund a paisa more (or less) than the units cost.
            BigDecimal amount = Refunds.prorate(l.total(), l.qty(), l.returnedQty(), l.refunded(), q);
            BigDecimal cgst;
            BigDecimal sgst;
            BigDecimal igst;
            if (l.igst().signum() > 0) {
                cgst = Money.ZERO;
                sgst = Money.ZERO;
                igst = amount.subtract(taxable);
            } else {
                cgst = Refunds.prorate(l.cgst(), l.qty(), l.returnedQty(), l.refundedCgst(), q);
                sgst = amount.subtract(taxable).subtract(cgst);
                igst = Money.ZERO;
            }
            amounts.put(l.lineNo(), amount);
            total = total.add(amount);
            changed.add(new Line(l.lineNo(), l.sku(), l.qty(), l.unitPrice(), l.taxRate(), l.promoDiscount(),
                    l.manualDiscount(), l.taxable(), l.cgst(), l.sgst(), l.igst(), l.total(), l.returnedQty() + q,
                    l.refunded().add(amount), l.refundedTaxable().add(taxable), l.refundedCgst().add(cgst),
                    l.refundedSgst().add(sgst), l.refundedIgst().add(igst)));
            eventLines.add(Map.of("sku", l.sku(), "qty", q, "net", taxable.toPlainString(),
                    "tax", cgst.add(sgst).add(igst).toPlainString()));
        }
        var after = new TreeMap<>(byNo);
        changed.forEach(l -> after.put(l.lineNo(), l));
        boolean all = after.values().stream().allMatch(l -> l.returnedQty() == l.qty());
        SaleStatus status = sale.status().moveTo(all ? SaleStatus.RETURNED : SaleStatus.PARTIALLY_RETURNED);

        UUID shiftId = cmd.shiftId();
        if (cmd.refundMethod() == PaymentMethod.CASH) {
            if (shiftId == null) {
                throw ApiException.unprocessable("cash-needs-shift", "A cash refund must come out of an open shift's drawer");
            }
            shifts.lockOpen(shiftId, sale.store());
        }
        if (cmd.restock()) {
            inventory.apply(sale.store(), InventoryService.HQ,
                    changed.stream().map(l -> new InventoryService.Move(l.sku(), qty.get(l.lineNo()))).toList(),
                    StockPolicy.ALLOW_NEGATIVE, "RETURN", cmd.returnId().toString());
        }
        Instant now = clock.instant();
        String creditNote = null;
        if (cmd.refundMethod() == PaymentMethod.CREDIT_NOTE) {
            if (total.signum() <= 0) {
                throw new DomainRuleException("nothing-to-refund", "Nothing is left to refund on these lines");
            }
            creditNote = invoices.nextCreditNote(sale.store(), BusinessZone.today(clock));
            repo.insertCreditNote(new CreditNote(creditNote, sale.store(), saleId, total, Money.ZERO, now));
        }
        var ret = new Return(cmd.returnId(), saleId, cmd.refundMethod(), total, creditNote, cmd.restock(), shiftId, hash,
                caller.subject(), now, qty);
        repo.insertReturn(ret, amounts);
        repo.updateAfterReturn(saleId, status, sale.refunded().add(total), changed);

        var data = new LinkedHashMap<String, Object>();
        data.put("store", sale.store());
        data.put("saleId", saleId.toString());
        data.put("returnId", cmd.returnId().toString());
        data.put("day", BusinessZone.dayOf(now).toString());
        data.put("amount", total.toPlainString());
        data.put("method", cmd.refundMethod().name());
        data.put("lines", eventLines);
        outbox.append(Outbox.SALES, sale.store(), "SaleReturned", data);
        meters.counter("storesync.returns", "method", cmd.refundMethod().name()).increment();
        return view(ret, sale.invoiceNo(), status);
    }

    private static ReturnView view(Return r, String invoiceNo, SaleStatus status) {
        return new ReturnView(r.id(), r.saleId(), invoiceNo, status, r.refundMethod(), r.amount(), r.creditNoteNo(),
                r.qty(), r.restock(), r.at());
    }

    /** Return to a credit note, then sell the replacement paying with that note first, in one transaction. */
    @Transactional
    public ExchangeView exchange(Caller caller, UUID saleId, ExchangeCommand cmd) {
        if (cmd.ret() == null || cmd.newSale() == null) {
            throw ApiException.invalid("ret and newSale are required");
        }
        var r = cmd.ret();
        if (r.refundMethod() != PaymentMethod.CREDIT_NOTE) {
            throw ApiException.invalid("An exchange refunds to a credit note");
        }
        ReturnView returned = returnSale(caller, saleId, r);
        var ns = cmd.newSale();
        var sale = sales.sell(caller, new SalesService.OnlineSale(ns.id(), ns.store(), ns.shiftId(), ns.cashier(),
                ns.customerGstin(), ns.lines(), ns.payments(), returned.creditNoteNo()));
        BigDecimal left = repo.creditNote(returned.creditNoteNo(), false).map(CreditNote::balance).orElse(Money.ZERO);
        return new ExchangeView(returned, sale, left);
    }

    public CreditNote creditNote(Caller caller, String no) {
        var c = repo.creditNote(no, false).orElseThrow(() -> ApiException.notFound("credit note"));
        caller.requireStore(c.store(), Role.CASHIER, Role.MANAGER, Role.TERMINAL);
        return c;
    }

}
