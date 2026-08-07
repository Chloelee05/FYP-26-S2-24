package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read access to the auction tagging vocabulary.
 *
 * <p>Reads {@code tags} (the tag dictionary) and {@code auction_tag_info} (the many-to-many join
 * between auctions and tags). Called by the listing creation and search API servlets to populate
 * tag pickers and to narrow a search by tag. This class only reads; tag rows are written by
 * {@link SellerAuctionDAO} when a seller saves a listing.</p>
 */
public class AuctionTagsDAO {

    /** Every tag in the dictionary, keyed by tag id, ordered by name for a stable picker. */
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

    /**
     * The tags attached to one auction, as (id, name) pairs. The join walks from the link table
     * {@code auction_tag_info} back to {@code tags} to get the display name.
     */
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

    /**
     * Auction ids carrying <em>all</em> of the supplied tags, not merely any of them.
     *
     * @param tags tag ids to match; an empty or null list short-circuits to an empty result
     */
    public List<Long> findAuctionByTag(List<Long> tags) throws Exception
    {
        if (tags == null || tags.isEmpty())
        {
            return new ArrayList<>();
        }
        try(Connection conn = DBUtil.connectDB()){
            List<Long> listOfAuctions = new ArrayList<>();
            // One "?" per tag id. The list is variable length, so the IN clause has to be built at
            // runtime, but the values still go through setLong rather than string concatenation.
            String placeholders = String.join(", ", Collections.nCopies(tags.size(), "?"));

            // "Relational division": group the matching link rows per auction and keep only the
            // auctions whose distinct matched-tag count equals the number of tags asked for. That
            // turns an OR-style IN filter into AND semantics, so an auction tagged only "vintage"
            // is excluded from a search for "vintage" plus "camera".
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
