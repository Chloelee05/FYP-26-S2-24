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

    public static Role getRole(int id)
    {
        for(Role role: values()) {
            if (role.id == id) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid role");
    } 
}
