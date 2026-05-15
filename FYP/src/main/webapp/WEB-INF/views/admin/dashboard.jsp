<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Dashboard — Admin</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/admin.css">
</head>
<body class="admin-body">
<div class="admin-layout">
    <%@ include file="/WEB-INF/includes/admin-sidebar.jspf" %>
    <main class="admin-main flex-grow-1 p-3 p-lg-4">
        <header class="mb-4">
            <h1 class="admin-page-title mb-1">Dashboard Overview</h1>
            <p class="admin-subtitle mb-0">Monitor platform activity and key metrics.</p>
        </header>

        <div class="row g-3 mb-4">
            <div class="col-6 col-xl-3">
                <div class="admin-card-stat p-3 d-flex gap-3 align-items-start">
                    <div class="admin-stat-icon bg-primary bg-opacity-10 text-primary"><i class="bi bi-people" aria-hidden="true"></i></div>
                    <div>
                        <div class="fs-4 fw-semibold">${metrics.totalUsers}</div>
                        <div class="text-muted small">Total Users</div>
                        <div class="small text-primary">${metrics.activeUsers} active</div>
                    </div>
                </div>
            </div>
            <div class="col-6 col-xl-3">
                <div class="admin-card-stat p-3 d-flex gap-3 align-items-start">
                    <div class="admin-stat-icon bg-success bg-opacity-10 text-success"><i class="bi bi-clipboard-check" aria-hidden="true"></i></div>
                    <div>
                        <div class="fs-4 fw-semibold">${metrics.activeListings}</div>
                        <div class="text-muted small">Active Listings</div>
                        <div class="small text-success">${metrics.totalListings} total</div>
                    </div>
                </div>
            </div>
            <div class="col-6 col-xl-3">
                <div class="admin-card-stat p-3 d-flex gap-3 align-items-start">
                    <div class="admin-stat-icon bg-warning bg-opacity-10 text-warning"><i class="bi bi-exclamation-triangle" aria-hidden="true"></i></div>
                    <div>
                        <div class="fs-4 fw-semibold">${metrics.flaggedListings}</div>
                        <div class="text-muted small">Flagged Items</div>
                        <div class="small text-warning">Needs review</div>
                    </div>
                </div>
            </div>
            <div class="col-6 col-xl-3">
                <div class="admin-card-stat p-3 d-flex gap-3 align-items-start">
                    <div class="admin-stat-icon bg-danger bg-opacity-10 text-danger"><i class="bi bi-currency-dollar" aria-hidden="true"></i></div>
                    <div>
                        <div class="fs-4 fw-semibold"><fmt:formatNumber value="${metrics.revenueDollars}" type="currency" currencyCode="USD"/></div>
                        <div class="text-muted small">Revenue</div>
                        <div class="small text-success">${metrics.revenueGrowthLabel}</div>
                    </div>
                </div>
            </div>
        </div>

        <div class="row g-4">
            <div class="col-lg-6">
                <div class="admin-table-card p-3 mb-3">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <h2 class="h6 mb-0">Users</h2>
                        <a class="small" href="${pageContext.request.contextPath}/admin/users">View all</a>
                    </div>
                    <div class="table-responsive">
                        <table class="table table-sm align-middle mb-0">
                            <thead><tr><th>User</th><th>Status</th><th class="d-none d-md-table-cell">Role</th></tr></thead>
                            <tbody>
                            <c:forEach var="u" items="${previewUsers}">
                                <tr>
                                    <td><strong><c:out value="${u.username}"/></strong></td>
                                    <td>
                                        <c:choose>
                                            <c:when test="${u.statusId == 1}"><span class="badge text-bg-success">active</span></c:when>
                                            <c:when test="${u.statusId == 2}"><span class="badge text-bg-danger">banned</span></c:when>
                                            <c:otherwise><span class="badge text-bg-secondary">other</span></c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td class="d-none d-md-table-cell"><span class="badge text-bg-light text-dark"><c:out value="${fn:toLowerCase(u.role.name())}"/></span></td>
                                </tr>
                            </c:forEach>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
            <div class="col-lg-6">
                <div class="admin-table-card p-3 mb-3">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <h2 class="h6 mb-0">Listings</h2>
                        <a class="small" href="${pageContext.request.contextPath}/admin/listings">View all</a>
                    </div>
                    <div class="table-responsive">
                        <table class="table table-sm align-middle mb-0">
                            <thead><tr><th>Title</th><th>Reports</th><th>Status</th></tr></thead>
                            <tbody>
                            <c:forEach var="l" items="${previewListings}">
                                <tr>
                                    <td><c:out value="${l.title}"/></td>
                                    <td>
                                        <c:set var="rc" value="${l.reportCount}"/>
                                        <c:choose>
                                            <c:when test="${rc == 0}"><span class="badge text-bg-secondary">${rc}</span></c:when>
                                            <c:when test="${rc lt 5}"><span class="badge text-bg-warning text-dark">${rc}</span></c:when>
                                            <c:otherwise><span class="badge text-bg-danger">${rc}</span></c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td><span class="badge text-bg-light text-dark"><c:out value="${l.moderationState}"/></span></td>
                                </tr>
                            </c:forEach>
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>

        <section class="admin-table-card p-3 mt-2">
            <h2 class="h6 mb-3">Recent Activity</h2>
            <ul class="list-unstyled mb-0">
                <c:forEach var="a" items="${activities}">
                    <c:set var="dotClass" value="bg-secondary"/>
                    <c:if test="${a.severity == 'success'}"><c:set var="dotClass" value="bg-success"/></c:if>
                    <c:if test="${a.severity == 'warning'}"><c:set var="dotClass" value="bg-warning"/></c:if>
                    <c:if test="${a.severity == 'danger'}"><c:set var="dotClass" value="bg-danger"/></c:if>
                    <li class="d-flex gap-3 py-2 border-bottom border-light-subtle">
                        <span class="admin-activity-dot flex-shrink-0 ${dotClass}"></span>
                        <div>
                            <div><c:out value="${a.message}"/></div>
                            <c:if test="${not empty a.timeLabel}">
                                <div class="small text-muted"><c:out value="${a.timeLabel}"/></div>
                            </c:if>
                        </div>
                    </li>
                </c:forEach>
            </ul>
        </section>
    </main>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/admin-panel.js"></script>
</body>
</html>
