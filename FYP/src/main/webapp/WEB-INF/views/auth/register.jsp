<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Register — AuctionHub</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body class="bg-light">

<%@ include file="/WEB-INF/includes/navbar.jsp" %>

<div class="container py-5">
    <div class="row justify-content-center">
        <div class="col-md-6">
            <h1 class="h3 mb-4">Create account</h1>
            <c:if test="${not empty Error}">
                <div class="alert alert-danger" role="alert"><c:out value="${Error}"/></div>
            </c:if>
            <c:if test="${not empty Insert}">
                <div class="alert alert-success" role="alert">Registration successful. You can sign in.</div>
            </c:if>
            <form method="post" action="${pageContext.request.contextPath}/register" class="card shadow-sm p-4">
                <div class="mb-3">
                    <label class="form-label" for="username">Username</label>
                    <input class="form-control" type="text" id="username" name="username" required
                           value="<c:out value='${username}'/>" maxlength="120">
                </div>
                <div class="mb-3">
                    <label class="form-label" for="email">Email</label>
                    <input class="form-control" type="email" id="email" name="email" required
                           value="<c:out value='${email}'/>" autocomplete="email">
                </div>
                <div class="mb-3">
                    <label class="form-label" for="password">Password</label>
                    <input class="form-control" type="password" id="password" name="password" required
                           autocomplete="new-password">
                </div>
                <div class="mb-3">
                    <label class="form-label">Role</label>
                    <div>
                        <div class="form-check form-check-inline">
                            <input class="form-check-input" type="radio" name="role" id="roleBuyer" value="buyer"
                                   <c:if test="${empty role or role eq 'buyer'}">checked</c:if>>
                            <label class="form-check-label" for="roleBuyer">Buyer</label>
                        </div>
                        <div class="form-check form-check-inline">
                            <input class="form-check-input" type="radio" name="role" id="roleSeller" value="seller"
                                   <c:if test="${role eq 'seller'}">checked</c:if>>
                            <label class="form-check-label" for="roleSeller">Seller</label>
                        </div>
                    </div>
                </div>
                <button type="submit" class="btn btn-primary w-100">Register</button>
            </form>
            <p class="text-center mt-3 text-muted small">
                <a href="${pageContext.request.contextPath}/login">Already have an account?</a>
            </p>
        </div>
    </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
