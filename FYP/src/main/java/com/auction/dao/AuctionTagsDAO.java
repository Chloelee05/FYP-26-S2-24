package com.auction.dao;

import com.auction.util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

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
}
