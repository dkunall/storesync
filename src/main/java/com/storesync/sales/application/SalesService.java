package com.storesync.sales.application;

import com.storesync.catalog.application.CatalogService;
import com.storesync.catalog.domain.Product;
import com.storesync.inventory.application.InventoryService;
import com.storesync.inventory.domain.StockPolicy;
import com.storesync.outbox.application.Outbox;
import com.storesync.promo.application.PromotionService;
import com.storesync.promo.domain.BasketLine;
import com.storesync.sales.application.SalesRepository.AppliedPromotion;
import com.storesync.sales.application.SalesRepository.Channel;
import com.storesync.sales.application.SalesRepository.Line;
import com.storesync.sales.application.SalesRepository.Payment;
import com.storesync.sales.application.SalesRepository.Sale;
import com.storesync.sales.application.SalesRepository.SaleView;
import com.storesync.sales.application.SalesRepository.Shift;
import com.storesync.sales.domain.InvoiceNumber;
import com.storesync.sales.domain.PaymentMethod;
import com.storesync.sales.domain.SaleCalculator;
import com.storesync.sales.domain.SaleStatus;
import com.storesync.sales.domain.ShiftState;
import com.storesync.sales.domain.TaxCalculator;
import com.storesync.sales.domain.Tender;
import com.storesync.shared.config.BusinessZone;
import com.storesync.shared.config.StoreSyncProperties;
import com.storesync.shared.domain.DomainRuleException;
import com.storesync.shared.domain.Hashes;
import com.storesync.shared.domain.Money;
import com.storesync.shared.error.ApiException;
import com.storesync.shared.security.Caller;
import com.storesync.shared.security.Role;
import com.storesync.store.application.StoreService;
import com.storesync.store.domain.Store;
import com.storesync.store.domain.Terminal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class SalesService {

    /** How far ahead of the server clock an offline sale's timestamp may be before it is treated as bad data. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    public record LineRequest(String sku, String barcode, int qty, BigDecimal manualDiscount) {
        public LineRequest {
            manualDiscount = manualDiscount == null ? Money.ZERO : Money.exact(manualDiscount, "manualDiscount");
        }
    }

    public record PaymentRequest(PaymentMethod method, BigDecimal amount, String reference) {
        public PaymentRequest {
            if (method == null) {
                throw new IllegalArgumentException("payment method is required");
            }
            amount = Money.exact(amount, "payment amount");
        }
    }

    /**
     * A sale rung up against the server. {@code id} comes from the client so a retry is recognised;
     * {@code applyCreditNote} spends as much of that note as the total needs before the other payments.
     */
    public record OnlineSale(UUID id, String store, UUID shiftId, String cashier, String customerGstin,
                             List<LineRequest> lines, List<PaymentRequest> payments, String applyCreditNote) {
        public OnlineSale {
            if (id == null || store == null || lines == null || lines.isEmpty()) {
                throw new IllegalArgumentException("id, store and at least one line are required");
            }
            lines = List.copyOf(lines);
            payments = payments == null ? List.of() : List.copyOf(payments);
        }
    }

    public record OfflineLine(int lineNo, String sku, int qty, BigDecimal unitPrice, BigDecimal taxRate,
                              BigDecimal promoDiscount, BigDecimal manualDiscount) {
    }

    public record Totals(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {
    }

    /** A sale a terminal already completed, as carried by a SALE op. The server checks it rather than re-prices it. */
    public record OfflineSale(UUID saleId, UUID shiftId, String invoiceNo, String cashier, String customerGstin,
                              Instant soldAt, List<OfflineLine> lines, List<PaymentRequest> payments,
                              BigDecimal changeDue, Totals totals, List<AppliedPromotion> promotions) {
        public OfflineSale {
            if (saleId == null || shiftId == null || invoiceNo == null || cashier == null || soldAt == null
                    || lines == null || lines.isEmpty() || payments == null || totals == null || changeDue == null) {
                throw new IllegalArgumentException("sale op is missing required fields");
            }
            promotions = promotions == null ? List.of() : List.copyOf(promotions);
            if (promotions.stream().map(AppliedPromotion::code).distinct().count() != promotions.size()) {
                throw new IllegalArgumentException("a promotion is listed twice");
            }
        }
    }

    public record VoidedInvoice(UUID saleId, UUID shiftId, String invoiceNo, String cashier, String approvedBy,
                                String reason, Instant at) {
        public VoidedInvoice {
            if (saleId == null || shiftId == null || invoiceNo == null || cashier == null || approvedBy == null || at == null) {
                throw new IllegalArgumentException("void op is missing required fields");
            }
        }
    }

    private final SalesRepository repo;
    private final StoreService stores;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final PromotionService promos;
    private final InvoiceService invoices;
    private final ShiftService shifts;
    private final Outbox outbox;
    private final StoreSyncProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    SalesService(SalesRepository repo, StoreService stores, CatalogService catalog, InventoryService inventory,
                 PromotionService promos, InvoiceService invoices, ShiftService shifts, Outbox outbox,
                 StoreSyncProperties props, Clock clock, MeterRegistry meters) {
        this.repo = repo;
        this.stores = stores;
        this.catalog = catalog;
        this.inventory = inventory;
        this.promos = promos;
        this.invoices = invoices;
        this.shifts = shifts;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    // ---------------------------------------------------------------- online

    /**
     * Prices, tenders, numbers and books a sale in one transaction. Stock may not go below zero here: the server
     * can see the balance, so it refuses instead of recording an oversell. Lock order: sale id, shift, credit
     * note, stock rows (sorted by SKU), invoice series.
     */
    @Transactional
    public SaleView sell(Caller caller, OnlineSale cmd) {
        caller.requireStore(cmd.store(), Role.CASHIER, Role.MANAGER, Role.TERMINAL);
        String hash = Hashes.sha256(cmd.toString());
        repo.lockSaleId(cmd.id());
        var existing = repo.sale(cmd.id(), false);
        if (existing.isPresent()) {
            if (existing.get().requestHash().equals(hash)) {
                meters.counter("storesync.sales.replayed").increment();
                return repo.view(cmd.id()).orElseThrow();
            }
            throw ApiException.unprocessable("sale-id-reused", "Sale id " + cmd.id() + " was used for a different sale");
        }
        Store store = stores.store(cmd.store());
        String cashier = caller.has(Role.TERMINAL) ? cmd.cashier() : caller.subject();
        if (cashier == null) {
            throw ApiException.invalid("A terminal must say which cashier is selling");
        }
        Role cashierRole = staffRole(store.code(), cashier);
        String pos = Store.placeOfSupply(store.stateCode(), cmd.customerGstin());

        var products = resolve(cmd.lines());
        var inputs = new ArrayList<SaleCalculator.LineInput>();
        var basket = new ArrayList<BasketLine>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            var r = cmd.lines().get(i);
            Product p = products.get(i);
            if (r.qty() <= 0) {
                throw new DomainRuleException("bad-quantity", "Line " + (i + 1) + " quantity must be positive");
            }
            checkDiscount(cashierRole, p.price().multiply(BigDecimal.valueOf(r.qty())), r.manualDiscount(), i + 1);
            if (r.manualDiscount().signum() == 0) {
                basket.add(new BasketLine(i + 1, p.sku(), r.qty(), p.price()));
            }
        }
        var best = promos.best(basket, clock.instant());
        for (int i = 0; i < cmd.lines().size(); i++) {
            var r = cmd.lines().get(i);
            Product p = products.get(i);
            inputs.add(new SaleCalculator.LineInput(i + 1, p.sku(), r.qty(), p.price(), p.taxRate(),
                    best.lineDiscounts().getOrDefault(i + 1, Money.ZERO), r.manualDiscount()));
        }
        var priced = SaleCalculator.price(inputs, TaxCalculator.forSupply(store.stateCode(), pos));

        Shift shift = cmd.shiftId() == null ? null : shifts.lockOpen(cmd.shiftId(), store.code());
        var payments = new ArrayList<Tender.Payment>();
        if (cmd.applyCreditNote() != null) {
            var note = repo.creditNote(cmd.applyCreditNote(), true)
                    .orElseThrow(() -> ApiException.notFound("credit note " + cmd.applyCreditNote()));
            BigDecimal use = note.balance().min(priced.total());
            if (use.signum() > 0) {
                payments.add(new Tender.Payment(PaymentMethod.CREDIT_NOTE, use, note.no()));
            }
        }
        cmd.payments().forEach(p -> payments.add(new Tender.Payment(p.method(), p.amount(), p.reference())));
        BigDecimal change = Tender.settle(priced.total(), payments);
        if (shift == null && payments.stream().anyMatch(p -> p.method() == PaymentMethod.CASH)) {
            throw ApiException.unprocessable("cash-needs-shift", "Cash can only be taken into an open shift's drawer");
        }
        spendCreditNotes(store.code(), payments);

        inventory.apply(store.code(), InventoryService.HQ, moves(inputs), StockPolicy.REFUSE_NEGATIVE, "SALE",
                cmd.id().toString());
        String invoiceNo = invoices.nextOnline(store.code(), BusinessZone.today(clock));
        SaleStatus status = SaleStatus.OPEN.moveTo(SaleStatus.TENDERING).moveTo(SaleStatus.PAID).moveTo(SaleStatus.SYNCED);
        Instant now = clock.instant();
        var sale = new Sale(cmd.id(), store.code(), caller.terminalId(), cmd.shiftId(), null, Channel.ONLINE, invoiceNo,
                status, cashier, pos, blankToNull(cmd.customerGstin()), priced.gross(), priced.discount(),
                priced.taxable(), priced.cgst(), priced.sgst(), priced.igst(), priced.total(), change, Money.ZERO, hash,
                now, now);
        var applied = best.applied().stream().map(a -> new AppliedPromotion(a.code(), a.times(), a.saving())).toList();
        save(sale, priced, payments, applied);
        if (shift != null) {
            shifts.touched(shift);
        }
        meters.counter("storesync.sales", "channel", "online").increment();
        return repo.view(cmd.id()).orElseThrow();
    }

    private List<Product> resolve(List<LineRequest> lines) {
        var skus = lines.stream().map(LineRequest::sku).filter(Objects::nonNull).toList();
        var bySku = catalog.find(skus);
        var out = new ArrayList<Product>();
        int no = 1;
        for (LineRequest r : lines) {
            Product p = r.sku() != null ? bySku.get(r.sku())
                    : r.barcode() != null ? catalog.byBarcode(r.barcode()).orElse(null) : null;
            if (p == null) {
                throw ApiException.unprocessable("unknown-product", "Line " + no + " names no known product");
            }
            if (!p.active()) {
                throw ApiException.unprocessable("inactive-product", p.sku() + " is not for sale");
            }
            out.add(p);
            no++;
        }
        return out;
    }

    private Role staffRole(String store, String subject) {
        var m = stores.staff(subject);
        if (m.isEmpty() || !m.get().storeCode().equals(store)) {
            throw new DomainRuleException("not-store-staff", subject + " is not staff of store " + store);
        }
        return m.get().role();
    }

    private void checkDiscount(Role role, BigDecimal gross, BigDecimal manual, int lineNo) {
        if (manual.signum() == 0) {
            return;
        }
        BigDecimal limit = role == Role.MANAGER ? props.sales().managerMaxDiscountPercent()
                : props.sales().cashierMaxDiscountPercent();
        if (manual.compareTo(Money.percentOf(gross, limit)) > 0) {
            throw new DomainRuleException("discount-over-limit", "Line " + lineNo + " discount is above the "
                    + limit.stripTrailingZeros().toPlainString() + "% a " + role + " may give");
        }
    }

    private void spendCreditNotes(String store, List<Tender.Payment> payments) {
        for (Tender.Payment p : payments) {
            if (p.method() != PaymentMethod.CREDIT_NOTE) {
                continue;
            }
            var note = repo.creditNote(p.reference(), true)
                    .orElseThrow(() -> new DomainRuleException("unknown-credit-note", "No credit note " + p.reference()));
            if (!note.store().equals(store)) {
                throw new DomainRuleException("other-store-credit-note", "Credit note " + note.no() + " is from another store");
            }
            if (note.balance().compareTo(p.amount()) < 0) {
                throw new DomainRuleException("credit-note-overdrawn", "Credit note " + note.no() + " has only " + note.balance());
            }
            repo.useCreditNote(note.no(), p.amount());
        }
    }

    private static List<InventoryService.Move> moves(List<SaleCalculator.LineInput> lines) {
        return lines.stream().map(l -> new InventoryService.Move(l.sku(), -l.qty())).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void save(Sale sale, SaleCalculator.PricedSale priced, List<Tender.Payment> payments,
                      List<AppliedPromotion> applied) {
        var lines = priced.lines().stream().map(pl -> new Line(pl.input().lineNo(), pl.input().sku(), pl.input().qty(),
                pl.input().unitPrice(), pl.input().taxRate(), pl.input().promoDiscount(), pl.input().manualDiscount(),
                pl.tax().taxable(), pl.tax().cgst(), pl.tax().sgst(), pl.tax().igst(), pl.total(), 0, Money.ZERO,
                Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)).toList();
        var pays = payments.stream().map(p -> new Payment(p.method(), p.amount(), p.reference())).toList();
        repo.insert(sale, lines, pays, applied);

        var data = new LinkedHashMap<String, Object>();
        data.put("store", sale.store());
        data.put("saleId", sale.id().toString());
        data.put("invoiceNo", sale.invoiceNo());
        data.put("channel", sale.channel().name());
        data.put("day", BusinessZone.dayOf(sale.soldAt()).toString());
        data.put("total", sale.total().toPlainString());
        data.put("tax", priced.tax().toPlainString());
        data.put("lines", lines.stream().map(l -> Map.of("sku", l.sku(), "qty", l.qty(),
                "net", l.taxable().toPlainString(),
                "tax", l.cgst().add(l.sgst()).add(l.igst()).toPlainString())).toList());
        outbox.append(Outbox.SALES, sale.store(), "SaleCompleted", data);
    }

    // ---------------------------------------------------------------- offline (applied by sync)

    /**
     * A SALE op. The sale already happened at the till, so stock is decremented on the terminal's own node even
     * past zero; the resulting StockChanged event is what raises the negative-stock alert.
     *
     * @throws DomainRuleException when the op cannot be accepted; sync records it as REJECTED
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOffline(Terminal t, long seq, OfflineSale op, String hash) {
        repo.lockSaleId(op.saleId());
        var existing = repo.sale(op.saleId(), false);
        if (existing.isPresent()) {
            if (existing.get().requestHash().equals(hash)) {
                return;
            }
            throw new DomainRuleException("sale-id-reused", "Sale id " + op.saleId() + " was used for a different sale");
        }
        InvoiceNumber no = invoiceIssuedTo(t, op.invoiceNo());
        if (op.soldAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new DomainRuleException("sold-in-future", "Sale time is ahead of the server clock");
        }
        Store store = stores.store(t.storeCode());
        Role role = staffRole(store.code(), op.cashier());
        String pos = Store.placeOfSupply(store.stateCode(), op.customerGstin());

        var inputs = new ArrayList<SaleCalculator.LineInput>();
        var seen = new HashSet<Integer>();
        for (OfflineLine l : op.lines()) {
            if (!seen.add(l.lineNo())) {
                throw new DomainRuleException("duplicate-line", "Line " + l.lineNo() + " appears twice");
            }
            var in = new SaleCalculator.LineInput(l.lineNo(), l.sku(), l.qty(), l.unitPrice(), l.taxRate(),
                    l.promoDiscount(), l.manualDiscount());
            checkDiscount(role, in.gross(), in.manualDiscount(), l.lineNo());
            inputs.add(in);
        }
        var priced = SaleCalculator.price(inputs, TaxCalculator.forSupply(store.stateCode(), pos));
        var claimed = op.totals();
        if (!same(priced.total(), claimed.total()) || !same(priced.taxable(), claimed.taxable())
                || !same(priced.cgst(), claimed.cgst()) || !same(priced.sgst(), claimed.sgst())
                || !same(priced.igst(), claimed.igst())) {
            throw new DomainRuleException("totals-mismatch", "Server computes total " + priced.total()
                    + " (taxable " + priced.taxable() + ") for invoice " + no);
        }
        var payments = op.payments().stream().map(p -> new Tender.Payment(p.method(), p.amount(), p.reference())).toList();
        BigDecimal change = Tender.settle(priced.total(), payments);
        if (!same(change, op.changeDue())) {
            throw new DomainRuleException("change-mismatch", "Change should be " + change);
        }
        Shift shift = shifts.lock(op.shiftId());
        if (!Objects.equals(shift.terminalId(), t.id())) {
            throw new DomainRuleException("other-terminal-shift", "Shift belongs to another till");
        }
        if (shift.status() == ShiftState.CLOSED) {
            throw new DomainRuleException("shift-closed", "Shift was closed with all its declared sales already");
        }
        spendCreditNotes(store.code(), payments);
        inventory.apply(store.code(), t.node(), moves(inputs), StockPolicy.ALLOW_NEGATIVE, "SALE", op.saleId().toString());
        var sale = new Sale(op.saleId(), store.code(), t.id(), shift.id(), seq, Channel.OFFLINE, no.toString(),
                SaleStatus.PAID.moveTo(SaleStatus.SYNCED), op.cashier(), pos, blankToNull(op.customerGstin()),
                priced.gross(), priced.discount(), priced.taxable(), priced.cgst(), priced.sgst(), priced.igst(),
                priced.total(), change, Money.ZERO, hash, op.soldAt(), clock.instant());
        save(sale, priced, payments, op.promotions());
        if (shift.status() == ShiftState.CLOSING) {
            shifts.maybeComplete(shift.id());
        } else {
            shifts.touched(shift);
        }
        meters.counter("storesync.sales", "channel", "offline").increment();
    }

    /** A SALE_VOIDED op: the number was printed and then cancelled, so it is recorded with zero amounts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordVoid(Terminal t, long seq, VoidedInvoice op, String hash) {
        repo.lockSaleId(op.saleId());
        var existing = repo.sale(op.saleId(), false);
        if (existing.isPresent()) {
            if (existing.get().requestHash().equals(hash)) {
                return;
            }
            throw new DomainRuleException("sale-id-reused", "Sale id " + op.saleId() + " was used for a different sale");
        }
        InvoiceNumber no = invoiceIssuedTo(t, op.invoiceNo());
        staffRole(t.storeCode(), op.cashier());
        if (staffRole(t.storeCode(), op.approvedBy()) != Role.MANAGER) {
            throw new DomainRuleException("void-needs-manager", "A void must be approved by a manager of the store");
        }
        Shift shift = shifts.lock(op.shiftId());
        if (!Objects.equals(shift.terminalId(), t.id())) {
            throw new DomainRuleException("other-terminal-shift", "Shift belongs to another till");
        }
        var z = Money.ZERO;
        repo.insert(new Sale(op.saleId(), t.storeCode(), t.id(), shift.id(), seq, Channel.OFFLINE, no.toString(),
                SaleStatus.OPEN.moveTo(SaleStatus.VOIDED), op.cashier(), null, null, z, z, z, z, z, z, z, z, z, hash,
                op.at(), clock.instant()), List.of(), List.of(), List.of());
        meters.counter("storesync.sales.voided").increment();
    }

    private InvoiceNumber invoiceIssuedTo(Terminal t, String invoiceNo) {
        InvoiceNumber no;
        try {
            no = InvoiceNumber.parse(invoiceNo);
        } catch (IllegalArgumentException e) {
            throw new DomainRuleException("bad-invoice-number", e.getMessage());
        }
        if (!invoices.issuedTo(t, no)) {
            throw new DomainRuleException("invoice-not-issued", "Invoice " + no + " is not in a block given to this till");
        }
        if (repo.invoiceUsed(no.toString())) {
            throw new DomainRuleException("invoice-reused", "Invoice " + no + " is already used");
        }
        return no;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return b != null && a.compareTo(b) == 0;
    }

    // ---------------------------------------------------------------- reads

    public SaleView get(Caller caller, UUID id) {
        var v = repo.view(id).orElseThrow(() -> ApiException.notFound("sale"));
        caller.requireStore(v.sale().store(), Role.CASHIER, Role.MANAGER, Role.TERMINAL);
        return v;
    }
}
