#!/usr/bin/env bash
# Rough single-box timings against a running stack. Prints a markdown report (and writes it to $OUT if set).
# Safe to rerun: each run makes its own store and products.
set -euo pipefail
OUT=${OUT:+$(realpath -m "$OUT")}
cd "$(dirname "$0")"
source ./lib.sh
export TZ=Asia/Kolkata
N=${N:-300}          # online sales attempted
C=${C:-16}           # concurrent clients
OFFLINE=${OFFLINE:-50} # offline sales in one sync push
STOCK=$((N / 2))
ms() { echo $(( $(date +%s%N) / 1000000 )); }
tmp=$(mktemp -d /tmp/storesync-bench.XXXX)
trap 'rm -rf "$tmp"' EXIT

ADMIN=$(token "$ADMIN_SUB")
new_store 29
SKU=$(new_product 118.00 18)
SOCKS=$(new_product 105.00 5)
expect 201 "$MGR" POST "/v1/stores/$STORE/stock-counts" "{\"id\":\"$(uuid)\",\"sku\":\"$SKU\",\"counted\":$STOCK}" > /dev/null
expect 201 "$MGR" POST "/v1/stores/$STORE/stock-counts" "{\"id\":\"$(uuid)\",\"sku\":\"$SOCKS\",\"counted\":$((OFFLINE * 2))}" > /dev/null

# 1. N online sales of one unit from C clients at once, against stock for half of them.
for ((i = 0; i < N; i++)); do sale_body "$(uuid)" "$SKU" 1 CARD 118.00; echo; done > "$tmp/bodies"
export BASE CASHIER
t0=$(ms)
xargs -d '\n' -P "$C" -I{} sh -c 'curl -s -o /dev/null -w "%{http_code} %{time_total}\n" -X POST "$BASE/v1/sales" \
  -H "Authorization: Bearer $CASHIER" -H "Content-Type: application/json" -d "$1"' _ {} < "$tmp/bodies" > "$tmp/online"
online_ms=$(($(ms) - t0))
ok=$(grep -c '^201 ' "$tmp/online" || true)
refused=$(grep -c '^409 ' "$tmp/online" || true)
other=$((N - ok - refused))
awk '{printf "%d\n", $2 * 1000}' "$tmp/online" > "$tmp/online_ms"
left=$(on_hand "$SKU")
negatives=$(docker compose -p storesync exec -T db psql -U storesync -d storesync -tAc \
  "select count(*) from stock_movement where store_code = '$STORE' and on_hand < 0")

# 2. One offline till syncs OFFLINE cash sales in one push, then sends the same queue again.
TILL_SUB="till-${STORE,,}-1@storesync.example.test"
TERM=$(expect 201 "$MGR" POST "/v1/stores/$STORE/terminals" "{\"number\":1,\"subject\":\"$TILL_SUB\"}")
TID=$(jq -r .id <<< "$TERM"); EPOCH=$(jq -r .epoch <<< "$TERM")
TILL=$(token "$TILL_SUB")
BLOCK=$(expect 201 "$TILL" POST "/v1/terminals/$TID/invoice-blocks" '{}')
size=$(jq -r '.block.last - .block.first + 1' <<< "$BLOCK")
((OFFLINE <= size)) || { echo "OFFLINE=$OFFLINE is more than one invoice block ($size)" >&2; exit 1; }
jq -c --arg tid "$TID" --argjson e "$EPOCH" --arg shift "$(uuid)" --arg c "$CASHIER_SUB" --arg sku "$SOCKS" \
  --arg o "$(date -u -d '-10 min' +%FT%TZ)" --arg s "$(date -u -d '-5 min' +%FT%TZ)" --argjson n "$OFFLINE" \
  --rawfile ids <(for ((i = 0; i < OFFLINE; i++)); do uuid; done) '
  (.series) as $series | (.block.first) as $first | ($ids | split("\n") | map(select(length > 0))) as $u |
  {terminalId:$tid, epoch:$e, ops:([{seq:1, type:"SHIFT_OPENED", v:2,
     payload:{shiftId:$shift, cashier:$c, openingFloat:500.00, openedAt:$o}}] +
   [range(0; $n) as $i | {seq:($i + 2), type:"SALE", v:2, payload:{saleId:$u[$i], shiftId:$shift,
     invoiceNo:($series + "-" + (("00000" + (($first + $i) | tostring))[-5:])), cashier:$c, soldAt:$s,
     lines:[{lineNo:1, sku:$sku, qty:2, unitPrice:105.00, taxRate:5, promoDiscount:0, manualDiscount:0}],
     payments:[{method:"CASH", amount:210.00}], changeDue:0,
     totals:{taxable:200.00, cgst:5.00, sgst:5.00, igst:0, total:210.00}, promotions:[]}}])}' <<< "$BLOCK" > "$tmp/push.json"
