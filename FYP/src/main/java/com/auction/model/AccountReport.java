package com.auction.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * One user reporting another, mapping to a row of {@code seller_reports}. Created when a
 * buyer or seller submits the report form, and read back by the admin reports queue.
 */
public class AccountReport implements Serializable {
    private Long id;
    /** The account that submitted the report. */
    private Long reporter_id;
    /** The account being reported. */
    private Long target_id;
    /** Category picked from the report form, for example a scam or a prohibited item. */
    private String reason;
    /** The reporter's free text, capped at {@code InputValidator.REPORT_DESCRIPTION_MAX_LENGTH}. */
    private String comment;
    private Instant created_at;
    /** Set once an administrator has acted on the report, which removes it from the queue. */
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
