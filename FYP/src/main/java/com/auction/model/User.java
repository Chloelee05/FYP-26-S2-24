package com.auction.model;

import java.io.Serializable;

/**
 * An account, mapping to a row of the {@code users} table. Loaded by {@code UserDAO} and
 * used everywhere from login to the profile page.
 *
 * <p>Sensitive columns are handled three different ways, and the distinction matters:</p>
 * <ul>
 *   <li>{@link #getPassword()} holds a salted SHA-256 hash, which is one way. Nothing can
 *       read the password back, only check a candidate against it.</li>
 *   <li>Phone, address and the 2FA secret hold AES-GCM ciphertext, which is reversible,
 *       because the application genuinely needs those values back.</li>
 *   <li>Email is stored in the clear so login and mail delivery work, and is masked at
 *       the point of display instead.</li>
 * </ul>
 * See {@link com.auction.util.SecurityUtil} for all three.
 */
public class User implements Serializable {
    private int id;
    /** Stored unmasked so login and email delivery work; masked when displayed publicly. */
    private String email;
    private String username;
    /** Salted SHA-256 hash in the form {@code 1$salt$hash}, never the plaintext. */
    private String password;
    private Role role;
    /**
     * Whether this account may list items for sale.
     *
     * <p>Buying and selling live on one account: everyone registers as a
     * {@link Role#BUYER} and switches selling on later, so seller authorisation
     * reads this flag rather than {@link #role}. Accounts created before the merge
     * carry {@link Role#SELLER} and were backfilled to {@code true}.</p>
     */
    private boolean canSell;
    /** {@link com.auction.model.Status#getId()} — defaults to {@link com.auction.model.Status#ACTIVE}. */
    private int statusId = Status.ACTIVE.getId();
    private boolean twoFactorEnabled;
    private String twoFactorSecret; // AES-GCM encrypted; null when 2FA is disabled
    /** AES-GCM ciphertext (Base64) from {@link com.auction.util.SecurityUtil#encrypt}; nullable. */
    private String phoneEncrypted;
    /** AES-GCM ciphertext (Base64); nullable. */
    private String addressEncrypted;
    /** HTTPS URL to avatar image; nullable. */
    private String profileImageUrl;
    /** Account creation date (optional — loaded when column present). */
    private java.time.LocalDate memberSince;

    public User() {
    }

    public User(String username, String email, String password, Role role) {
        this.email = email;
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public int getId() {
        return this.id;
    }

    public void setId(int id)
    {
        this.id = id;
    }

    public String getEmail()
    {
        return this.email;
    }

    public void setEmail(String email)
    {
        this.email = email;
    }

    public String getUsername()
    {
        return this.username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getPassword()
    {
        return this.password;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public Role getRole()
    {
        return this.role;
    }

    public void setRole(Role role)
    {
        this.role = role;
    }

    /**
     * Whether this account may list items for sale.
     *
     * <p>True when the {@code can_sell} flag is set, or when the account still
     * carries the legacy {@link Role#SELLER} role — so a database that has not yet
     * had {@code migration_seller_capability.sql} applied keeps working.</p>
     */
    public boolean canSell()
    {
        return this.canSell || this.role == Role.SELLER;
    }

    public void setCanSell(boolean canSell)
    {
        this.canSell = canSell;
    }

    public int getStatusId() {
        return statusId;
    }

    public void setStatusId(int statusId) {
        this.statusId = statusId;
    }

    public boolean isTwoFactorEnabled()
    {
        return this.twoFactorEnabled;
    }

    public void setTwoFactorEnabled(boolean twoFactorEnabled)
    {
        this.twoFactorEnabled = twoFactorEnabled;
    }

    public String getTwoFactorSecret()
    {
        return this.twoFactorSecret;
    }

    public void setTwoFactorSecret(String twoFactorSecret)
    {
        this.twoFactorSecret = twoFactorSecret;
    }

    public String getPhoneEncrypted() {
        return phoneEncrypted;
    }

    public void setPhoneEncrypted(String phoneEncrypted) {
        this.phoneEncrypted = phoneEncrypted;
    }

    public String getAddressEncrypted() {
        return addressEncrypted;
    }

    public void setAddressEncrypted(String addressEncrypted) {
        this.addressEncrypted = addressEncrypted;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public void setProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public java.time.LocalDate getMemberSince() {
        return memberSince;
    }

    public void setMemberSince(java.time.LocalDate memberSince) {
        this.memberSince = memberSince;
    }
}
