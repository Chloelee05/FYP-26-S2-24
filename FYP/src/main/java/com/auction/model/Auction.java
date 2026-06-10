package com.auction.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class Auction implements  Serializable{
    private int auction_id;
    private int seller_id;
    private String auction_name;
    private String auction_details;
    private Instant start_date;
    private Instant end_date;
    private float starting_price;
    private BigDecimal maxPrice;    // null = no cap (SCRUM-33)
    private int quantity = 1;
    private BigDecimal costPrice;       // seller's private cost; null = unset
    private BigDecimal dutchFloorPrice; // Dutch clock floor; null = unset
    private AuctionType auctionType;
    private ItemCondition itemCondition;
    private List<Long> auctionTagsList;
    private String category;

    public Auction(){
    }

    public Auction(int seller_id, String auction_name, String auction_details, Instant start_date, Instant end_date,
                   float starting_price, AuctionType auctionType, ItemCondition itemCondition, List<Long> auctionTagsList)
    {
        this.seller_id = seller_id;
        this.auction_name = auction_name;
        this.auction_details = auction_details;
        this.start_date = start_date;
        this.end_date = end_date;
        this.starting_price = starting_price;
        this.auctionType = auctionType;
        this.itemCondition = itemCondition;
        this.auctionTagsList = auctionTagsList;
    }

    public int getAuction_id() {
        return this.auction_id;
    }

    public void setAuction_id(int auction_id)
    {
        this.auction_id = auction_id;
    }

    public int getSeller_id() {
        return this.seller_id;
    }

    public void setSeller_id(int seller_id){
        this.seller_id = seller_id;
    }

    public String getAuction_name()
    {
        return this.auction_name;
    }

    public void setAuction_name(String auction_name)
    {
        this.auction_name = auction_name;
    }

    public String getAuction_details()
    {
        return this.auction_details;
    }

    public void setAuction_details(String auction_details) {
        this.auction_details = auction_details;
    }

    public Instant getStart_date() {
        return this.start_date;
    }

    public void setStart_date(Instant start_date)
    {
        this.start_date = start_date;
    }

    public Instant getEnd_date() {
        return this.end_date;
    }

    public void setEnd_date(Instant end_date){
        this.end_date = end_date;
    }

    public float getStarting_price() {
        return this.starting_price;
    }

    public void setStarting_price(float starting_price) {
        this.starting_price = starting_price;
    }

    public BigDecimal getMaxPrice() { return this.maxPrice; }

    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }

    public int getQuantity() { return this.quantity; }

    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getCostPrice() { return this.costPrice; }

    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }

    public BigDecimal getDutchFloorPrice() { return this.dutchFloorPrice; }

    public void setDutchFloorPrice(BigDecimal dutchFloorPrice) { this.dutchFloorPrice = dutchFloorPrice; }

    public AuctionType getAuctionType() {
        return this.auctionType;
    }

    public void setAuctionType(AuctionType auctionType) {
        this.auctionType = auctionType;
    }

    public ItemCondition getItemCondition() {
        return this.itemCondition;
    }

    public void setItemCondition(ItemCondition itemCondition) {
        this.itemCondition = itemCondition;
    }

    public List<Long> getAuctionTagsList() {
        return auctionTagsList;
    }

    public void setAuctionTagsList(List<Long> newList)
    {
        auctionTagsList = newList;
    }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
