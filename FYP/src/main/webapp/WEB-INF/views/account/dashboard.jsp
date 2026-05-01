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
            <c:if test="${param.updated == '1'}">
                <div class="alert alert-success alert-dismissible fade show" role="alert">
                    Your profile was updated.
                    <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
                </div>
            </c:if>

            <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 mb-4">
                <div class="d-flex align-items-center">
                    <c:choose>
                        <c:when test="${not empty profileImageUrl}">
                            <img src="<c:out value="${profileImageUrl}"/>" alt=""
                                 class="rounded-3 me-3 shadow-sm border" width="64" height="64"
                                 style="object-fit: cover;">
                        </c:when>
                        <c:otherwise>
                            <div class="bg-primary bg-gradient text-white rounded-3 p-3 me-3 shadow-sm">
                                <svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" fill="currentColor"
                                     class="bi bi-person-circle" viewBox="0 0 16 16" aria-hidden="true">
                                    <path d="M11 6a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"/>
                                    <path fill-rule="evenodd"
                                          d="M0 8a8 8 0 1 1 16 0A8 8 0 0 1 0 8zm8-7a7 7 0 1 0 0 14A7 7 0 0 0 8 1z"/>
                                </svg>
                            </div>
                        </c:otherwise>
                    </c:choose>
                    <div>
                        <h1 class="h3 mb-0 fw-bold text-dark">Account Management</h1>
                        <p class="text-muted mb-0 small">View and keep your profile information up to date.</p>
                    </div>
                </div>
                <a class="btn btn-primary" href="${pageContext.request.contextPath}/protected/account/edit">Edit profile</a>
            </div>

            <div class="card shadow-sm border-0 mb-4">
                <div class="card-header bg-white py-3 border-bottom">
                    <span class="fw-semibold text-secondary">Public preview (masked — PDPA)</span>
                </div>
                <div class="card-body p-4 small text-muted">
                    <p class="mb-1">How your name, email and phone may appear to others (e.g. auction history):</p>
                    <ul class="mb-0">
                        <li><strong>Name:</strong> <c:out value="${publicMaskedName}"/></li>
                        <li><strong>Email:</strong> <c:out value="${publicMaskedEmail}"/></li>
                        <li><strong>Phone:</strong>
                            <c:choose>
                                <c:when test="${empty publicMaskedPhone}">Not shown</c:when>
                                <c:otherwise><c:out value="${publicMaskedPhone}"/></c:otherwise>
                            </c:choose>
                        </li>
                    </ul>
                </div>
            </div>

            <div class="card shadow-sm border-0">
                <div class="card-header bg-white py-3 border-bottom">
                    <span class="fw-semibold text-secondary">Your profile (private)</span>
                </div>
                <div class="card-body p-4">
                    <dl class="row mb-0">
                        <dt class="col-sm-3 text-muted">Display name</dt>
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

                        <dt class="col-sm-3 text-muted">Profile image URL</dt>
                        <dd class="col-sm-9">
                            <c:choose>
                                <c:when test="${empty profileImageUrl}"><span class="text-muted">Not set</span></c:when>
                                <c:otherwise><a href="<c:out value="${profileImageUrl}"/>" target="_blank" rel="noopener"><c:out value="${profileImageUrl}"/></a></c:otherwise>
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

            <div class="card border-danger shadow-sm mt-4">
                <div class="card-header bg-danger text-white py-3">
                    <span class="fw-semibold">Danger zone</span>
                </div>
                <div class="card-body">
                    <p class="card-text text-muted small mb-3">
                        Permanently close your account. Your personal data will be removed or anonymised (PDPA).
                        Historical auction records may retain an anonymised user reference.
                    </p>
                    <button type="button" class="btn btn-outline-danger" data-bs-toggle="modal"
                            data-bs-target="#deleteAccountModal">
                        Delete my account
                    </button>
                </div>
            </div>
        </div>
    </div>
</div>

<div class="modal fade" id="deleteAccountModal" tabindex="-1" aria-labelledby="deleteAccountModalLabel"
     aria-hidden="true">
    <div class="modal-dialog modal-dialog-centered">
        <div class="modal-content border-danger">
            <div class="modal-header">
                <h5 class="modal-title text-danger" id="deleteAccountModalLabel">Delete account?</h5>
                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
            </div>
            <div class="modal-body">
                <p class="mb-2">This cannot be undone. You will be signed out immediately.</p>
                <p class="small text-muted mb-0">Type the confirmation in the next step by submitting the form below.</p>
            </div>
            <div class="modal-footer flex-column align-items-stretch gap-2">
                <form method="post" action="${pageContext.request.contextPath}/protected/account/delete"
                      class="d-grid">
                    <input type="hidden" name="confirm" value="DELETE"/>
                    <button type="submit" class="btn btn-danger">Yes, delete my account</button>
                </form>
                <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button>
            </div>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
