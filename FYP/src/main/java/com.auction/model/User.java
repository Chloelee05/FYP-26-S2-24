package com.auction.model;

import java.io.Serializable;
import java.time.LocalDate;

public class User implements Serializable{
    private int id;
    private String email;
    private String username;
    private String password;
    private String role;
    private LocalDate date_created;

public User(int id, String username, String email, String password, String role, LocalDate date_created)
{
    this.id = id;
    this.email = email;
    this.username = username;
    this.password = password;
    this.role = role;
    this.date_created = date_created;
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

    public String getRole()
    {
        return this.role;
    }

    public void setRole(String role)
    {
        this.role = role;
    }

    public LocalDate getDate_created()
    {
        return this.date_created;
    }

    public void setDate_created(LocalDate date_created)
    {
        this.date_created = date_created;
    }
}
