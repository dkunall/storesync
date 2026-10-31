# StoreSync

Inventory and point-of-sale backend for a small chain of shops, built for tills that lose the network. A till keeps
selling offline with invoice numbers it was handed in advance, queues what it did as an op log, and pushes the log
when it is back. The server applies each op once, however many times the till resends it, and keeps stock per
store as a PN-counter so offline sales from several tills merge without losing any of them.

Java 21, Spring Boot 3.5, Postgres, Kafka, Redis. Hexagonal layout: `domain` is plain Java, `application` holds the
use cases and the ports, `adapter` holds the web, JDBC, Kafka and Redis code. All data in the tests and scripts is
made up.

## What it does

- **Stores, staff, terminals.** A store has a GST state, managers and cashiers. A terminal is enrolled with its own
  login and gets an epoch; re-enrolling bumps the epoch so a cloned or reset till can't replay an old log.
- **Catalogue.** Products with a price, a GST rate and an EAN-13 barcode. Barcode lookups are cached in Redis for 10
  minutes; an update evicts the entry after the new row has committed.
- **Stock.** One PN-counter per store and SKU, one node per writer (each terminal, plus `hq` for server-side moves).
  Every change takes the store/SKU row lock in SKU order, so two baskets racing for the last unit can't both get it
  and can't deadlock each other. Online sales, transfers out and the like are refused with `409 insufficient-stock`
  rather than going below zero. A sale a till already made offline is a fact, so it is recorded even if the merged
  count goes negative, and that raises a `NEGATIVE_STOCK` alert.
- **Online sales.** The client picks the sale id, so a retry returns the first result; the same id with a different
  basket is `422 sale-id-reused`. Prices, GST (CGST+SGST inside the state, IGST across), promotions and manual
  discounts (capped per role) are worked out on the server in `BigDecimal`. Online invoices come from the store's
  server series one at a time under a row lock, so a failed sale doesn't leave a gap there.
- **Promotions.** Item and basket offers; the engine picks the combination with the biggest saving by branch and
  bound, with a deterministic tie-break. A unit test checks it against brute force on 500 random baskets.
- **Shifts and cash.** Cash needs an open shift. Closing a shift works out the expected drawer and the variance; a
  variance of 100.00 or more raises `CASH_VARIANCE`.
- **Returns and exchanges.** Partial or full, to cash, card/UPI or a credit note. Concurrent returns on one sale run
  one after another so nothing is refunded twice, and each line's tax is prorated so a full return refunds exactly
  what was charged, to the paisa. An exchange turns the returned goods into a credit note and spends it on the new
  sale in one transaction.
- **Offline sync.** `POST /v1/sync/push` takes a batch of ops (`SHIFT_OPENED`, `SALE`, `SALE_VOIDED`,
  `SHIFT_CLOSED`). `(terminal, epoch, seq)` is the idempotency key: a resent op is `DUPLICATE`, a different op with a
  used seq is `CONFLICT`, a hole in the sequence is `GAP` and stops the batch. An op the server can't accept (say an
  invoice number outside the till's blocks) is stored as `REJECTED` and still advances the sequence, because the
  customer already has a receipt; it raises `SYNC_REJECTED` for a person to look at. `GET /v1/sync/pull?since=`
  gives the till what changed in the catalogue, promotions and stock since its last pull.
- **Transfers and counts.** Store-to-store transfers with dispatch and receive; a short receipt raises `SHRINKAGE`,
  as does a stock count that is 3 or more units under the book.
- **Procurement.** Suppliers, purchase orders, goods receipts (a receipt posted twice adds stock once), and
  reorder-point replenishment: EWMA demand over 28 days, safety stock z·σ·√L, order up to the reorder point plus a
  week. Raising the suggested orders with an `Idempotency-Key` creates them once and returns the same orders on a
  retry.
- **Events and analytics.** Sales, returns, stock moves and sync rejections go to an outbox in the same transaction
  and a relay publishes them to Kafka (at least once). Two consumers, the daily sales rollup and the alerts, dedupe
  on the event id. `GET /v1/analytics/sales` reads the rollup.

Errors are RFC 9457 problem details with a stable `code`.

## Layout

```
src/main/java/com/storesync/
  store/ catalog/ inventory/ promo/ sales/ sync/ procurement/ analytics/ outbox/   each with domain/ application/ adapter/
  shared/   money, security (JWT roles), problem details, config
src/main/resources/db/migration/V1__schema.sql
scripts/  up.sh down.sh demo.sh bench.sh
```

## Running it

You need Docker with Compose. Ports 8980 (API) and 8981 (a mock OAuth server for tokens) are bound to 127.0.0.1.

