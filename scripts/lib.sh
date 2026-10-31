# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://127.0.0.1:8980}
OAUTH=${OAUTH:-http://127.0.0.1:8981}
ADMIN_SUB=${ADMIN_SUB:-admin@storesync.example.test}

# The app validates the issuer as http://oauth:8080/storesync (its in-network name), and mock-oauth2-server
# derives the issuer from the Host header. The client_id becomes the token subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d scope=storesync "$OAUTH/storesync/token" | jq -r .access_token
}

# call TOKEN METHOD PATH [BODY] [EXTRA_CURL_ARGS...]; prints the body, then the status on its own line.
call() {
  local tok=$1 method=$2 path=$3 body=${4:-}
  shift 4 2> /dev/null || shift $#
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" ${tok:+-H "Authorization: Bearer $tok"} \
    -H 'Content-Type: application/json' ${body:+-d "$body"} "$@"
}

# expect STATUS TOKEN METHOD PATH [BODY] [EXTRA...]; prints the body or fails loudly (never prints the token).
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $2 $3 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# until_eq SECONDS WANT CMD...; reruns CMD until it prints WANT, or fails after SECONDS.
until_eq() {
  local limit=$1 want=$2 got=""
  shift 2
  for ((i = 0; i < limit * 2; i++)); do
    got=$("$@" 2> /dev/null || true)
    [[ $got == "$want" ]] && { echo "$got"; return 0; }
    sleep 0.5
  done
  echo "timed out after ${limit}s; last value '$got', wanted '$want'" >&2
  return 1
}

uuid() { cat /proc/sys/kernel/random/uuid; }

percentile() {
  [[ -s $1 ]] || { echo n/a; return; }
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}

# ean13 TWELVE_DIGITS; prints the code with its check digit.
ean13() {
  local s=0 i d
  for ((i = 0; i < 12; i++)); do d=${1:i:1}; ((i % 2 == 0)) && s=$((s + d)) || s=$((s + 3 * d)); done
  echo "$1$(((10 - s % 10) % 10))"
}

# openssl instead of tr < /dev/urandom | head: with pipefail the SIGPIPE from head would end the script.
rand_code() { openssl rand -hex 16 | tr 'a-f' 'A-F' | cut -c1-"$1"; }

# new_store STATE; creates a store with a manager and a cashier and sets STORE, MGR, CASHIER (tokens) and
# CASHIER_SUB. Codes are random so the scripts can run more than once on the same stack.
new_store() {
  STORE="D$(rand_code 3)"
  local s=${STORE,,}
  expect 201 "$ADMIN" POST /v1/stores "{\"code\":\"$STORE\",\"name\":\"Demo store $STORE\",\"stateCode\":\"$1\",
    \"gstin\":\"${1}ABCDE1234F1Z5\"}" > /dev/null
  expect 201 "$ADMIN" POST "/v1/stores/$STORE/staff" "{\"subject\":\"mgr-$s@storesync.example.test\",
    \"role\":\"MANAGER\",\"name\":\"Manager $STORE\"}" > /dev/null
  CASHIER_SUB="cashier-$s@storesync.example.test"
  expect 201 "$ADMIN" POST "/v1/stores/$STORE/staff" "{\"subject\":\"$CASHIER_SUB\",\"role\":\"CASHIER\",
    \"name\":\"Cashier $STORE\"}" > /dev/null
  MGR=$(token "mgr-$s@storesync.example.test")
  CASHIER=$(token "$CASHIER_SUB")
}

# new_product PRICE RATE; prints the SKU. PRICE is GST-inclusive.
new_product() {
  local sku="SKU-$(rand_code 6)"
  local bc
  bc=$(ean13 "89$(printf '%010d' $((RANDOM * 32768 + RANDOM)))")
  expect 201 "$ADMIN" POST /v1/products "{\"sku\":\"$sku\",\"barcode\":\"$bc\",\"name\":\"Demo item $sku\",
    \"hsn\":\"6109\",\"taxRate\":$2,\"price\":$1,\"cost\":$(jq -n "$1/2"),\"lowStockLevel\":2}" > /dev/null
  echo "$sku"
}

on_hand() { call "$MGR" GET "/v1/stores/$STORE/stock/$1" | head -n -1 | jq -r .onHand; }

# sale_body ID SKU QTY METHOD AMOUNT
sale_body() {
  jq -nc --arg id "$1" --arg st "$STORE" --arg sku "$2" --argjson q "$3" --arg m "$4" --argjson a "$5" \
    '{id:$id, store:$st, lines:[{sku:$sku, qty:$q}], payments:[{method:$m, amount:$a, reference:("REF-" + $id[0:8])}]}'
}
