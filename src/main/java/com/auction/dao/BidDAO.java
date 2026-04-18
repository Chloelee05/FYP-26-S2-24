package com.auction.dao;

import com.auction.model.Bid;
import com.auction.util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class BidDAO {

    public List<Bid> findByAuction(int auctionId) {
        String sql = "SELECT b.*, u.username AS buyer_username " +
                     "FROM bids b JOIN users u ON b.buyer_id = u.id " +
                     "WHERE b.auction_id = ? ORDER BY b.amount DESC, b.bid_time ASC";
        List<Bid> list = new ArrayList<>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setInt(1, auctionId);
            rs = ps.executeQuery();
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(rs, ps, conn);
        }
        return list;
    }

    public Bid findHighestBid(int auctionId) {
        String sql = "SELECT b.*, u.username AS buyer_username " +
                     "FROM bids b JOIN users u ON b.buyer_id = u.id " +
                     "WHERE b.auction_id = ? ORDER BY b.amount DESC LIMIT 1";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setInt(1, auctionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(rs, ps, conn);
        }
        return null;
    }

    public boolean insert(Bid bid) {
        String sql = "INSERT INTO bids (auction_id, buyer_id, amount) VALUES (?, ?, ?)";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, bid.getAuctionId());
            ps.setInt(2, bid.getBuyerId());
            ps.setBigDecimal(3, bid.getAmount());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    bid.setId(keys.getInt(1));
                }
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(ps, conn);
        }
        return false;
    }

    public int countByAuction(int auctionId) {
        String sql = "SELECT COUNT(*) FROM bids WHERE auction_id = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setInt(1, auctionId);
            rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(rs, ps, conn);
        }
        return 0;
    }

    private Bid mapRow(ResultSet rs) throws SQLException {
        Bid b = new Bid();
        b.setId(rs.getInt("id"));
        b.setAuctionId(rs.getInt("auction_id"));
        b.setBuyerId(rs.getInt("buyer_id"));
        b.setAmount(rs.getBigDecimal("amount"));
        b.setBidTime(rs.getTimestamp("bid_time"));
        try {
            b.setBuyerUsername(rs.getString("buyer_username"));
        } catch (SQLException ignored) {}
        return b;
    }
}
