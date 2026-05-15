package com.auction.model;

import java.io.Serializable;

public class User implements Serializable {
    private int id;
    private String email;
    private String username;
    private String password;
    private Role role;
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
