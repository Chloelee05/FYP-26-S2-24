package com.auction.model;

public enum Status {
    ACTIVE(1),
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

    public int getId(){
        return this.id;
    }

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
