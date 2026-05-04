<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Edit profile — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/profile.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<c:set var="ctx" value="${pageContext.request.contextPath}"/>

<div class="container py-4">
    <header class="profile-page-header d-flex flex-column flex-sm-row justify-content-between align-items-start align-items-sm-center gap-2 pb-3 mb-4">
        <div>
            <h1 class="h3 fw-bold mb-0">Edit profile</h1>
            <p class="text-muted small mb-0">View mode: <a href="${ctx}/protected/account">User Profile</a></p>
        </div>
        <div class="d-flex flex-wrap gap-2">
            <a href="${ctx}/protected/account" class="btn btn-outline-secondary btn-sm rounded-pill"><i class="bi bi-eye me-1"></i> View profile</a>
            <a href="${ctx}/protected/account/password" class="btn btn-outline-primary btn-sm rounded-pill">Change password</a>
        </div>
    </header>

    <div class="row justify-content-center">
        <div class="col-lg-8">
            <c:if test="${not empty error}">
                <div class="alert alert-danger" role="alert"><c:out value="${error}"/></div>
            </c:if>

            <div class="profile-main-panel p-3 p-md-4">
                <form id="profileEditForm" method="post" action="${ctx}/protected/account/update" novalidate>
                    <div class="mb-3">
                        <label class="form-label" for="username">Display name</label>
                        <input type="text" class="form-control" id="username" name="username" required maxlength="64"
                               value="<c:out value='${formUsername}'/>" autocomplete="name"
                               aria-describedby="usernameHelp usernameErr">
                        <div id="usernameHelp" class="form-text">2–64 characters. Letters, numbers, spaces, hyphen, dot, apostrophe.</div>
                        <div class="invalid-feedback" id="usernameErr"></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="email">Email</label>
                        <input type="email" class="form-control" id="email" name="email" required maxlength="254"
                               value="<c:out value='${formEmail}'/>" autocomplete="email" aria-describedby="emailErr">
                        <div class="invalid-feedback" id="emailErr"></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="phone">Phone <span class="text-muted fw-normal">(optional)</span></label>
                        <input type="text" class="form-control" id="phone" name="phone" autocomplete="tel"
                               placeholder="+65..."
                               value="<c:out value='${formPhone}'/>" aria-describedby="phoneErr">
                        <div class="invalid-feedback" id="phoneErr"></div>
                    </div>
                    <div class="mb-3">
                        <label class="form-label" for="address">Address <span class="text-muted fw-normal">(optional)</span></label>
                        <textarea class="form-control" id="address" name="address" rows="3" maxlength="500" aria-describedby="addressErr"><c:out value="${formAddress}"/></textarea>
                        <div class="invalid-feedback" id="addressErr"></div>
                    </div>
                    <div class="mb-4">
                        <label class="form-label" for="profileImageUrl">Profile picture URL <span class="text-muted fw-normal">(optional)</span></label>
                        <input type="url" class="form-control" id="profileImageUrl" name="profileImageUrl" maxlength="512"
                               placeholder="https://…"
                               value="<c:out value='${formProfileImageUrl}'/>" aria-describedby="urlHelp urlErr">
                        <div id="urlHelp" class="form-text">Must be an <code>https://</code> link (e.g. to an image you host).</div>
                        <div class="invalid-feedback" id="urlErr"></div>
                    </div>
                    <div class="d-grid gap-2 d-sm-flex justify-content-sm-end">
                        <a href="${ctx}/protected/account" class="btn btn-outline-secondary">Cancel</a>
                        <button type="submit" class="btn btn-primary">Save changes</button>
                    </div>
                </form>
            </div>
        </div>
    </div>
</div>

<%-- SCRUM-183: form stacks full width &lt;576px; panel padding reduces on xs. --%>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${ctx}/js/profile-validation.js"></script>
</body>
</html>
