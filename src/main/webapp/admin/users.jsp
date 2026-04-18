<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Manage Users - AuctionHub"/>
</jsp:include>

<div class="d-flex justify-content-between align-items-center mb-4">
    <div>
        <h2><i class="bi bi-people text-primary"></i> User Management</h2>
        <p class="text-muted mb-0">${users.size()} registered user(s)</p>
    </div>
    <a href="${pageContext.request.contextPath}/admin/dashboard" class="btn btn-outline-secondary">
        <i class="bi bi-arrow-left"></i> Back to Dashboard
    </a>
</div>

<div class="card border-0 shadow-sm" style="border-radius:12px;">
    <div class="table-responsive">
        <table class="table table-hover mb-0">
            <thead class="table-light">
                <tr>
                    <th>ID</th>
                    <th>Username</th>
                    <th>Email</th>
                    <th>Full Name</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Registered</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                <c:forEach var="u" items="${users}">
                    <tr>
                        <td>${u.id}</td>
                        <td><strong>${u.username}</strong></td>
                        <td>${u.email}</td>
                        <td>${u.fullName}</td>
                        <td>
                            <c:choose>
                                <c:when test="${u.role == 'ADMIN'}">
                                    <span class="badge bg-danger">${u.role}</span>
                                </c:when>
                                <c:when test="${u.role == 'SELLER'}">
                                    <span class="badge bg-warning text-dark">${u.role}</span>
                                </c:when>
                                <c:otherwise>
                                    <span class="badge bg-primary">${u.role}</span>
                                </c:otherwise>
                            </c:choose>
                        </td>
                        <td>
                            <c:choose>
                                <c:when test="${u.status == 'ACTIVE'}">
                                    <span class="badge bg-success">${u.status}</span>
                                </c:when>
                                <c:when test="${u.status == 'SUSPENDED'}">
                                    <span class="badge bg-danger">${u.status}</span>
                                </c:when>
                                <c:otherwise>
                                    <span class="badge bg-warning text-dark">${u.status}</span>
                                </c:otherwise>
                            </c:choose>
                        </td>
                        <td><fmt:formatDate value="${u.createdAt}" pattern="MMM dd, yyyy"/></td>
                        <td>
                            <c:if test="${u.role != 'ADMIN'}">
                                <c:choose>
                                    <c:when test="${u.status == 'ACTIVE'}">
                                        <form method="post" action="${pageContext.request.contextPath}/admin/users"
                                              style="display:inline;">
                                            <input type="hidden" name="userId" value="${u.id}">
                                            <input type="hidden" name="action" value="suspend">
                                            <button type="submit" class="btn btn-sm btn-outline-danger"
                                                    onclick="return confirm('Suspend this user?');">
                                                <i class="bi bi-slash-circle"></i> Suspend
                                            </button>
                                        </form>
                                    </c:when>
                                    <c:when test="${u.status == 'SUSPENDED'}">
                                        <form method="post" action="${pageContext.request.contextPath}/admin/users"
                                              style="display:inline;">
                                            <input type="hidden" name="userId" value="${u.id}">
                                            <input type="hidden" name="action" value="activate">
                                            <button type="submit" class="btn btn-sm btn-outline-success">
                                                <i class="bi bi-check-circle"></i> Activate
                                            </button>
                                        </form>
                                    </c:when>
                                    <c:when test="${u.status == 'PENDING'}">
                                        <form method="post" action="${pageContext.request.contextPath}/admin/users"
                                              style="display:inline;">
                                            <input type="hidden" name="userId" value="${u.id}">
                                            <input type="hidden" name="action" value="approve">
                                            <button type="submit" class="btn btn-sm btn-outline-success">
                                                <i class="bi bi-check-lg"></i> Approve
                                            </button>
                                        </form>
                                        <form method="post" action="${pageContext.request.contextPath}/admin/users"
                                              style="display:inline;">
                                            <input type="hidden" name="userId" value="${u.id}">
                                            <input type="hidden" name="action" value="suspend">
                                            <button type="submit" class="btn btn-sm btn-outline-danger">
                                                <i class="bi bi-x-lg"></i> Reject
                                            </button>
                                        </form>
                                    </c:when>
                                </c:choose>
                            </c:if>
                        </td>
                    </tr>
                </c:forEach>
            </tbody>
        </table>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
