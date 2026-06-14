package com.auction.realtime;

import com.auction.model.AuctionStatus;
import com.auction.model.AuctionType;
import com.auction.util.DBUtil;
import com.auction.util.DutchClock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds a public, strategy-aware snapshot of an auction's live state and
 * broadcasts it over {@link AuctionEventBus} as a {@code bid} SSE event.
 *
 * <p>Visibility rules per auction type:
 * <ul>
 *   <li><b>PRICE_UP</b>: current highest bid + bid count.</li>
 *   <li><b>DUTCH</b>: descending clock price (computed from start/floor/time);
 *       hidden once accepted/closed.</li>
 *   <li><b>BLIND</b>: amounts hidden while open (only sealed-bid count shown);
 *       revealed after close.</li>
 * </ul>
 */
public final class AuctionEventPublisher {

    private static final Logger LOG = Logger.getLogger(AuctionEventPublisher.class.getName());

    private AuctionEventPublisher() { }

    /** Recomputes the live snapshot for {@code auctionId} and broadcasts it. */
    public static void publishSnapshot(long auctionId) {
        try {
            Map<String, Object> snapshot = buildSnapshot(auctionId);
            if (snapshot != null) {
                AuctionEventBus.getInstance().publish(auctionId, "bid", snapshot);
            }
        } catch (Exception e) {
            LOG.warning("Failed to publish SSE snapshot for auction " + auctionId + ": " + e.getMessage());
        }
    }

    static Map<String, Object> buildSnapshot(long auctionId) {
        String sql =
                "SELECT a.status_id, a.auction_type, a.date_created, a.date_end, a.moderation_state, "
                + "d.starting_price, d.dutch_floor_price, "
                + "COALESCE(MAX(b.bid_amount), d.starting_price) AS current_bid, "
                + "COUNT(b.bid_id)::int AS bid_count "
                + "FROM auction a "
                + "JOIN auction_details d ON d.id = a.auction_id "
                + "LEFT JOIN bids b ON b.auction_id = a.auction_id "
                + "WHERE a.auction_id = ? "
                + "GROUP BY a.status_id, a.auction_type, a.date_created, a.date_end, "
                + "         a.moderation_state, d.starting_price, d.dutch_floor_price";

        try (Connection conn = DBUtil.connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                int statusId = rs.getInt("status_id");
                int typeId = rs.getInt("auction_type");
                Instant dateCreated = rs.getTimestamp("date_created").toInstant();
                Instant dateEnd = rs.getTimestamp("date_end").toInstant();
                String modState = rs.getString("moderation_state");
                BigDecimal startingPrice = rs.getBigDecimal("starting_price");
                if (startingPrice == null) startingPrice = BigDecimal.ZERO;
                BigDecimal dutchFloor = rs.getBigDecimal("dutch_floor_price");
                BigDecimal currentBid = rs.getBigDecimal("current_bid");
                int bidCount = rs.getInt("bid_count");

                Instant nowPub = Instant.now();
                boolean open = statusId == AuctionStatus.ACTIVE.getId()
                        && "active".equals(modState)
                        && nowPub.isBefore(dateEnd)
                        && !nowPub.isBefore(dateCreated);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("auctionId", auctionId);
                body.put("open", open);
                body.put("statusId", statusId);
                body.put("auctionType", typeId);
                body.put("endTime", dateEnd.toString());

                AuctionType type;
                try { type = AuctionType.getAuctionType(typeId); }
                catch (IllegalArgumentException e) { type = AuctionType.PRICE_UP; }

                switch (type) {
                    case DUTCH_AUCTION:
                        if (open) {
                            BigDecimal clock = DutchClock.currentPrice(
                                    startingPrice, dutchFloor, dateCreated, dateEnd, Instant.now());
                            body.put("currentBid", clock);
                            body.put("numBids", 0);
                        } else {
                            body.put("currentBid", currentBid);
                            body.put("numBids", bidCount);
                        }
                        break;
                    case BLIND:
                        // Sealed: hide the amount while open; reveal the winning bid once closed.
                        body.put("currentBid", open ? null : currentBid);
                        body.put("numBids", bidCount);
                        break;
                    case PRICE_UP:
                    default:
                        body.put("currentBid", currentBid);
                        body.put("numBids", bidCount);
                        break;
                }
                return body;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
