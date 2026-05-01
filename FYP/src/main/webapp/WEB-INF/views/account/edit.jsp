<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Edit profile — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<div class="container py-4">
    <div class="row justify-content-center">
        <div class="col-lg-7">
            <div class="d-flex justify-content-between align-items-center mb-4">
                <h1 class="h3 fw-bold mb-0">Edit profile</h1>
                <a href="${pageContext.request.contextPath}/protected/account" class="btn btn-outline-secondary btn-sm">Back</a>
            </div>

            <c:if test="${not empty error}">
                <div class="alert alert-danger" role="alert"><c:out value="${error}"/></div>
            </c:if>

            <div class="card shadow-sm border-0">
                <div class="card-body p-4">
                    <form method="post" action="${pageContext.request.contextPath}/protected/account/update"
                          class="needs-validation" novalidate>
                        <div class="mb-3">
                            <label class="form-label" for="username">Display name</label>
                            <input type="text" class="form-control" id="username" name="username" required maxlength="64"
                                   value="<c:out value='${formUsername}'/>">
                            <div class="form-text">2–64 characters. Letters, numbers, spaces, hyphen, dot, apostrophe.</div>
                        </div>
                        <div class="mb-3">
                            <label class="form-label" for="email">Email</label>
                            <input type="email" class="form-control" id="email" name="email" required maxlength="254"
                                   value="<c:out value='${formEmail}'/>">
                        </div>
                        <div class="mb-3">
                            <label class="form-label" for="phone">Phone <span class="text-muted fw-normal">(optional)</span></label>
                            <input type="text" class="form-control" id="phone" name="phone" autocomplete="tel"
                                   placeholder="+65..."
                                   value="<c:out value='${formPhone}'/>">
                        </div>
                        <div class="mb-3">
                            <label class="form-label" for="address">Address <span class="text-muted fw-normal">(optional)</span></label>
                            <textarea class="form-control" id="address" name="address" rows="3" maxlength="500"><c:out value="${formAddress}"/></textarea>
                        </div>
                        <div class="mb-4">
                            <label class="form-label" for="profileImageUrl">Profile picture URL <span class="text-muted fw-normal">(optional)</span></label>
                            <input type="url" class="form-control" id="profileImageUrl" name="profileImageUrl" maxlength="512"
                                   placeholder="https://…"
                                   value="<c:out value='${formProfileImageUrl}'/>">
                            <div class="form-text">Must be an <code>https://</code> link (e.g. to an image you host).</div>
                        </div>
                        <div class="d-grid gap-2 d-sm-flex justify-content-sm-end">
                            <a href="${pageContext.request.contextPath}/protected/account" class="btn btn-outline-secondary">Cancel</a>
                            <button type="submit" class="btn btn-primary">Save changes</button>
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
