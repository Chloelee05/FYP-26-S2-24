package com.auction.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure price-clock math for Dutch ("low start high" → descending) auctions.
 *
 * <p>The clock starts at {@code startPrice} when the auction opens and decreases
 * linearly to {@code floorPrice} by the end time. A buyer wins by accepting the
 * current clock price; the first acceptance ends the auction.</p>
 */
public final class DutchClock {

    private DutchClock() { }

    /**
     * Returns the clock price at {@code now}, clamped to {@code [floorPrice, startPrice]}.
     *
     * @param startPrice price at the moment the auction opened (the high start)
     * @param floorPrice lowest price the clock may reach (null treated as 0)
     * @param start      auction open time
     * @param end        auction end time
     * @param now        evaluation instant
     */
    public static BigDecimal currentPrice(BigDecimal startPrice, BigDecimal floorPrice,
                                          Instant start, Instant end, Instant now) {
        if (startPrice == null) startPrice = BigDecimal.ZERO;
        if (floorPrice == null) floorPrice = BigDecimal.ZERO;
        if (floorPrice.compareTo(startPrice) > 0) floorPrice = startPrice;

        if (start == null || end == null || !end.isAfter(start)) {
            return scale(startPrice);
        }
        if (!now.isAfter(start)) return scale(startPrice);
        if (!now.isBefore(end))  return scale(floorPrice);

        long total = Duration.between(start, end).toMillis();
        long elapsed = Duration.between(start, now).toMillis();
        BigDecimal fraction = BigDecimal.valueOf(elapsed)
                .divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_UP);

        BigDecimal drop = startPrice.subtract(floorPrice).multiply(fraction);
        BigDecimal price = startPrice.subtract(drop);
        if (price.compareTo(floorPrice) < 0) price = floorPrice;
        if (price.compareTo(startPrice) > 0) price = startPrice;
        return scale(price);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
