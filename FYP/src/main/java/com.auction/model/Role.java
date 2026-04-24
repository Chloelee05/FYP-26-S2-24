package com.auction.model;

public enum Role {
    ADMIN(1),
    BUYER(2),
    SELLER(3);

    private final int id;

    Role(int id){
        this.id = id;
    }

    public int getId(){
        return this.id;
    }
}
