package com.auction.model;

import java.io.Serializable;

public class User implements Serializable{
    private int id;
    private String email;
    private String username;
    private String password;
    private Role role;

public User()
{
}

public User(String username, String email, String password, Role role)
{
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
}
