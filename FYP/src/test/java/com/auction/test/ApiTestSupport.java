package com.auction.test;

import com.auction.model.Role;
import com.auction.util.AuthSession;
import com.auction.util.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** Helpers for JSON API servlet unit tests. */
public final class ApiTestSupport {

    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ApiTestSupport() {}

    public static StringWriter bindJsonWriter(HttpServletResponse resp) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(resp.getWriter()).thenReturn(pw);
        doAnswer(inv -> {
            pw.flush();
            return null;
        }).when(resp).setStatus(anyInt());
        return sw;
    }

    public static AuthSession newBuyerSession(int userId) {
        AuthSession s = TokenStore.getInstance().create();
        s.setAttribute("userId", userId);
        s.setAttribute("userRole", Role.BUYER.name());
        return s;
    }

    public static AuthSession newSellerSession(int userId) {
        AuthSession s = TokenStore.getInstance().create();
        s.setAttribute("userId", userId);
        s.setAttribute("userRole", Role.SELLER.name());
        return s;
    }

    public static AuthSession newAdminSession(int userId) {
        AuthSession s = TokenStore.getInstance().create();
        s.setAttribute("userId", userId);
        s.setAttribute("userRole", Role.ADMIN.name());
        return s;
    }

    public static void withBearer(HttpServletRequest req, AuthSession session) {
        when(req.getHeader("Authorization")).thenReturn("Bearer " + session.getToken());
    }

    public static JsonNode parse(StringWriter sw) throws Exception {
        return JSON.readTree(sw.toString());
    }
}
