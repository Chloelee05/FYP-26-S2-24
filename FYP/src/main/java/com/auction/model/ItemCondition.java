package com.auction.model;

/**
 * The condition a seller declares for a listed item, mirroring
 * {@code auction_details.item_condition_id}. The numeric ids are the stored values.
 *
 * <p>This enum also acts as the whitelist for the buyer search condition filter: the
 * servlet resolves a client-supplied id through {@link #getItemCondition(int)} rather than
 * passing the raw string on, so {@link SearchFilter} can only ever hold a known id.</p>
 */
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

    /** The value stored in the database, not the enum ordinal. */
    public int getId(){
        return this.id;
    }

    /** Label shown to users, since the constant names are not presentable as they are. */
    public String getDisplayName(){
        return this.displayName;
    }

    /**
     * Maps a stored or submitted condition id back to its constant.
     *
     * @throws IllegalArgumentException when no constant carries that id, which is how the
     *         search filter rejects an unknown value from a client
     */
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
