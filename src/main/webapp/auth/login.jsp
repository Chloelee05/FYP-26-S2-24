<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Login - AuctionHub"/>
</jsp:include>

<div class="auth-container">
    <div class="card">
        <div class="card-body p-4">
            <div class="text-center mb-4">
                <i class="bi bi-hammer text-primary" style="font-size: 3rem;"></i>
                <h3 class="mt-2">Welcome Back</h3>
                <p class="text-muted">Sign in to your account</p>
            </div>

            <c:if test="${not empty error}">
                <div class="alert alert-danger alert-dismissible fade show" role="alert">
                    ${error}
                    <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
                </div>
            </c:if>

            <form method="post" action="${pageContext.request.contextPath}/auth/login">
                <div class="mb-3">
                    <label for="username" class="form-label">Username</label>
                    <div class="input-group">
                        <span class="input-group-text"><i class="bi bi-person"></i></span>
                        <input type="text" class="form-control" id="username" name="username"
                               value="${username}" required autofocus>
                    </div>
                </div>
                <div class="mb-3">
                    <label for="password" class="form-label">Password</label>
                    <div class="input-group">
                        <span class="input-group-text"><i class="bi bi-lock"></i></span>
                        <input type="password" class="form-control" id="password" name="password" required>
                    </div>
                </div>
                <button type="submit" class="btn btn-primary w-100 py-2 mt-2">
                    <i class="bi bi-box-arrow-in-right"></i> Sign In
                </button>
            </form>

            <div class="text-center mt-3">
                <p class="mb-0">Don't have an account?
                    <a href="${pageContext.request.contextPath}/auth/register">Register here</a>
                </p>
            </div>
        </div>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
