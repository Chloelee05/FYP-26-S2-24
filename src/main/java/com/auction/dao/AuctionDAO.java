package com.auction.dao;

import com.auction.model.Auction;
import com.auction.util.DBUtil;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class AuctionDAO {

    private static final String SELECT_RICH =
        "SELECT a.*, p.name AS product_name, p.description AS product_description, " +
        "p.image_url AS product_image_url, u.username AS seller_username, " +
        "c.name AS category_name, " +
        "(SELECT COUNT(*) FROM bids b WHERE b.auction_id = a.id) AS bid_count, " +
        "w.username AS winner_username " +
        "FROM auctions a " +
        "JOIN products p ON a.product_id = p.id " +
        "JOIN users u ON p.seller_id = u.id " +
        "LEFT JOIN categories c ON p.category_id = c.id " +
        "LEFT JOIN users w ON a.winner_id = w.id ";

    public Auction findById(int id) {
        String sql = SELECT_RICH + "WHERE a.id = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setInt(1, id);
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

    public List<Auction> findActive() {
        String sql = SELECT_RICH + "WHERE a.status = 'ACTIVE' AND a.end_time > NOW() ORDER BY a.end_time ASC";
        return queryList(sql);
    }

    public List<Auction> findAll() {
        String sql = SELECT_RICH + "ORDER BY a.created_at DESC";
        return queryList(sql);
    }

    public List<Auction> findBySeller(int sellerId) {
        String sql = SELECT_RICH + "WHERE p.seller_id = ? ORDER BY a.created_at DESC";
        List<Auction> list = new ArrayList<>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setInt(1, sellerId);
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

    public List<Auction> search(String keyword) {
        String sql = SELECT_RICH + "WHERE (p.name LIKE ? OR p.description LIKE ?) ORDER BY a.created_at DESC";
        List<Auction> list = new ArrayList<>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            String like = "%" + keyword + "%";
            ps.setString(1, like);
            ps.setString(2, like);
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

    public boolean insert(Auction auction) {
        String sql = "INSERT INTO auctions (product_id, start_price, current_price, bid_increment, start_time, end_time, status, strategy) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, auction.getProductId());
            ps.setBigDecimal(2, auction.getStartPrice());
            ps.setBigDecimal(3, auction.getCurrentPrice());
            ps.setBigDecimal(4, auction.getBidIncrement());
            ps.setTimestamp(5, auction.getStartTime());
            ps.setTimestamp(6, auction.getEndTime());
            ps.setString(7, auction.getStatus() != null ? auction.getStatus() : "ACTIVE");
            ps.setString(8, auction.getStrategy() != null ? auction.getStrategy() : "PRICE_UP");
            int rows = ps.executeUpdate();
            if (rows > 0) {
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    auction.setId(keys.getInt(1));
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

    public boolean updateCurrentPrice(int auctionId, BigDecimal newPrice) {
        String sql = "UPDATE auctions SET current_price = ? WHERE id = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setBigDecimal(1, newPrice);
            ps.setInt(2, auctionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(ps, conn);
        }
    }

    public boolean updateStatus(int auctionId, String status) {
        String sql = "UPDATE auctions SET status = ? WHERE id = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, status);
            ps.setInt(2, auctionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(ps, conn);
        }
    }

    public boolean cancel(int auctionId) {
        return updateStatus(auctionId, "CANCELLED");
    }

    public int countActive() {
        String sql = "SELECT COUNT(*) FROM auctions WHERE status = 'ACTIVE'";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
            rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            DBUtil.close(rs, ps, conn);
        }
        return 0;
    }

    private List<Auction> queryList(String sql) {
        List<Auction> list = new ArrayList<>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = DBUtil.getConnection();
            ps = conn.prepareStatement(sql);
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

    private Auction mapRow(ResultSet rs) throws SQLException {
        Auction a = new Auction();
        a.setId(rs.getInt("id"));
        a.setProductId(rs.getInt("product_id"));
        a.setStartPrice(rs.getBigDecimal("start_price"));
        a.setCurrentPrice(rs.getBigDecimal("current_price"));
        a.setBidIncrement(rs.getBigDecimal("bid_increment"));
        a.setStartTime(rs.getTimestamp("start_time"));
        a.setEndTime(rs.getTimestamp("end_time"));
        a.setStatus(rs.getString("status"));
        a.setStrategy(rs.getString("strategy"));
        int winnerId = rs.getInt("winner_id");
        a.setWinnerId(rs.wasNull() ? null : winnerId);
        a.setCreatedAt(rs.getTimestamp("created_at"));
        try {
            a.setProductName(rs.getString("product_name"));
            a.setProductDescription(rs.getString("product_description"));
            a.setProductImageUrl(rs.getString("product_image_url"));
            a.setSellerUsername(rs.getString("seller_username"));
            a.setCategoryName(rs.getString("category_name"));
            a.setBidCount(rs.getInt("bid_count"));
            a.setWinnerUsername(rs.getString("winner_username"));
        } catch (SQLException ignored) {}
        return a;
    }
}
