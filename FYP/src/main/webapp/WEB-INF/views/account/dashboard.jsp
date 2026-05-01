<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>My Account — AuctionHub</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<div class="container py-4">
    <div class="row justify-content-center">
        <div class="col-lg-8">
            <div class="d-flex align-items-center mb-4">
                <div class="bg-primary bg-gradient text-white rounded-3 p-3 me-3 shadow-sm">
                    <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" fill="currentColor"
                         class="bi bi-person-circle" viewBox="0 0 16 16" aria-hidden="true">
                        <path d="M11 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"/>
                        <path fill-rule="evenodd"
                              d="M0 8a8 8 0 1 1 16 0A8 8 0 0 1 0 8zm8-7a7 7 0 1 0 0 14A7 7 0 0 0 8 1z"/>
                    </svg>
                </div>
                <div>
                    <h1 class="h3 mb-0 fw-bold text-dark">Account Management</h1>
                    <p class="text-muted mb-0 small">View and keep your profile information up to date.</p>
                </div>
            </div>

            <div class="card shadow-sm border-0">
                <div class="card-header bg-white py-3 border-bottom">
                    <span class="fw-semibold text-secondary">Profile</span>
                </div>
                <div class="card-body p-4">
                    <dl class="row mb-0">
                        <dt class="col-sm-3 text-muted">Username</dt>
                        <dd class="col-sm-9"><c:out value="${profileUsername}"/></dd>

                        <dt class="col-sm-3 text-muted">Email</dt>
                        <dd class="col-sm-9"><c:out value="${profileEmail}"/></dd>

                        <dt class="col-sm-3 text-muted">Role</dt>
                        <dd class="col-sm-9"><span class="badge bg-secondary"><c:out value="${profileRole}"/></span></dd>

                        <dt class="col-sm-3 text-muted">2FA</dt>
                        <dd class="col-sm-9">
                            <c:choose>
                                <c:when test="${twoFactorEnabled}"><span class="text-success fw-medium">Enabled</span></c:when>
                                <c:otherwise><span class="text-muted">Disabled</span></c:otherwise>
                            </c:choose>
                        </dd>

                        <dt class="col-sm-3 text-muted">Phone</dt>
                        <dd class="col-sm-9">
                            <c:choose>
                                <c:when test="${empty profilePhone}"><span class="text-muted">Not set</span></c:when>
                                <c:otherwise><c:out value="${profilePhone}"/></c:otherwise>
                            </c:choose>
                        </dd>

                        <dt class="col-sm-3 text-muted">Address</dt>
                        <dd class="col-sm-9 mb-0">
                            <c:choose>
                                <c:when test="${empty profileAddress}"><span class="text-muted">Not set</span></c:when>
                                <c:otherwise><c:out value="${profileAddress}"/></c:otherwise>
                            </c:choose>
                        </dd>
                    </dl>
                </div>
            </div>

            <p class="text-muted small mt-3 mb-0">
                Phone and address are decrypted only for your session using the platform encryption key.
            </p>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
