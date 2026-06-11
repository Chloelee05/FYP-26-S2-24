package com.auction.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuctionType enum")
class AuctionTypeTest {

    @Test
    @DisplayName("getAuctionType resolves by id")
    void byId() {
        assertEquals(AuctionType.PRICE_UP, AuctionType.getAuctionType(1));
        assertEquals(AuctionType.DUTCH_AUCTION, AuctionType.getAuctionType(2));
        assertEquals(AuctionType.BLIND, AuctionType.getAuctionType(3));
    }

    @Test
    @DisplayName("invalid id throws")
    void invalidId() {
        assertThrows(IllegalArgumentException.class, () -> AuctionType.getAuctionType(99));
    }

    @Test
    @DisplayName("getId returns stable values")
    void ids() {
        assertEquals(1, AuctionType.PRICE_UP.getId());
        assertEquals(2, AuctionType.DUTCH_AUCTION.getId());
        assertEquals(3, AuctionType.BLIND.getId());
    }
}
