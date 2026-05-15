<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Create account — AuctionHub</title>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/auth.css">
</head>
<body class="auth-page">

<%@ include file="/WEB-INF/includes/auth-brand-header.jsp" %>

<main class="container px-3 pb-5">
    <div class="text-end auth-signin-inline pt-2">
        Already have an account?
        <a href="${pageContext.request.contextPath}/login" class="fw-semibold">Sign in</a>
    </div>

    <div class="row justify-content-center pt-2">
        <div class="col-12 col-sm-11 col-md-9 col-lg-7 col-xl-6">
            <h1 class="auth-title text-center mt-2 mb-1">Welcome to <span class="text-primary">AuctionHub</span></h1>
            <p class="text-center text-secondary mb-4">I want to:</p>

            <c:if test="${not empty Error}">
                <div class="alert alert-danger small" role="alert"><c:out value="${Error}"/></div>
            </c:if>
            <c:if test="${not empty Insert}">
                <div class="alert alert-success small" role="alert">Registration successful. You can sign in.</div>
            </c:if>

            <form id="registerForm" method="post" action="${pageContext.request.contextPath}/register" novalidate>
                <div class="row g-3 mb-3">
                    <div class="col-6">
                        <label class="account-type-card d-block mb-0 w-100" id="labelRoleBuyer">
                            <input type="radio" name="role" value="buyer" id="roleBuyer"
                                   class="visually-hidden" autocomplete="off"
                                   <c:if test="${empty signupRole or signupRole ne 'seller'}">checked="checked"</c:if>>
                            <span class="d-block" aria-hidden="true">
                                <span class="role-icon text-secondary"><i class="bi bi-cart3"></i></span>
                                <span class="d-block fw-bold">Buy Items</span>
                                <span class="d-block small text-secondary">Register as Buyer</span>
                            </span>
                        </label>
                    </div>
                    <div class="col-6">
                        <label class="account-type-card d-block mb-0 w-100" id="labelRoleSeller">
                            <input type="radio" name="role" value="seller" id="roleSeller"
                                   class="visually-hidden" autocomplete="off"
                                   <c:if test="${signupRole eq 'seller'}">checked="checked"</c:if>>
                            <span class="d-block" aria-hidden="true">
                                <span class="role-icon text-secondary"><i class="bi bi-cash-stack"></i></span>
                                <span class="d-block fw-bold">Sell Items</span>
                                <span class="d-block small text-secondary">Register as Seller</span>
                            </span>
                        </label>
                    </div>
                </div>

                <h2 class="h5 fw-bold mb-3">Create an account</h2>

                <div class="mb-3">
                    <label class="visually-hidden" for="username">Full Name</label>
                    <input class="form-control auth-input-pill" type="text" id="username" name="username"
                           placeholder="Full Name" maxlength="120"
                           value="<c:out value='${username}'/>" autocomplete="name" aria-required="true">
                    <div class="invalid-feedback small" data-feedback="username"></div>
                </div>
                <div class="mb-3">
                    <label class="visually-hidden" for="email">Email address</label>
                    <input class="form-control auth-input-pill" type="email" id="email" name="email"
                           placeholder="Email address"
                           value="<c:out value='${email}'/>" autocomplete="email" aria-required="true">
                    <div class="invalid-feedback small" data-feedback="email"></div>
                </div>
                <div class="mb-3">
                    <label class="visually-hidden" for="password">Password</label>
                    <input class="form-control auth-input-pill" type="password" id="password" name="password"
                           placeholder="Password" autocomplete="new-password" aria-required="true">
                    <div class="form-text small">8–128 characters; uppercase, lowercase, number, and special character.</div>
                    <div class="invalid-feedback small" data-feedback="password"></div>
                </div>
                <div class="mb-3">
                    <label class="visually-hidden" for="confirmPassword">Confirm Password</label>
                    <input class="form-control auth-input-pill" type="password" id="confirmPassword"
                           name="confirmPassword"
                           placeholder="Confirm Password" autocomplete="new-password" aria-required="true"
                           value="<c:out value='${confirmPassword}'/>">
                    <div class="invalid-feedback small" data-feedback="confirmPassword"></div>
                </div>

                <div class="form-check mb-4">
                    <input class="form-check-input" type="checkbox" name="termsAccept" value="on"
                           id="termsAccept">
                    <label class="form-check-label small" for="termsAccept">
                        I agree to the
                        <a href="#" class="text-decoration-none" onclick="return false;">User Agreement</a>
                        and
                        <a href="#" class="text-decoration-none" onclick="return false;">Privacy Notices</a>
                    </label>
                    <div class="invalid-feedback small" data-feedback="termsAccept"></div>
                </div>

                <button type="submit" class="btn btn-auth-primary text-white w-100">Create Account</button>
            </form>
        </div>
    </div>
</main>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/auth-validation.js"></script>
</body>
</html>
