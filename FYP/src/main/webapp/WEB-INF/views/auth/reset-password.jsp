<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Reset password — AuctionHub</title>
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
                <h1 class="auth-title text-center mb-2">Set new password</h1>
                <p class="text-center text-secondary small mb-4">
                    Enter the code sent to your email and choose a new password.
                </p>

                <c:if test="${not empty simulatedOtp}">
                    <div class="alert alert-warning small py-2" role="alert">
                        <strong>Dev only:</strong> simulated OTP is
                        <code><c:out value="${simulatedOtp}"/></code>
                    </div>
                </c:if>

                <c:if test="${not empty Error}">
                    <div class="alert alert-danger py-2 small" role="alert"><c:out value="${Error}"/></div>
                </c:if>
                <c:if test="${not empty Reset}">
                    <div class="alert alert-success py-2 small" role="alert"><c:out value="${Reset}"/></div>
                    <p class="text-center mb-0">
                        <a href="${pageContext.request.contextPath}/login">Sign in</a>
                    </p>
                </c:if>

                <c:if test="${empty Reset}">
                    <form id="resetPasswordForm" method="post"
                          action="${pageContext.request.contextPath}/reset-password" novalidate>
                        <input type="hidden" name="identifier" value="<c:out value='${resetIdentifier}'/>">
                        <div class="mb-3">
                            <label class="form-label small" for="otp">Verification code</label>
                            <input class="form-control auth-input-pill" type="text" id="otp" name="otp"
                                   placeholder="6-digit code" inputmode="numeric" pattern="[0-9]{6}"
                                   autocomplete="one-time-code" aria-required="true">
                            <div class="invalid-feedback small" data-feedback="otp"></div>
                        </div>
                        <div class="mb-3">
                            <label class="form-label small" for="newPassword">New password</label>
                            <input class="form-control auth-input-pill" type="password" id="newPassword"
                                   name="newPassword" placeholder="New password" autocomplete="new-password"
                                   aria-required="true">
                            <div class="invalid-feedback small" data-feedback="newPassword"></div>
                        </div>
                        <div class="mb-3">
                            <label class="form-label small" for="confirmNewPassword">Confirm new password</label>
                            <input class="form-control auth-input-pill" type="password" id="confirmNewPassword"
                                   name="confirmNewPassword" placeholder="Confirm password"
                                   autocomplete="new-password" aria-required="true">
                            <div class="invalid-feedback small" data-feedback="confirmNewPassword"></div>
                        </div>
                        <button type="submit" class="btn btn-auth-primary text-white w-100">Update password</button>
                    </form>
                </c:if>
            </div>
        </div>
    </div>
</main>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/auth-validation.js"></script>
</body>
</html>
