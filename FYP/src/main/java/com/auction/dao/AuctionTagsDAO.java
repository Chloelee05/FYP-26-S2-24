package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

public class AuctionTagsDAO {

    public Map<Long, String> getAllTags() throws Exception {
        try(Connection conn = DBUtil.connectDB()){
            Map<Long, String> listOfTags = new HashMap<>();
            String sqlString = "SELECT * FROM tags ORDER BY tag_name";
            try(PreparedStatement pStatement = conn.prepareStatement(sqlString)){
                try(ResultSet rs = pStatement.executeQuery())
                {
                    while(rs.next()) {
                        listOfTags.put(rs.getLong("id"), rs.getString("tag_name"));
                    }
                }
            }
            return listOfTags;
        }
    }

    public List<Map.Entry<Long, String>> getTagsForAuction(long auctionId) throws Exception {
        try (Connection conn = DBUtil.connectDB()) {
            List<Map.Entry<Long, String>> result = new ArrayList<>();
            String sql = "SELECT t.id, t.tag_name FROM tags t "
                    + "JOIN auction_tag_info ati ON ati.tag_id = t.id "
                    + "WHERE ati.auction_id = ? ORDER BY t.tag_name";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, auctionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new AbstractMap.SimpleEntry<>(rs.getLong("id"), rs.getString("tag_name")));
                    }
                }
            }
            return result;
        }
    }

    public List<Long> findAuctionByTag(List<Long> tags) throws Exception
    {
        if (tags == null || tags.isEmpty())
        {
            return new ArrayList<>();
        }
        try(Connection conn = DBUtil.connectDB()){
            List<Long> listOfAuctions = new ArrayList<>();
            String placeholders = String.join(", ", Collections.nCopies(tags.size(), "?"));

            String searchSQL = "SELECT auction_id FROM auction_tag_info WHERE tag_id IN (" + placeholders + ") " +
                    "GROUP BY auction_id HAVING COUNT(DISTINCT tag_id) = ?";
            try (PreparedStatement stmt = conn.prepareStatement(searchSQL)) {
                int i = 1;
                for (Long tag : tags) {
                    stmt.setLong(i++, tag);
                }
                stmt.setInt(i, tags.size()); // count
                try(ResultSet rs = stmt.executeQuery())
                {
                    while(rs.next()){
                        listOfAuctions.add(rs.getLong("auction_id"));
                    }
                }
            }
            return listOfAuctions;
        }
    }
}
