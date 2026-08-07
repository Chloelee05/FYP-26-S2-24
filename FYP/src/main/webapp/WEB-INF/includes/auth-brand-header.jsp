<%--
  Minimal brand bar for the authentication pages. Statically included by login.jsp,
  register.jsp, forgot-password.jsp and reset-password.jsp. Deliberately plainer than the home
  navbar: these pages carry no navigation or search, only a link back to the landing page.
  Legacy JSP layout fragment; the SPA has its own auth layout.
--%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<header class="auth-topbar">
    <a class="auth-brand" href="${pageContext.request.contextPath}/">
        <span class="auth-brand-icon" aria-hidden="true"><i class="bi bi-hammer"></i></span>
        AuctionHub
    </a>
</header>
