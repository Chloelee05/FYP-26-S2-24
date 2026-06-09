package com.auction.model;

import java.io.Serializable;
import java.time.Instant;

public class AccountReport implements Serializable {
    private Long id;
    private Long reporter_id;
    private Long target_id;
    private String reason;
    private String comment;
    private Instant created_at;
    private boolean resolved;

    public AccountReport()
    {

    }

    public AccountReport(Long reporter_id, Long target_id, String reason, String comment, Instant created_at){
        this.reporter_id = reporter_id;
        this.target_id = target_id;
        this.reason = reason;
        this.comment = comment;
        this.created_at = created_at;
    }

    public Long getId(){return this.id;}

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReporter_id() {
        return this.reporter_id;
    }

    public void setReporter_id(Long reporter_id){
        this.reporter_id = reporter_id;
    }

    public Long getTarget_id(){
        return this.target_id;
    }

    public void setTarget_id(Long target_id)
    {
        this.target_id = target_id;
    }

    public String getReason() {
        return this.reason;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public String getComment() {
        return this.comment;
    }

    public void setComment(String comment)
    {
        this.comment = comment;
    }

    public Instant getCreated_at() {
        return this.created_at;
    }

    public void setCreated_at(Instant created_at) {
        this.created_at = created_at;
    }

    public boolean getResolved() {
        return this.resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }
}
