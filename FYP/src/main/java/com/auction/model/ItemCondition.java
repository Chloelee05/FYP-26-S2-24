package com.auction.model;

public enum ItemCondition {
    BRAND_NEW(1, "Brand New"),
    SLIGHTLY_USED(2, "Slightly Used"),
    USED(3, "Used"),
    DAMAGED(4, "Damaged");

    private final int id;
    private final String displayName;

    ItemCondition(int id, String displayName){
        this.id = id;
        this.displayName = displayName;
    }

    public int getId(){
        return this.id;
    }

    public String getDisplayName(){
        return this.displayName;
    }

    public static ItemCondition getItemCondition(int id)
    {
        for(ItemCondition itemCondition: values()) {
            if (itemCondition.id == id) {
                return itemCondition;
            }
        }
        throw new IllegalArgumentException("Invalid Item Condition");
    }
}
