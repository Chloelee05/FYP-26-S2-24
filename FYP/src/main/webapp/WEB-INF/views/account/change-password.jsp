<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Change password — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<div class="container py-4">
    <div class="row justify-content-center">
        <div class="col-md-6 col-lg-5">
            <div class="d-flex justify-content-between align-items-center mb-4">
                <h1 class="h3 fw-bold mb-0">Change password</h1>
                <a href="${pageContext.request.contextPath}/protected/account" class="btn btn-outline-secondary btn-sm">Back</a>
            </div>

            <p class="text-muted small mb-3">
                Use a strong password. You will be signed out after a successful change and must log in again.
            </p>

            <c:if test="${not empty error}">
                <div class="alert alert-danger" role="alert"><c:out value="${error}"/></div>
            </c:if>

            <div class="card shadow-sm border-0">
                <div class="card-body p-4">
                    <form method="post" action="${pageContext.request.contextPath}/protected/account/password">
                        <div class="mb-3">
                            <label for="currentPassword" class="form-label">Current password</label>
                            <input type="password" class="form-control" id="currentPassword" name="currentPassword"
                                   required autocomplete="current-password">
                        </div>
                        <div class="mb-3">
                            <label for="newPassword" class="form-label">New password</label>
                            <input type="password" class="form-control" id="newPassword" name="newPassword"
                                   required autocomplete="new-password" minlength="8">
                            <div class="form-text text-muted"><%= com.auction.util.InputValidator.getPasswordPolicySummary() %></div>
                        </div>
                        <div class="mb-4">
                            <label for="confirmPassword" class="form-label">Confirm new password</label>
                            <input type="password" class="form-control" id="confirmPassword" name="confirmPassword"
                                   required autocomplete="new-password" minlength="8">
                        </div>
                        <div class="d-grid gap-2">
                            <button type="submit" class="btn btn-primary">Update password</button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