t0=$(ms); P1=$(expect 200 "$TILL" POST /v1/sync/push "@$tmp/push.json"); push_ms=$(($(ms) - t0))
t0=$(ms); P2=$(expect 200 "$TILL" POST /v1/sync/push "@$tmp/push.json"); replay_ms=$(($(ms) - t0))
applied=$(jq '[.results[] | select(.status == "APPLIED")] | length' <<< "$P1")
dupes=$(jq '[.results[] | select(.status == "DUPLICATE")] | length' <<< "$P2")
socks_left=$(on_hand "$SOCKS")

# 3. How long the Kafka-fed rollup takes to show every invoice.
DAY=$(date +%F)
want=$((ok + OFFLINE))
invoices() {
  call "$MGR" GET "/v1/analytics/sales?store=$STORE&from=$DAY&to=$DAY" | head -n -1 | jq -r '[.days[].invoices] | add // 0'
}
t0=$(ms); until_eq 300 "$want" invoices > /dev/null; lag_ms=$(($(ms) - t0))

[[ $ok == "$STOCK" && $left == 0 && $negatives == 0 && $other == 0 ]] || { echo "online oversell check failed" >&2; exit 1; }
[[ $applied == $((OFFLINE + 1)) && $dupes == $((OFFLINE + 1)) && $socks_left == 0 ]] || { echo "sync check failed" >&2; exit 1; }

report=$(cat <<MD
# StoreSync bench, $(date '+%Y-%m-%d %H:%M IST')

One run of \`scripts/bench.sh\` (N=$N, C=$C, OFFLINE=$OFFLINE) against the compose stack on one machine
($(nproc) CPUs, $(free -g | awk '/Mem:/{print $2}') GiB RAM; app, Postgres, Kafka, Redis and the bench client all share it).
Numbers are wall-clock from the client and come from a single run; treat them as a smoke-level indication, not
capacity figures.

| What | Result |
|---|---|
| Online sales attempted, one unit each, $C at a time, against stock of $STOCK | $N |
| Accepted / refused \`insufficient-stock\` / other | $ok / $refused / $other |
| Stock left / stock movements below zero | $left / $negatives |
| Wall time for all $N, throughput | $online_ms ms, $(( N * 1000 / (online_ms > 0 ? online_ms : 1) )) requests/s |
| Sale latency p50 / p95 / p99 (accepted and refused together) | $(percentile "$tmp/online_ms" 50) / $(percentile "$tmp/online_ms" 95) / $(percentile "$tmp/online_ms" 99) ms |
| Offline sync: one push of $((OFFLINE + 1)) ops (shift open + $OFFLINE sales) | $push_ms ms, $applied APPLIED |
| Same queue pushed again | $replay_ms ms, $dupes DUPLICATE; socks left $socks_left of $((OFFLINE * 2)), so each sale was taken once |
| Rollup showing all $want invoices, timed from after the replay (polled every 0.5 s) | $lag_ms ms |
MD
)
echo "$report"
if [[ -n ${OUT:-} ]]; then echo "$report" > "$OUT"; fi
exit 0
