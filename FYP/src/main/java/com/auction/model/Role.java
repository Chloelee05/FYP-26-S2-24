package com.auction.model;

/**
 * Account role, mirroring {@code users.role_id}.
 *
 * <p>Role alone no longer decides whether someone can sell. Buying and selling were merged
 * onto one account, so every new registration is a {@link #BUYER} and the ability to list
 * items is carried by the {@code users.can_sell} flag instead. See
 * {@link com.auction.util.RbacUtil#isSeller} for how the two are combined.</p>
 */
public enum Role {
    /** Platform administrator: moderation, user approval and the admin dashboard. */
    ADMIN(1),
    /** The role every account registers with. Selling is granted separately by can_sell. */
    BUYER(2),
    /**
     * Legacy role kept for accounts created before buyer and seller were merged. It still
     * grants selling so an un-migrated database keeps working, but nothing issues it now.
     */
    SELLER(3);

    private final int id;

    Role(int id){
        this.id = id;
    }

    /** The value stored in {@code users.role_id}, not the enum ordinal. */
    public int getId(){
        return this.id;
    }

    /**
     * Maps a stored {@code role_id} back to its constant.
     *
     * @throws IllegalArgumentException when no constant carries that id
     */
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
