<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>User Moderation — Admin</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/admin.css">
</head>
<body class="admin-body">
<div class="admin-layout">
    <%@ include file="/WEB-INF/includes/admin-sidebar.jspf" %>
    <main class="admin-main flex-grow-1 p-3 p-lg-4">
        <header class="mb-4">
            <h1 class="admin-page-title mb-1">User Moderation</h1>
            <p class="admin-subtitle mb-0">Manage users and enforce platform policies.</p>
        </header>

        <c:if test="${not empty adminFlash}">
            <div class="alert alert-success py-2 small"><c:out value="${adminFlash}"/></div>
        </c:if>
        <c:if test="${not empty adminFlashError}">
            <div class="alert alert-danger py-2 small"><c:out value="${adminFlashError}"/></div>
        </c:if>

        <div class="admin-table-card">
            <div class="table-responsive">
                <table class="table table-hover align-middle mb-0">
                    <thead class="table-light">
                    <tr>
                        <th scope="col">User</th>
                        <th scope="col">Email</th>
                        <th scope="col">Role</th>
                        <th scope="col" class="d-none d-lg-table-cell">Activity</th>
                        <th scope="col">Status</th>
                        <th scope="col" class="text-end">Actions</th>
                    </tr>
                    </thead>
                    <tbody>
                    <c:forEach var="u" items="${users}">
                        <tr>
                            <td>
                                <div class="fw-semibold"><c:out value="${u.username}"/></div>
                                <div class="small text-muted">Joined <c:out value="${u.joined}"/></div>
                            </td>
                            <td class="small"><c:out value="${u.email}"/></td>
                            <td>
                                <c:set var="r" value="${fn:toLowerCase(u.role.name())}"/>
                                <c:choose>
                                    <c:when test="${r == 'buyer'}"><span class="badge text-bg-info">buyer</span></c:when>
                                    <c:when test="${r == 'seller'}"><span class="badge text-bg-success">seller</span></c:when>
                                    <c:otherwise><span class="badge text-bg-secondary">admin</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="d-none d-lg-table-cell small">
                                Bids: ${u.bidCount}<br/>
                                Listings: ${u.listingCount}
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${u.statusId == 1}"><span class="badge text-bg-success">active</span></c:when>
                                    <c:when test="${u.statusId == 2}"><span class="badge text-bg-danger">banned</span></c:when>
                                    <c:otherwise><span class="badge text-bg-secondary">other</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end">
                                <c:choose>
                                    <c:when test="${u.role.name() == 'ADMIN'}">
                                        <span class="small text-muted">—</span>
                                    </c:when>
                                    <c:when test="${u.statusId == 1}">
                                        <form class="d-inline" method="post" action="${pageContext.request.contextPath}/admin/users/action"
                                              data-admin-user-action>
                                            <input type="hidden" name="action" value="SUSPEND"/>
                                            <input type="hidden" name="userid" value="${u.id}"/>
                                            <button type="submit" class="btn btn-danger btn-sm" data-confirm="Suspend this user?">Ban User</button>
                                        </form>
                                    </c:when>
                                    <c:when test="${u.statusId == 2}">
                                        <form class="d-inline" method="post" action="${pageContext.request.contextPath}/admin/users/action"
                                              data-admin-user-action>
                                            <input type="hidden" name="action" value="ACTIVE"/>
                                            <input type="hidden" name="userid" value="${u.id}"/>
                                            <button type="submit" class="btn btn-success btn-sm" data-confirm="Restore this user?">Unban User</button>
                                        </form>
                                    </c:when>
                                    <c:otherwise>
                                        <span class="small text-muted">—</span>
                                    </c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    </tbody>
                </table>
            </div>
        </div>
    </main>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/admin-panel.js"></script>
</body>
</html>
