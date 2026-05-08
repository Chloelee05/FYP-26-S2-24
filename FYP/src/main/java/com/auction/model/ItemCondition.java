package com.auction.model;

public enum ItemCondition {
    BRAND_NEW(1),
    SLIGHTLY_USED(2),
    USED(3),
    DAMAGED(4);

    private final int id;

    ItemCondition(int id){
        this.id = id;
    }

    public int getId(){
        return this.id;
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
