package com.storesync.promo.domain;

import java.math.BigDecimal;

public record BasketLine(int lineNo, String sku, int qty, BigDecimal unitPrice) {
}
