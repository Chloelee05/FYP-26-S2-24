package com.auction.model;

/**
 * Account state, mirroring {@code users.status_id}. Login checks this: only
 * {@link #ACTIVE} may sign in, and the other four each explain a different refusal.
 * The numeric ids are the stored values.
 */
public enum Status {
    /** Approved and able to sign in. The only state that permits login. */
    ACTIVE(1),
    /** Blocked by an administrator, usually after a report. The account still exists. */
    SUSPENDED(2),
    /** PDPA-oriented closed account: PII anonymised; row kept for referential integrity. */
    DELETED(3),
    /** Newly registered account awaiting admin approval; cannot log in yet. */
    PENDING(4),
    /** Registration rejected by an admin; cannot log in. */
    REJECTED(5);

    private final int id;

    Status(int id) {
        this.id = id;
    }

    /** The value stored in {@code users.status_id}, not the enum ordinal. */
    public int getId(){
        return this.id;
    }

    /**
     * Maps a stored {@code status_id} back to its constant.
     *
     * @throws IllegalArgumentException when no constant carries that id
     */
    public static Status getStatus(int id)
    {
        for(Status status: values()) {
            if (status.id == id) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid status");
    }
}
