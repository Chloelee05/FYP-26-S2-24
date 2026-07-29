package com.auction.model.admin;

import java.time.LocalDateTime;

/**
 * One admin-editable landing page copy field.
 *
 * <p>Carries the presentation metadata the admin form needs (group, label, multiline,
 * order) and the seeded default so a field can be reset without the reset text living
 * in code. The public landing endpoint only needs {@code key} and {@code value}.</p>
 */
public final class LandingContentItem {

    private final String key;
    private final String group;
    private final String label;
    private final String value;
    private final String defaultValue;
    private final boolean multiline;
    private final int displayOrder;
    private final LocalDateTime updatedAt;
    /** Admin who last saved this field, or {@code null} if never edited. */
    private final Integer updatedBy;

    public LandingContentItem(String key, String group, String label, String value,
                              String defaultValue, boolean multiline, int displayOrder,
                              LocalDateTime updatedAt, Integer updatedBy) {
        this.key = key;
        this.group = group;
        this.label = label;
        this.value = value;
        this.defaultValue = defaultValue;
        this.multiline = multiline;
        this.displayOrder = displayOrder;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getKey() { return key; }
    public String getGroup() { return group; }
    public String getLabel() { return label; }
    public String getValue() { return value; }
    public String getDefaultValue() { return defaultValue; }
    public boolean isMultiline() { return multiline; }
    public int getDisplayOrder() { return displayOrder; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Integer getUpdatedBy() { return updatedBy; }

    /** {@code true} when the field still holds its seeded default. */
    public boolean isDefault() { return defaultValue != null && defaultValue.equals(value); }
}
