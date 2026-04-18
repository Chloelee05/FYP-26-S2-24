<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Register - AuctionHub"/>
</jsp:include>

<div class="auth-container">
    <div class="card">
        <div class="card-body p-4">
            <div class="text-center mb-4">
                <i class="bi bi-person-plus text-primary" style="font-size: 3rem;"></i>
                <h3 class="mt-2">Create Account</h3>
                <p class="text-muted">Join AuctionHub today</p>
            </div>

            <c:if test="${not empty error}">
                <div class="alert alert-danger alert-dismissible fade show" role="alert">
                    ${error}
                    <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
                </div>
            </c:if>

            <form method="post" action="${pageContext.request.contextPath}/auth/register">
                <div class="mb-3">
                    <label for="fullName" class="form-label">Full Name <span class="text-danger">*</span></label>
                    <input type="text" class="form-control" id="fullName" name="fullName"
                           value="${fullName}" required>
                </div>
                <div class="mb-3">
                    <label for="username" class="form-label">Username <span class="text-danger">*</span></label>
                    <input type="text" class="form-control" id="username" name="username"
                           value="${username}" required>
                </div>
                <div class="mb-3">
                    <label for="email" class="form-label">Email <span class="text-danger">*</span></label>
                    <input type="email" class="form-control" id="email" name="email"
                           value="${email}" required>
                </div>
                <div class="mb-3">
                    <label for="password" class="form-label">Password <span class="text-danger">*</span></label>
                    <input type="password" class="form-control" id="password" name="password"
                           minlength="6" required>
                    <div class="form-text">At least 6 characters.</div>
                </div>
                <div class="mb-3">
                    <label for="confirmPassword" class="form-label">Confirm Password <span class="text-danger">*</span></label>
                    <input type="password" class="form-control" id="confirmPassword" name="confirmPassword" required>
                </div>
                <div class="mb-4">
                    <label class="form-label">I want to <span class="text-danger">*</span></label>
                    <div class="d-flex gap-3">
                        <div class="form-check flex-fill border rounded p-3">
                            <input class="form-check-input" type="radio" name="role" id="roleBuyer"
                                   value="BUYER" ${role == 'SELLER' ? '' : 'checked'}>
                            <label class="form-check-label" for="roleBuyer">
                                <i class="bi bi-cart"></i> <strong>Buy</strong>
                                <br><small class="text-muted">Bid on items</small>
                            </label>
                        </div>
                        <div class="form-check flex-fill border rounded p-3">
                            <input class="form-check-input" type="radio" name="role" id="roleSeller"
                                   value="SELLER" ${role == 'SELLER' ? 'checked' : ''}>
                            <label class="form-check-label" for="roleSeller">
                                <i class="bi bi-shop"></i> <strong>Sell</strong>
                                <br><small class="text-muted">List items for auction</small>
                            </label>
                        </div>
                    </div>
                </div>
                <button type="submit" class="btn btn-primary w-100 py-2">
                    <i class="bi bi-person-plus"></i> Create Account
                </button>
            </form>

            <div class="text-center mt-3">
                <p class="mb-0">Already have an account?
                    <a href="${pageContext.request.contextPath}/auth/login">Sign in</a>
                </p>
            </div>
        </div>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
