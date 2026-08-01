package com.auction.dao;

import com.auction.dao.BidDAO.BidOutcome;
import com.auction.dao.BidDAO.BidResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who a bid actually displaced — the arithmetic behind the corrected OUTBID recipient.
 *
 * <p>The defect these cover: {@code processAutoBids} runs inside {@code placeBid}'s own
 * transaction, so by the time anything could query the bid table, an auto-bidder who
 * counter-bid is simultaneously the current leader <em>and</em> the highest bidder other than
 * the manual bidder. Deriving the recipient from that state names the winner and leaves the
 * person who was genuinely knocked off the top with no notification at all.</p>
 */
@DisplayName("BidOutcome — who was actually outbid")
class BidOutcomeTest {

    private static final int MANUAL_BIDDER = 5;
    private static final int PREVIOUS_LEADER = 3;
    private static final int AUTO_BIDDER = 8;

    private static BidOutcome outcome(Integer previousTop, Integer finalTop) {
        return new BidOutcome(BidResult.SUCCESS, previousTop, finalTop, MANUAL_BIDDER);
    }

    @Test
    @DisplayName("A plain manual bid displaces whoever held the top bid")
    void manualBidDisplacesThePreviousLeader() {
        assertEquals(PREVIOUS_LEADER,
                outcome(PREVIOUS_LEADER, MANUAL_BIDDER).displacedBidderId());
    }

    @Test
    @DisplayName("When a proxy auto-bid counter-bids, the displaced bidder is the manual bidder — "
            + "not the auto-bidder who is now winning")
    void autoBidCounterBidDisplacesTheManualBidder() {
        // Lead sequence: AUTO_BIDDER → MANUAL_BIDDER → AUTO_BIDDER, all inside one transaction.
        BidOutcome o = outcome(AUTO_BIDDER, AUTO_BIDDER);

        assertEquals(MANUAL_BIDDER, o.displacedBidderId(),
                "the person who lost the lead is the one who just bid manually");
        assertNotEquals(AUTO_BIDDER, o.displacedBidderId(),
                "the winning auto-bidder must never be told they were outbid");
    }

    @Test
    @DisplayName("A counter-bid by a third party also displaces the manual bidder")
    void thirdPartyCounterBidDisplacesTheManualBidder() {
        assertEquals(MANUAL_BIDDER, outcome(PREVIOUS_LEADER, AUTO_BIDDER).displacedBidderId());
    }

    @Test
    @DisplayName("The first bid on an auction displaces nobody")
    void firstBidDisplacesNobody() {
        assertNull(outcome(null, MANUAL_BIDDER).displacedBidderId());
    }

    @Test
    @DisplayName("Raising your own top bid does not notify you that you outbid yourself")
    void raisingYourOwnBidNotifiesNobody() {
        assertNull(outcome(MANUAL_BIDDER, MANUAL_BIDDER).displacedBidderId(),
                "the previous and current leader are the same person");
    }

    @Test
    @DisplayName("Nobody is ever named who is currently the leader")
    void theCurrentLeaderIsNeverTheRecipient() {
        for (Integer previous : new Integer[] { null, PREVIOUS_LEADER, MANUAL_BIDDER, AUTO_BIDDER }) {
            for (Integer current : new Integer[] { MANUAL_BIDDER, AUTO_BIDDER }) {
                Integer displaced = outcome(previous, current).displacedBidderId();
                assertNotEquals(current, displaced,
                        "previousTop=" + previous + " finalTop=" + current);
            }
        }
    }

    @Test
    @DisplayName("A rejected bid displaces nobody, whatever the reason")
    void rejectionsDisplaceNobody() {
        for (BidResult r : BidResult.values()) {
            if (r == BidResult.SUCCESS) continue;
            assertNull(BidOutcome.of(r).displacedBidderId(), r.name());
            assertFalse(BidOutcome.of(r).isSuccess(), r.name());
        }
    }

    @Test
    @DisplayName("A success with no leader information reports nobody rather than guessing user 0")
    void missingLeaderInformationIsNotUserZero() {
        assertNull(BidOutcome.of(BidResult.SUCCESS).displacedBidderId());
    }
}