```
./scripts/up.sh       # builds the image, starts Postgres, Kafka, Redis, the mock IdP and the app
./scripts/demo.sh     # walks through the main flows and checks each result
./scripts/bench.sh    # rough timings, see below
./scripts/down.sh     # stops everything and deletes the volumes
```

`up.sh` generates a database password into `~/.config/storesync/compose.env` (mode 600) the first time and never
prints it; set `STORESYNC_ENV_FILE` to put it somewhere else. The demo creates a store in Karnataka, sells a T-shirt
online with a retry, races ten tills for the last three, runs an offline till through a sync and a re-sync, and
checks the rollup and the low-stock alert.

Running the app outside Docker needs `STORESYNC_DB_URL`, `STORESYNC_DB_PASSWORD`, `STORESYNC_KAFKA`,
`STORESYNC_REDIS_HOST` and `STORESYNC_ISSUER`; see `application.yml` for the rest.

## Tests

`mvn verify` runs everything; the integration tests need Docker (Testcontainers starts Postgres, Kafka and Redis).

- 46 unit tests: the PN-counter (including a 1000-step random convergence check), GST and invoice numbering, the
  sale calculator and tenders, the promotion engine against brute force, the replenishment maths, the shift and sale
  state machines, plus 6 ArchUnit rules that keep the domain plain Java and the adapters out of the use cases.
- 28 integration tests against the real stack, among them: concurrent sales never take stock below zero; racing
  retries of one sale id record one sale; concurrent returns never refund more than was sold; a credit note can't be
  spent twice by racing sales; a sync batch replayed by racing pushes is applied once; two offline tills overselling
  converge to the same negative count and raise an alert; a purchase order, a goods receipt, a replenishment raise
  and a transfer each happen once under retries; a redelivered event is counted once in the rollup; staff only see
  their own store and a terminal only acts for itself.

CI runs the tests, builds the image, scans it with Trivy, then brings up the compose stack and runs the demo and a
150-sale bench.

The tests found real bugs along the way: a partial refund that paid back 40.01 for a 40.00 unit because each tax
component was rounded on its own; sale events that put the gross amount in the `net` field, so the rollup showed
660 for a 630 day; racing retries of one transfer id that failed with HTTP 500 instead of waiting; a repeated
replenishment raise that answered with an empty list instead of the orders it had made; and SQL text blocks that
lost a trailing space and glued two keywords together.

## Numbers

One run of `bench.sh` with N=300 on a fresh stack (after the demo), on one 8-CPU box with the app, Postgres, Kafka,
Redis and the client all sharing it. Single run, so read it as a smoke check, not capacity. Full table in
[docs/bench/2026-10-31.md](docs/bench/2026-10-31.md).

- 300 one-unit sales from 16 clients at once against 150 in stock: 150 accepted, 150 refused, 0 other errors, 0 left,
  no stock movement below zero. 1798 ms in total, p50 / p95 / p99 latency 11 / 159 / 246 ms.
- One offline push of 51 ops took 244 ms, all applied; pushing the same queue again took 47 ms and came back 51
  `DUPLICATE` with stock unchanged.
- The rollup showed all 200 invoices 536 ms after the replay.

## Limitations

- All sales of one store take the same invoice-series row lock, so online checkout in a store is serialised. Fine
  for a few tills per shop, not for a big-box store.
- Terminal invoice blocks are handed out ahead of time; numbers a till never uses, or blocks it asks for twice, leave
  gaps in its series. The online series has no gaps.
- Offline sales are checked (totals add up, tax is right for the line, the invoice number belongs to the till) but
  not re-priced. If the till had an old price, the server records what the customer actually paid.
- Offline oversells are allowed by design and only alerted on; someone has to sort out a negative count.
- A stock count is a snapshot. Sales that land between counting the shelf and posting the count are not accounted
  for.
- Card and UPI just record the till's approval reference. There is no payment gateway, refund to card included.
- Analytics are eventually consistent (Kafka, at least once) and lag the sales by however long the relay and the
  consumer take. Demand for replenishment is net of returns, and the forecast is a normal approximation that suits
  steady sellers better than slow, lumpy ones.
- When Postgres aborts a transaction on a deadlock or lock failure (possible on exchanges, which lock a sale and a
  new basket), the API answers `409 lock-conflict`; the client retries with the same id, which is safe.
- Alert thresholds (shrinkage units, cash variance) and discount caps are configuration, not per-store settings.
- Auth is plain JWT roles from an OAuth server; the compose stack uses a mock IdP that hands a token to anyone. There
  is no rate limiting, no multi-tenant isolation beyond the store check, and no UI.

## Licence

MIT, see [LICENSE](LICENSE).
