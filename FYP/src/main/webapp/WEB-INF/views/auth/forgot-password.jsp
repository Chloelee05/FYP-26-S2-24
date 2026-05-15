<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Forgot password — AuctionHub</title>
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
                <div class="text-end small mb-2">
                    <a href="${pageContext.request.contextPath}/login" class="text-decoration-none">Back to Login</a>
                </div>
                <h1 class="auth-title text-center mb-2">Forgot Password</h1>
                <p class="text-center text-secondary small mb-4">
                    Enter your email address to receive a verification code.
                </p>

                <c:if test="${not empty Error}">
                    <div class="alert alert-danger py-2 small" role="alert"><c:out value="${Error}"/></div>
                </c:if>
                <c:if test="${not empty OtpSent}">
                    <div class="alert alert-info py-2 small" role="alert"><c:out value="${OtpSent}"/></div>
                </c:if>

                <form id="forgotPasswordForm" method="post"
                      action="${pageContext.request.contextPath}/forgot-password" novalidate>
                    <div class="mb-3">
                        <label class="visually-hidden" for="identifier">Email address</label>
                        <input class="form-control auth-input-pill" type="email" id="identifier" name="identifier"
                               placeholder="Enter your email address"
                               value="<c:out value='${identifier}'/>" autocomplete="email" aria-required="true">
                        <div class="invalid-feedback small" data-feedback="identifier"></div>
                    </div>
                    <button type="submit" class="btn btn-auth-primary text-white w-100">Send Reset Code</button>
                </form>
            </div>
        </div>
    </div>
</main>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/auth-validation.js"></script>
</body>
</html>
