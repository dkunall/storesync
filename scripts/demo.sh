#!/usr/bin/env bash
# End-to-end walk through a store's day against a running stack (scripts/up.sh): catalog, stock, online sales racing
# for the last units, retries, an offline till syncing (and re-syncing) its queue, and the analytics read side.
# Everything here is made up; addresses use the reserved example.test domain.
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh
export TZ=Asia/Kolkata

echo "== StoreSync demo ($(date '+%Y-%m-%d %H:%M IST'))"
ADMIN=$(token "$ADMIN_SUB")

echo "== 1. A store in Karnataka, two products, stock counted in"
new_store 29
TEE=$(new_product 118.00 18)
SOCKS=$(new_product 105.00 5)
expect 201 "$MGR" POST "/v1/stores/$STORE/stock-counts" "{\"id\":\"$(uuid)\",\"sku\":\"$TEE\",\"counted\":5}" > /dev/null
expect 201 "$MGR" POST "/v1/stores/$STORE/stock-counts" "{\"id\":\"$(uuid)\",\"sku\":\"$SOCKS\",\"counted\":10}" > /dev/null
check "store $STORE has 5 of $TEE" "$(on_hand "$TEE")" 5
BC=$(expect 200 "$ADMIN" GET "/v1/products/$TEE" | jq -r .barcode)
check "barcode scan finds the product" "$(expect 200 "$CASHIER" GET "/v1/products/by-barcode/$BC" | jq -r .sku)" "$TEE"

echo "== 2. An online UPI sale, retried by a flaky client"
ID=$(uuid)
S1=$(expect 201 "$CASHIER" POST /v1/sales "$(sale_body "$ID" "$TEE" 2 UPI 236.00)")
S2=$(call "$CASHIER" POST /v1/sales "$(sale_body "$ID" "$TEE" 2 UPI 236.00)" | head -n -1)
check "retry returns the same invoice" "$(jq -r .sale.invoiceNo <<< "$S2")" "$(jq -r .sale.invoiceNo <<< "$S1")"
check "GST split intra-state (CGST 18.00)" "$(jq -r ".sale.cgst == 18" <<< "$S1")" true
check "stock taken once" "$(on_hand "$TEE")" 3
check "same id, different sale is refused" \
  "$(call "$CASHIER" POST /v1/sales "$(sale_body "$ID" "$TEE" 1 UPI 118.00)" | tail -n1)" 422

echo "== 3. Ten tills race for the last 3 tees"
tmp=$(mktemp -d /tmp/storesync-demo.XXXX)
trap 'rm -rf "$tmp"' EXIT
for i in $(seq 10); do
  ({ call "$CASHIER" POST /v1/sales "$(sale_body "$(uuid)" "$TEE" 1 CARD 118.00)" | tail -n1; echo; } > "$tmp/r$i") &
done
wait
check "3 sales went through" "$(cat "$tmp"/r* | grep -c '^201$')" 3
check "7 were refused (insufficient-stock)" "$(cat "$tmp"/r* | grep -c '^409$')" 7
check "stock is 0, not negative" "$(on_hand "$TEE")" 0

echo "== 4. An offline till: enrol, take an invoice block, sell, sync, then sync the same queue again"
TILL_SUB="till-${STORE,,}-1@storesync.example.test"
TERM=$(expect 201 "$MGR" POST "/v1/stores/$STORE/terminals" "{\"number\":1,\"subject\":\"$TILL_SUB\"}")
TID=$(jq -r .id <<< "$TERM")
EPOCH=$(jq -r .epoch <<< "$TERM")
TILL=$(token "$TILL_SUB")
BLOCK=$(expect 201 "$TILL" POST "/v1/terminals/$TID/invoice-blocks" '{}')
INV=$(jq -r '.series + "-" + (.block.first | tostring | ("00000" + .)[-5:])' <<< "$BLOCK")
SHIFT=$(uuid)
SALE=$(uuid)
OPENED=$(date -u -d '-2 min' +%FT%TZ)
SOLD=$(date -u -d '-1 min' +%FT%TZ)
OPS=$(jq -nc --arg tid "$TID" --argjson e "$EPOCH" --arg shift "$SHIFT" --arg sale "$SALE" --arg inv "$INV" \
  --arg c "$CASHIER_SUB" --arg sku "$SOCKS" --arg o "$OPENED" --arg s "$SOLD" '{terminalId:$tid, epoch:$e, ops:[
  {seq:1, type:"SHIFT_OPENED", v:2, payload:{shiftId:$shift, cashier:$c, openingFloat:500.00, openedAt:$o}},
  {seq:2, type:"SALE", v:2, payload:{saleId:$sale, shiftId:$shift, invoiceNo:$inv, cashier:$c, soldAt:$s,
    lines:[{lineNo:1, sku:$sku, qty:2, unitPrice:105.00, taxRate:5, promoDiscount:0, manualDiscount:0}],
    payments:[{method:"CASH", amount:250.00}], changeDue:40.00,
    totals:{taxable:200.00, cgst:5.00, sgst:5.00, igst:0, total:210.00}, promotions:[]}}]}')
P1=$(expect 200 "$TILL" POST /v1/sync/push "$OPS")
check "first sync applies both ops" "$(jq -c '[.results[].status]' <<< "$P1")" '["APPLIED","APPLIED"]'
P2=$(expect 200 "$TILL" POST /v1/sync/push "$OPS")
check "re-sync is a no-op" "$(jq -c '[.results[].status]' <<< "$P2")" '["DUPLICATE","DUPLICATE"]'
check "offline sale took 2 socks once" "$(on_hand "$SOCKS")" 8
check "offline invoice number kept" "$(expect 200 "$MGR" GET "/v1/sales/$SALE" | jq -r .sale.invoiceNo)" "$INV"

echo "== 5. Analytics catch up from Kafka"
DAY=$(date +%F)
invoices() {
  call "$MGR" GET "/v1/analytics/sales?store=$STORE&from=$DAY&to=$DAY" | head -n -1 | jq -r '[.days[].invoices] | add // 0'
}
until_eq 60 5 invoices > /dev/null
check "rollup counts 5 invoices (1 + 3 online, 1 offline)" "$(invoices)" 5
lowstock() { call "$MGR" GET "/v1/stores/$STORE/alerts" | head -n -1 | jq -r --arg s "$TEE" '[.[] | select(.sku == $s and .type == "LOW_STOCK")] | length'; }
check "one LOW_STOCK alert for the tee" "$(until_eq 60 1 lowstock)" 1

echo "== demo finished: all checks passed"
