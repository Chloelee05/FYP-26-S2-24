package com.auction.model;

public enum Status {
    ACTIVE(1),
    SUSPENDED(2);

    private final int id;

    Status(int id) {
        this.id = id;
    }

    public int getId(){
        return this.id;
    }
}
