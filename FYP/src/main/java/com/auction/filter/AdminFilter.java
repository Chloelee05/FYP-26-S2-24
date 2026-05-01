package com.auction.filter;

import com.auction.model.User;
import com.auction.model.Role;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@WebFilter("/admin/*")
public class AdminFilter implements Filter {
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;

        HttpSession session = req.getSession(false);
        if(session == null)
        {
            //redirect to login page
            return;
        }

        User user = (User)session.getAttribute("user");
        if(user== null || user.getRole() != Role.ADMIN)
        {
            //redirect
            return;
        }
        filterChain.doFilter(req, resp);
    }
}
