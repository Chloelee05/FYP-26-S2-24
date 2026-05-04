<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Sign in — AuctionHub</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/auth.css">
</head>
<body class="auth-page">

<%@ include file="/WEB-INF/includes/auth-brand-header.jsp" %>

<main class="container px-3 pb-5">
    <div class="row justify-content-center pt-2 pt-md-4">
        <div class="col-12 col-sm-10 col-md-7 col-lg-5 col-xl-4">
            <div class="card auth-card p-4 p-md-5">
                <h1 class="auth-title text-center mb-2">Sign in your account</h1>
                <p class="text-center text-secondary mb-4">
                    New to AuctionHub?
                    <a href="${pageContext.request.contextPath}/register"
                       class="btn btn-auth-secondary btn-sm ms-2 align-middle">Create Account</a>
                </p>

                <c:if test="${not empty Error}">
                    <div class="alert alert-danger py-2 small" role="alert"><c:out value="${Error}"/></div>
                </c:if>

                <form id="loginForm" method="post" action="${pageContext.request.contextPath}/login" novalidate>
                    <div class="mb-3">
                        <label class="visually-hidden" for="email">Email address</label>
                        <input class="form-control auth-input-pill" type="email" id="email" name="email"
                               placeholder="Email address"
                               value="<c:out value='${email}'/>" autocomplete="username" aria-required="true">
                        <div class="invalid-feedback small" data-feedback="email"></div>
                    </div>
                    <div class="mb-3">
                        <label class="visually-hidden" for="password">Password</label>
                        <input class="form-control auth-input-pill" type="password" id="password" name="password"
                               placeholder="Password" autocomplete="current-password" aria-required="true">
                        <div class="invalid-feedback small" data-feedback="password"></div>
                    </div>
                    <button type="submit" class="btn btn-auth-primary text-white w-100 mb-3">Login</button>
                    <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 small">
                        <div class="form-check m-0">
                            <input class="form-check-input" type="checkbox" name="rememberMe" value="1"
                                   id="rememberMe">
                            <label class="form-check-label" for="rememberMe">Remember me</label>
                        </div>
                        <a href="${pageContext.request.contextPath}/forgot-password"
                           class="link-primary text-decoration-underline">Forgot password?</a>
                    </div>
                </form>
            </div>
        </div>
    </div>
</main>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/auth-validation.js"></script>
</body>
</html>
